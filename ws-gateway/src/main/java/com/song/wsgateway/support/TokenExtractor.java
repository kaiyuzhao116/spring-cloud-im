package com.song.wsgateway.support;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.List;

/**
 * 从握手请求里提取 token。
 *
 * <p>按优先级依次尝试：</p>
 * <ol>
 *   <li>{@code Authorization: Bearer xxx}（App / 脚本，推荐）</li>
 *   <li>{@code ?token=xxx}（浏览器无法自定义 Header 时用）</li>
 *   <li>{@code Sec-WebSocket-Protocol}（备用，需服务端回显子协议）</li>
 * </ol>
 */
public final class TokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";

    private TokenExtractor() {
    }

    public static String from(FullHttpRequest request) {
        String authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }

        List<String> queryToken = new QueryStringDecoder(request.uri()).parameters().get("token");
        if (queryToken != null && !queryToken.isEmpty()) {
            String token = queryToken.get(0).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }

        String subProtocol = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (subProtocol != null && !subProtocol.isBlank()) {
            return subProtocol.split(",")[0].trim();
        }

        return null;
    }
}
