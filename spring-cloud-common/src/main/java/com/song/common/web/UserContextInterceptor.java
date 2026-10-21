package com.song.common.web;

import com.song.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把网关注入的身份头还原到 {@link UserContext}。
 */
public class UserContextInterceptor implements HandlerInterceptor {

    public static final String HEADER_USER_ID = "X-User-Id";

    public static final String HEADER_SESSION_ID = "X-Session-Id";

    public static final String HEADER_DEVICE_TYPE = "X-Device-Type";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(HEADER_USER_ID);
        if (userId != null && !userId.isBlank()) {
            UserContext.set(Long.valueOf(userId), request.getHeader(HEADER_SESSION_ID),
                    request.getHeader(HEADER_DEVICE_TYPE));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
