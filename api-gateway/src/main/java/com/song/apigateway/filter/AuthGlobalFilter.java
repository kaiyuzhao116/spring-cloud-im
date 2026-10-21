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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
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
 * 网关核心过滤器：白名单放行 → 验签 → 比对 token 版本号 → 注入身份头。
 * <p>只做"对账"，不查会话明细，不调 auth 服务。</p>
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private static final String HEADER_USER_ID = "X-User-Id";

    private static final String HEADER_SESSION_ID = "X-Session-Id";

    private static final String HEADER_DEVICE_TYPE = "X-Device-Type";

    private static final String VERSION_KEY_FORMAT = "auth:user:%s:ver";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final AuthProperties authProperties;

    private final JwtVerifier jwtVerifier;

    private final ReactiveStringRedisTemplate redisTemplate;

    public AuthGlobalFilter(AuthProperties authProperties, JwtVerifier jwtVerifier,
                            ReactiveStringRedisTemplate redisTemplate) {
        this.authProperties = authProperties;
        this.jwtVerifier = jwtVerifier;
        this.redisTemplate = redisTemplate;
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
        String sessionId = claims.get("sid") == null ? "" : claims.get("sid").toString();
        String deviceType = claims.get("dev") == null ? "" : claims.get("dev").toString();
        String tokenVersion = claims.get("ver") == null ? "0" : claims.get("ver").toString();

        // 版本号比对：token 上的 ver 必须等于 Redis 当前 ver，否则说明已被踢/被封号
        return redisTemplate.opsForValue()
                .get(String.format(VERSION_KEY_FORMAT, userId))
                .defaultIfEmpty("0")
                .flatMap(currentVersion -> {
                    if (!currentVersion.equals(tokenVersion)) {
                        return unauthorized(exchange, "凭证已失效，请重新登录");
                    }
                    ServerHttpRequest request = exchange.getRequest().mutate()
                            .headers(headers -> {
                                // 先剥离外部伪造的身份头，再写入可信值
                                headers.remove(HEADER_USER_ID);
                                headers.remove(HEADER_SESSION_ID);
                                headers.remove(HEADER_DEVICE_TYPE);
                                headers.set(HEADER_USER_ID, userId);
                                headers.set(HEADER_SESSION_ID, sessionId);
                                headers.set(HEADER_DEVICE_TYPE, deviceType);
                            })
                            .build();
                    return chain.filter(exchange.mutate().request(request).build());
                })
                .onErrorResume(e -> {
                    log.error("鉴权依赖异常", e);
                    return unauthorized(exchange, "鉴权服务暂不可用");
                });
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
