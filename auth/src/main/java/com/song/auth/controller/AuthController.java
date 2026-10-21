package com.song.auth.controller;

import com.song.auth.domain.LoginRequest;
import com.song.auth.domain.LoginResponse;
import com.song.auth.service.AuthService;
import com.song.common.Result;
import com.song.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 * <p>网关配置 StripPrefix=2，外部访问 /api/auth/login，到这里是 /login。</p>
 */
@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return Result.ok(authService.login(request, clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout(UserContext.getUserId(), UserContext.getSessionId());
        return Result.ok();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

}
