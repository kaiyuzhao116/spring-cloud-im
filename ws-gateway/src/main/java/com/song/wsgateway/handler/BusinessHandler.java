package com.song.wsgateway.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.api.im.dto.SendResultDTO;
import com.song.wsgateway.protocol.Frame;
import com.song.wsgateway.protocol.FrameType;
import com.song.wsgateway.session.ChannelAttrs;
import com.song.wsgateway.session.CloseReason;
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
 * 业务帧处理：
 * <pre>
 * PING  → 立即回 PONG（轻量，留在 EventLoop）
 * MSG   → 丢进业务线程池 → Dubbo 调 im 落库 → 回 ACK（cid + seq）
 * 其它  → 忽略并记日志
 * </pre>
 *
 * <p>铁律：Dubbo 是阻塞调用，只能在业务线程池里执行。</p>
 */
public class BusinessHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(BusinessHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单帧业务上限：4KB（协议层上限由 netty.max-frame-length 控制，这里是业务层） */
    private static final int MAX_TEXT_BYTES = 4096;

    private final ImClient imClient;

    private final ExecutorService bizExecutor;

    public BusinessHandler(ImClient imClient, ExecutorService bizExecutor) {
        this.imClient = imClient;
        this.bizExecutor = bizExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {

        // ★ 先看字节数，超了直接断，避免把大字符串解码成 String（那才是真正吃内存的一步）
        //frame.text()              // 先把字节解码成 String（这一步就吃内存）
        //frame.content().readableBytes()   // 直接看字节数，不解码
        int bytes = frame.content().readableBytes();
        if (bytes > MAX_TEXT_BYTES) {
            log.warn("帧过大，断开连接 userId={} bytes={}",
                    ctx.channel().attr(ChannelAttrs.USER_ID).get(), bytes);

            ctx.channel().attr(ChannelAttrs.CLOSE_REASON).set(CloseReason.MESSAGE_TOO_BIG);
            ctx.writeAndFlush(new CloseWebSocketFrame(1009, "message too big"))
                    .addListener(ChannelFutureListener.CLOSE);
            return;      // 不再往下走，不解析、不落库
        }

        Frame in;
        try {
            in = MAPPER.readValue(frame.text(), Frame.class);
        } catch (Exception e) {
            log.warn("帧解析失败 userId={} text={}",
                    ctx.channel().attr(ChannelAttrs.USER_ID).get(), frame.text());
            return;
        }

        FrameType type = in.frameType();
        if (type == null) {
            log.warn("未知帧类型，忽略 type={}", in.getType());
            return;
        }

        switch (type) {
            case PING -> send(ctx, Frame.pong());
            case MSG -> bizExecutor.execute(() -> handleMessage(ctx, in));
            default -> log.warn("不支持的上行帧 type={}", in.getType());
        }
    }

    private void handleMessage(ChannelHandlerContext ctx, Frame in) {
        Long fromUid = ctx.channel().attr(ChannelAttrs.USER_ID).get();
        if (fromUid == null) {
            log.warn("连接上没有 userId，关闭连接");
            ctx.close();
            return;
        }
        if (in.getTo() == null || in.getCid() == null || in.getContent() == null
                || in.getCid().isBlank() || in.getContent().isBlank()) {
            log.warn("消息帧缺少必要字段 from={} frame={}", fromUid, in);
            return;
        }

        try {
            SendResultDTO result = imClient.send(fromUid, in.getTo(), in.getCid(), in.getContent());
            log.info("消息已落库 msgId={} conversation={} seq={} from={} to={}",
                    result.getMsgId(), result.getConversationId(), result.getSeq(), fromUid, in.getTo());
            send(ctx, Frame.ack(in.getCid(), result.getSeq()));
        } catch (Exception e) {
            log.error("调用 im 失败 from={} to={} cid={}", fromUid, in.getTo(), in.getCid(), e);
            // 失败也要回 ACK（seq=0 表示没存成），否则客户端那条消息会一直停在"发送中"
            // 用 Frame + Jackson 序列化，避免 cid 里的引号破坏 JSON
            send(ctx, Frame.ack(in.getCid(), 0L));
        }
    }

    private void send(ChannelHandlerContext ctx, Frame frame) {
        if (!ctx.channel().isActive()) {
            return;
        }
        try {
            ctx.writeAndFlush(new TextWebSocketFrame(MAPPER.writeValueAsString(frame)));
        } catch (Exception e) {
            log.warn("写回帧失败: {}", e.getMessage());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("业务处理异常 userId={} msg={}",
                ctx.channel().attr(ChannelAttrs.USER_ID).get(), cause.getMessage());
        ctx.close();
    }
}
