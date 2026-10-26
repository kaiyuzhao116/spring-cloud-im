package com.song.wsgateway.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.wsgateway.protocol.Frame;
import com.song.wsgateway.mq.KickPublisher;
import com.song.wsgateway.session.ChannelAttrs;
import com.song.wsgateway.session.ChannelManager;
import com.song.wsgateway.session.CloseReason;
import com.song.wsgateway.session.OnlineRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.UUID;

/**
 * 连接生命周期工位：登记、注销、空闲踢人、统计存活时长。
 *
 * <p>它站在 pipeline 最后，只做"连接可观测"，不处理业务帧（业务在 BusinessHandler）。</p>
 *
 * <p>本类接收三类通知：</p>
 * <ul>
 *   <li>{@code HandshakeComplete}：握手成功 → 生成 connId、登记连接</li>
 *   <li>{@code IdleStateEvent}：60 秒无数据 → 主动断开（服务端唯一的主动断源头）</li>
 *   <li>{@code channelInactive}：连接已关闭 → 注销连接、统计存活时长（所有关闭路径的统一出口）</li>
 * </ul>
 */
public class ConnectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    /** 独立一份即可，ObjectMapper 本身线程安全 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChannelManager channelManager;

    private final OnlineRegistry onlineRegistry;

    private final KickPublisher kickPublisher;

    /**
     * 本条连接的编号。
     *
     * <p>可以用成员变量存，是因为 Netty 每条连接都会 new 一个本类实例（非 @Sharable），
     * 各连各的、互不干扰。握手失败时为 null。</p>
     */
    private String connId;

    private Long userIdOf(ChannelHandlerContext ctx) {
        return ctx.channel().attr(ChannelAttrs.USER_ID).get();
    }

    public ConnectionHandler(ChannelManager channelManager,
                             OnlineRegistry onlineRegistry,
                             KickPublisher kickPublisher) {
        this.channelManager = channelManager;
        this.onlineRegistry = onlineRegistry;
        this.kickPublisher = kickPublisher;
    }
//userEventTriggered 是 Netty 用来通知"某个非 IO 事件发生了"的统一入口，HandshakeComplete 
// 是 WebSocketServerProtocolHandler 在 101 完成后抛出的具体事件对象；我们用 instanceof 认出它
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // ① 握手成功：这是"连接真正可用"的时刻，登记身份与时间
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete complete) {
            connId = UUID.randomUUID().toString().replace("-", "");
            ctx.channel().attr(ChannelAttrs.CONN_ID).set(connId);

            // 存活时长的起点：必须在握手完成时记，而不是 channelActive（那会儿还没验签）
            ctx.channel().attr(ChannelAttrs.CONNECT_TIME).set(System.currentTimeMillis());

            channelManager.register(connId, ctx.channel());

            // ① 写入全局在线表（含 TTL，靠 PING 续期）
            Long uid = userIdOf(ctx);
            if (uid != null) {
                onlineRegistry.register(uid, connId);

                // ② 单端登录：广播"踢掉这个账号除我之外的连接"
                //    本机与远端实例都会收到，各自查本机连接表
                // Long connectTime = ctx.channel().attr(ChannelAttrs.CONNECT_TIME).get();
                // kickPublisher.publish(uid, connId, connectTime == null ? System.currentTimeMillis() : connectTime);

                // 多端登录：同一账号允许多条连接共存，登录时不再互踢。
                // 管理员强制下线仍走 AdminKickPublisher（ws.kick），不受影响。
            }

            // ③ 推一条系统消息，让用户看到"通道已通"
            sendWelcome(ctx);

            Long tokenExp = ctx.channel().attr(ChannelAttrs.TOKEN_EXP).get();
            log.info("token 到期时间 connId={} exp={}", connId,
                    tokenExp == null ? "无" : new Date(tokenExp));

            String uri = complete.requestUri();
            int idx = uri.indexOf('?');
            // 只打路径：query 里可能带 token，落日志等于泄露凭证
            log.info("握手完成 connId={} userId={} path={}",
                    connId, ctx.channel().attr(ChannelAttrs.USER_ID).get(),
                    idx < 0 ? uri : uri.substring(0, idx));
            return;
        }

        // ② 空闲超时：IdleStateHandler 只会"报警"，真正关连接要我们自己动手
        if (evt instanceof IdleStateEvent) {
            ctx.channel().attr(ChannelAttrs.CLOSE_REASON).set(CloseReason.IDLE_TIMEOUT);
            log.info("空闲超时，主动断开 connId={} userId={}",
                    connId, ctx.channel().attr(ChannelAttrs.USER_ID).get());
            ctx.close();
            return;
        }

        // ③ 其他事件（如 Ping/Pong 相关）交回父类，保持默认转发行为
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 所有关闭路径（客户端断开 / 空闲超时 / 被踢 / 异常）最终都会汇合到这里，
        // 因此"清理"只写这一处即可
        Long uid = userIdOf(ctx);
        if (connId != null) {
            channelManager.unregister(connId);
            if (uid != null) {
                // 只删自己这个 field，不能 DEL 整个 key（会误删同账号的其它连接）
                onlineRegistry.remove(uid, connId);
            }
        }

        Long connectTime = ctx.channel().attr(ChannelAttrs.CONNECT_TIME).get();
        CloseReason reason = ctx.channel().attr(ChannelAttrs.CLOSE_REASON).get();
        log.info("连接关闭 connId={} userId={} 存活={} 原因={}",
        connId,
        ctx.channel().attr(ChannelAttrs.USER_ID).get(),
        connectTime == null ? "未完成握手" : formatDuration(System.currentTimeMillis() - connectTime),
        (reason == null ? CloseReason.CLIENT_CLOSED : reason).getCode());

        // 必须继续往后传，否则下游 handler 收不到"连接已关闭"
        super.channelInactive(ctx);
    }

    /**
     * 连接建立后主动推一条系统消息。
     *
     * <p>字段约定：{@code from=0} 表示系统（user_id 从 1 开始），
     * {@code seq=0} 表示它不属于任何会话（不需要排序、不落库）。</p>
     */
    private void sendWelcome(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) {
            return;
        }
        Frame welcome = Frame.down(0L, 0L, "欢迎回来，连接已建立");
        try {
            ctx.writeAndFlush(new TextWebSocketFrame(MAPPER.writeValueAsString(welcome)));
        } catch (Exception e) {
            // 欢迎消息不是关键路径，发不出去也不该影响连接
            log.warn("欢迎消息发送失败: {}", e.getMessage());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.channel().attr(ChannelAttrs.CLOSE_REASON).set(CloseReason.ERROR);
        log.warn("连接异常 connId={} msg={}", connId, cause.getMessage());
        ctx.close();
    }

    /**
     * 把毫秒格式化成便于阅读的形式：不足 1 分钟用 {@code 32s}，否则用 {@code 1m30s}。
     *
     * <p>不用 {@code Duration.toString()}，因为它输出 {@code PT32S}，看日志时不好读。</p>
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }
}
