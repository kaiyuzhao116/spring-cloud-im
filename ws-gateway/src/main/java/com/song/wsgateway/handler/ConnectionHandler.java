package com.song.wsgateway.handler;

import com.song.wsgateway.session.ChannelAttrs;
import com.song.wsgateway.session.ChannelManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 连接生命周期：注册/移除本机连接表、处理空闲超时、打印收到的帧。
 *
 * <p>P0 阶段不回复任何帧（业务处理放在 BusinessHandler），
 * 这里只负责"连接可观测"。</p>
 */
public class ConnectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    private final ChannelManager channelManager;

    private String connId;

    public ConnectionHandler(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete complete) {
            connId = UUID.randomUUID().toString().replace("-", "");
            ctx.channel().attr(ChannelAttrs.CONN_ID).set(connId);
            channelManager.register(connId, ctx.channel());
            String uri = complete.requestUri();
            int idx = uri.indexOf('?');
            // 只打路径：query 里可能带 token，落日志等于泄露凭证
            log.info("握手完成 connId={} userId={} path={}",
                    connId, ctx.channel().attr(ChannelAttrs.USER_ID).get(),
                    idx < 0 ? uri : uri.substring(0, idx));
            return;
        }
        if (evt instanceof IdleStateEvent) {
            log.info("空闲超时，主动断开 connId={} userId={}",
                    connId, ctx.channel().attr(ChannelAttrs.USER_ID).get());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (connId != null) {
            channelManager.unregister(connId);
        }
        log.info("连接关闭 connId={} userId={}", connId, ctx.channel().attr(ChannelAttrs.USER_ID).get());
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof TextWebSocketFrame frame) {
            try {
                log.info("收到帧 connId={} userId={} text={}",
                        connId, ctx.channel().attr(ChannelAttrs.USER_ID).get(), frame.text());
            } finally {
                ReferenceCountUtil.release(frame);
            }
            return;
        }
        ReferenceCountUtil.release(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("连接异常 connId={} msg={}", connId, cause.getMessage());
        ctx.close();
    }
}
