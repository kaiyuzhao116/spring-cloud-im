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
import java.util.concurrent.ThreadPoolExecutor;

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

    /** 令牌桶：每秒补充的令牌数（长期平均速率） */
    private static final double TOKEN_RATE_PER_SECOND = 10.0;

    /** 令牌桶：桶容量（允许的瞬时突发条数） */
    private static final double TOKEN_BURST = 20.0;

    /** 队列水位：超过这个深度就直接拒绝，不再往线程池塞 */
    private static final int QUEUE_WATERMARK = 4000;

    /** 限流/过载拒绝时，最多每秒回一次，避免刷屏 */
    private static final long REJECT_REPLY_INTERVAL_MS = 1000L;

    /** 当前可用令牌；只在该连接的 EventLoop 线程访问，无需加锁 */
    private double tokens = TOKEN_BURST;

    private long lastRefillAt = System.currentTimeMillis();

    private long lastRejectReplyAt = 0;

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
            case RECALL -> handleRecallAsync(ctx, in);              // 同样是 Dubbo 调用，也要切线程池
            default -> log.warn("不支持的上行帧 type={}", in.getType());
        }
    }

    // ==================== 发消息 ====================

    /**
     * 交给业务线程池执行，避免阻塞 EventLoop。
     *
     * <p>进线程池前过两道闸：</p>
     * <ol>
     *   <li><b>队列水位</b>：整体过载时直接拒绝（防"大家一起刷"）</li>
     *   <li><b>令牌桶</b>：本连接发太快时拒绝（防"一个人刷"）</li>
     * </ol>
     * <p>两者都只回 {@code ACK seq=0 + code}，<b>不断开连接</b>——用户只是发猛了，不是攻击。</p>
     */
    private void handleMessageAsync(ChannelHandlerContext ctx, Frame in) {
        int queueDepth = queueDepth();
        if (queueDepth > QUEUE_WATERMARK) {
            rejectWithCode(ctx, in, 503, "服务繁忙，请稍后再试",
                    "队列水位过高 userId=" + userIdOf(ctx) + " 队列=" + queueDepth);
            return;
        }

        if (!tryAcquireToken()) {
            rejectWithCode(ctx, in, 429, "发送太快，请稍后再试",
                    "发送过快 userId=" + userIdOf(ctx));
            return;
        }

        bizExecutor.execute(() -> handleMessage(ctx, in));
    }

    /**
     * 取一个令牌（令牌桶算法）。
     *
     * <p>按 {@link #TOKEN_RATE_PER_SECOND} 补充，最多补到 {@link #TOKEN_BURST}：
     * 长期平均被锁在 10 条/秒，同时放过"复制粘贴连发 20 条"这种人类突发。</p>
     *
     * @return true 表示放行
     */
    private boolean tryAcquireToken() {
        long now = System.currentTimeMillis();
        double refill = (now - lastRefillAt) / 1000.0 * TOKEN_RATE_PER_SECOND;
        tokens = Math.min(TOKEN_BURST, tokens + refill);
        lastRefillAt = now;

        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private int queueDepth() {
        if (bizExecutor instanceof ThreadPoolExecutor pool) {
            return pool.getQueue().size();
        }
        return 0;
    }

    /**
     * 拒绝一条消息。
     *
     * <p><b>每条都要回 ACK</b>：不回的话客户端那条消息会永远停在"发送中"。
     * 帧很小（≈70 字节），即使被刷也扛得住；真正要限制的是<b>日志</b>——
     * 同一连接每秒只打一条，避免被刷时日志文件被打爆。</p>
     */
    private void rejectWithCode(ChannelHandlerContext ctx, Frame in, int code, String reason, String logMessage) {
        sendFrame(ctx, Frame.nack(in.getCid(), code, reason));

        long now = System.currentTimeMillis();
        if (now - lastRejectReplyAt >= REJECT_REPLY_INTERVAL_MS) {
            lastRejectReplyAt = now;
            log.warn("{} code={}", logMessage, code);
        }
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

    /**
     * 撤回消息。
     *
     * <p>和发消息共用令牌桶与线程池：撤回同样是一次 Dubbo 阻塞调用，
     * 也不能待在 EventLoop 里。</p>
     */
    private void handleRecallAsync(ChannelHandlerContext ctx, Frame in) {
        Long fromUid = userIdOf(ctx);
        if (fromUid == null) {
            log.warn("连接上没有 userId，关闭连接");
            ctx.close();
            return;
        }
        if (in.getTo() == null || in.getSeq() == null || in.getCid() == null || in.getCid().isBlank()) {
            log.warn("撤回帧缺少必要字段 from={} frame={}", fromUid, in);
            return;
        }

        if (!tryAcquireToken()) {
            rejectWithCode(ctx, in, 429, "操作太快，请稍后再试",
                    "撤回限流 userId=" + fromUid);
            return;
        }

        bizExecutor.execute(() -> {
            try {
                imClient.recall(fromUid, in.getTo(), in.getSeq());
                sendFrame(ctx, Frame.ack(in.getCid(), in.getSeq()));
            } catch (BizException e) {
                log.warn("撤回被拒绝 from={} seq={} code={} reason={}",
                        fromUid, in.getSeq(), e.getCode(), e.getMessage());
                sendFrame(ctx, Frame.nack(in.getCid(), e.getCode(), e.getMessage()));
            } catch (Exception e) {
                log.error("撤回失败 from={} seq={}", fromUid, in.getSeq(), e);
                sendFrame(ctx, Frame.nack(in.getCid(), 500, "服务繁忙，请稍后重试"));
            }
        });
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
