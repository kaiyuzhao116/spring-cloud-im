package com.song.wsgateway.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.api.im.dto.SendResultDTO;
import com.song.common.BizException;
import com.song.wsgateway.protocol.Frame;
import com.song.wsgateway.protocol.FrameType;
import com.song.wsgateway.session.ChannelAttrs;
import com.song.wsgateway.session.CloseReason;
import com.song.wsgateway.session.OnlineRegistry;
import com.song.wsgateway.support.ImClient;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;

/**
 * 业务帧处理（上行）。
 *
 * <p>职责只有四件事：</p>
 * <ol>
 *   <li>限大小：单帧超过 4KB 直接以 1009 断开（不解码，省内存）</li>
 *   <li>分流：PING 当场回 PONG；MSG 交给业务线程池</li>
 *   <li>落库：MSG 通过 Dubbo 调 im 存下来，拿到 seq</li>
 *   <li>回执：无论成功失败都回 ACK，<b>seq=0 表示落库失败</b></li>
 * </ol>
 *
 * <p>铁律：Dubbo 是阻塞调用，只能在业务线程池（ws-biz-*）里执行，
 * 绝不能占用 Netty 的 EventLoop。</p>
 *
 * <p>行文约定：{@code seq == 0} 是"没存成"的哨兵值，客户端据此显示发送失败。</p>
 */
