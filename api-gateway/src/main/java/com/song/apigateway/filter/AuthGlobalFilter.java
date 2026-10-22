package com.song.apigateway.filter;

import com.song.apigateway.config.AuthProperties;
import com.song.apigateway.support.JwtVerifier;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关核心过滤器：白名单放行 → 验签 → 注入 X-User-Id。
 *
 * <p>精简版只做两件事：验签（sub/iat/exp 由 jjwt 自动校验）和注入用户 ID。
 * 不再校验版本号、不注入 sessionId / deviceType，也不访问 Redis。</p>
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private static final String HEADER_USER_ID = "X-User-Id";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final AuthProperties authProperties;

    private final JwtVerifier jwtVerifier;

    public AuthGlobalFilter(AuthProperties authProperties, JwtVerifier jwtVerifier) {
        this.authProperties = authProperties;
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isWhiteList(path)) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorized(exchange, "未登录或登录已失效");
        }

        Claims claims;
        try {
            claims = jwtVerifier.parse(authorization.substring(7));
        } catch (Exception e) {
            log.debug("token 校验失败: {}", e.getMessage());
            return unauthorized(exchange, "登录已失效，请重新登录");
        }

        String userId = claims.getSubject();
        if (userId == null || userId.isBlank()) {
            return unauthorized(exchange, "登录已失效，请重新登录");
        }

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    // 先剥离外部伪造的身份头，再写入可信值
                    headers.remove(HEADER_USER_ID);
                    headers.set(HEADER_USER_ID, userId);
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private boolean isWhiteList(String path) {
        return authProperties.getWhiteList().stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        String body = "{\"code\":401,\"message\":\"" + message + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
