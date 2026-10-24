package com.song.wsgateway.handler;

import com.song.wsgateway.session.ChannelAttrs;
import com.song.wsgateway.support.JwtVerifier;
import com.song.wsgateway.support.KickedChecker;
import com.song.wsgateway.support.TokenExtractor;
import io.jsonwebtoken.Claims;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 握手验签：WebSocket 升级之前拦一道 HTTP 请求。
 *
 * <p>通过后把 userId / tokenExp 写入 Channel，并放行给
 * {@code WebSocketServerProtocolHandler} 完成 101 升级；
 * 不通过直接回 401 并关闭连接。</p>
 *
 * <p>注意：升级之后的 WebSocket 帧也会流经本 handler，必须原样往后传。</p>
 */
public class AuthHandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeHandler.class);

    private static final String WS_PATH = "/ws";

    private final JwtVerifier jwtVerifier;

    private final KickedChecker kickedChecker;

    public AuthHandshakeHandler(JwtVerifier jwtVerifier, KickedChecker kickedChecker) {
        this.jwtVerifier = jwtVerifier;
        this.kickedChecker = kickedChecker;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            // 已升级为 WebSocket，后续帧直接往后传
            ctx.fireChannelRead(msg);
            return;
        }

        if (!request.uri().startsWith(WS_PATH)) {
            reject(ctx, request, HttpResponseStatus.NOT_FOUND, 404, "路径不存在，仅支持 " + WS_PATH);
            return;
        }

        String token = TokenExtractor.from(request);
        if (token == null) {
            reject(ctx, request, HttpResponseStatus.UNAUTHORIZED, 401, "缺少 token");
            return;
        }

        Claims claims;
        try {
            claims = jwtVerifier.parse(token);
        } catch (Exception e) {
            log.debug("握手验签失败: {}", e.getMessage());
            reject(ctx, request, HttpResponseStatus.UNAUTHORIZED, 401, "token 无效或已过期");
            return;
        }

        Long userId = Long.valueOf(claims.getSubject());

        // 管理员踢人会作废"签发时间早于踢出时刻"的 token；
        // 这一步必须有，否则用户刷新页面（直连 8089，不经过网关）就能复活
        if (kickedChecker.isRevoked(userId, claims.getIssuedAt())) {
            log.info("握手拒绝：token 已作废 userId={}", userId);
            reject(ctx, request, HttpResponseStatus.UNAUTHORIZED, 401, "登录状态已失效，请重新登录");
            return;
        }

        ctx.channel().attr(ChannelAttrs.USER_ID).set(userId);
        ctx.channel().attr(ChannelAttrs.TOKEN_EXP).set(claims.getExpiration().getTime());
        // 日志只打路径，绝不打 uri（query 里可能带 token，落日志等于泄露凭证）
        log.info("握手验签通过 userId={} path={}", userId, pathOf(request.uri()));

        // 放行给 WebSocketServerProtocolHandler（由它负责 release 该请求）
        ctx.fireChannelRead(request);
    }

    private void reject(ChannelHandlerContext ctx, FullHttpRequest request,
                        HttpResponseStatus status, int code, String message) {
        String body = "{\"code\":" + code + ",\"message\":\"" + message + "\"}";
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

        log.info("握手拒绝 status={} reason={} path={}", status.code(), message, pathOf(request.uri()));
        ReferenceCountUtil.release(request);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /** 去掉 query，避免 token 落进日志 */
    private String pathOf(String uri) {
        int idx = uri.indexOf('?');
        return idx < 0 ? uri : uri.substring(0, idx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("握手阶段异常: {}", cause.getMessage());
        ctx.close();
    }
}