public class BusinessHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(BusinessHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单帧业务上限：4KB。协议层 64KB 由 netty.max-frame-length 控制，这里是业务层 */
    private static final int MAX_TEXT_BYTES = 4096;

    /** WebSocket 标准关闭码：消息过大（RFC 6455） */
    private static final int CLOSE_MESSAGE_TOO_BIG = 1009;

    /** 落库失败时回给客户端的 seq */
    private static final long SEQ_FAILED = 0L;

    private final ImClient imClient;

    private final ExecutorService bizExecutor;

    private final OnlineRegistry onlineRegistry;

    public BusinessHandler(ImClient imClient, ExecutorService bizExecutor, OnlineRegistry onlineRegistry) {
        this.imClient = imClient;
        this.bizExecutor = bizExecutor;
        this.onlineRegistry = onlineRegistry;
    }

    // ==================== 入口 ====================

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        if (isTooLarge(frame)) {
            rejectTooLarge(ctx, frame);
            return;
        }

        Frame in = parseOrNull(ctx, frame);
        if (in != null) {
            dispatch(ctx, in);
        }
    }

    private boolean isTooLarge(TextWebSocketFrame frame) {
        // 用字节数而不是 frame.text()：text() 会先把字节解码成 String，那才是吃内存的一步
        return frame.content().readableBytes() > MAX_TEXT_BYTES;
    }

    private void rejectTooLarge(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        int bytes = frame.content().readableBytes();
        log.warn("帧过大，断开连接 userId={} bytes={} 上限={}", userIdOf(ctx), bytes, MAX_TEXT_BYTES);
        closeWithReason(ctx, CloseReason.MESSAGE_TOO_BIG, CLOSE_MESSAGE_TOO_BIG, "message too big");
    }

    /** 解析失败返回 null（只记日志，不断连接：可能只是客户端发了条调试消息） */
    private Frame parseOrNull(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        try {
            return MAPPER.readValue(frame.text(), Frame.class);
        } catch (Exception e) {
            log.warn("帧解析失败 userId={} text={}", userIdOf(ctx), frame.text());
            return null;
        }
    }

    private void dispatch(ChannelHandlerContext ctx, Frame in) {
        FrameType type = in.frameType();
        if (type == null) {
            log.warn("未知帧类型，忽略 type={}", in.getType());
            return;
        }

        switch (type) {
            case PING -> {
                sendFrame(ctx, Frame.pong());                      // 轻量，留在 EventLoop
                Long uid = userIdOf(ctx);
                if (uid != null) {
                    // 借客户端心跳给在线表续期：25 秒一次，远小于 120 秒 TTL
                    onlineRegistry.touch(uid);
                }
            }
            case MSG -> handleMessageAsync(ctx, in);                // 重活，切线程池
            default -> log.warn("不支持的上行帧 type={}", in.getType());
        }
    }

    // ==================== 发消息 ====================

    /** 交给业务线程池执行，避免阻塞 EventLoop（队列满时由 CallerRunsPolicy 形成背压） */
    private void handleMessageAsync(ChannelHandlerContext ctx, Frame in) {
        bizExecutor.execute(() -> handleMessage(ctx, in));
    }

    private void handleMessage(ChannelHandlerContext ctx, Frame in) {
        Long fromUid = userIdOf(ctx);
        if (fromUid == null) {
            log.warn("连接上没有 userId，关闭连接");
            ctx.close();
            return;
        }

        if (!hasRequiredFields(in)) {
            log.warn("消息帧缺少必要字段 from={} frame={}", fromUid, in);
            return;
        }

        SendOutcome outcome = persist(ctx, fromUid, in);
        ackMessage(ctx, in.getCid(), outcome);
    }

    private boolean hasRequiredFields(Frame in) {
        return in.getTo() != null
                && in.getCid() != null && !in.getCid().isBlank()
                && in.getContent() != null && !in.getContent().isBlank();
    }

    /**
     * 调用 im 落库。
     *
     * <p>业务拒绝（如"请先添加好友"）和系统故障要区分开：
     * 前者把 im 的错误码原样带给客户端，后者统一 500。</p>
     */
    private SendOutcome persist(ChannelHandlerContext ctx, long fromUid, Frame in) {
        try {
            SendResultDTO result = imClient.send(fromUid, in.getTo(), in.getCid(), in.getContent());
            log.info("消息已落库 msgId={} conversation={} seq={} from={} to={}",
                    result.getMsgId(), result.getConversationId(), result.getSeq(), fromUid, in.getTo());
            return SendOutcome.success(result.getSeq());
        } catch (BizException e) {
            // 业务拒绝：不是故障，不需要堆栈
            log.warn("消息被拒绝 from={} to={} code={} reason={}",
                    fromUid, in.getTo(), e.getCode(), e.getMessage());
            return SendOutcome.failure(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("调用 im 失败 from={} to={} cid={}", fromUid, in.getTo(), in.getCid(), e);
            return SendOutcome.failure(500, "服务繁忙，请稍后重试");
        }
    }

    /**
     * 回执：成功带真实 seq，失败带 {@link #SEQ_FAILED} + code/reason。
     *
     * <p>失败也必须回，否则客户端那条消息会一直停在"发送中"。
     * 走 {@link Frame} 序列化，避免 cid 中的特殊字符破坏 JSON。</p>
     */
    private void ackMessage(ChannelHandlerContext ctx, String cid, SendOutcome outcome) {
        if (outcome.seq > 0) {
            sendFrame(ctx, Frame.ack(cid, outcome.seq));
        } else {
            sendFrame(ctx, Frame.nack(cid, outcome.code == null ? 500 : outcome.code, outcome.reason));
        }
    }

    /** 落库结果：成功带 seq，失败带错误码与原因 */
    private record SendOutcome(long seq, Integer code, String reason) {

        static SendOutcome success(long seq) {
            return new SendOutcome(seq, null, null);
        }

        static SendOutcome failure(int code, String reason) {
            return new SendOutcome(SEQ_FAILED, code, reason);
        }
    }

    // ==================== 出站工具 ====================

    private void sendFrame(ChannelHandlerContext ctx, Frame frame) {
        if (!ctx.channel().isActive()) {
            return;
        }
        try {//把 JSON 字符串包成 WebSocket 帧对象。
            ctx.writeAndFlush(new TextWebSocketFrame(MAPPER.writeValueAsString(frame)));
        } catch (Exception e) {
            log.warn("写回帧失败: {}", e.getMessage());
        }
    }

    /** 带关闭码关连接：先发 Close 帧说明原因，再关闭 */
    private void closeWithReason(ChannelHandlerContext ctx, CloseReason reason, int code, String text) {
        ctx.channel().attr(ChannelAttrs.CLOSE_REASON).set(reason);
        ctx.writeAndFlush(new CloseWebSocketFrame(code, text))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private Long userIdOf(ChannelHandlerContext ctx) {
        return ctx.channel().attr(ChannelAttrs.USER_ID).get();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("业务处理异常 userId={} msg={}", userIdOf(ctx), cause.getMessage());
        ctx.close();
    }
}
