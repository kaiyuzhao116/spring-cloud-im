package com.song.auth.controller;

import com.song.auth.domain.LoginRequest;
import com.song.auth.domain.LoginResponse;
import com.song.auth.domain.RegisterRequest;
import com.song.auth.service.AuthService;
import com.song.common.Result;
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

    /**
     * 注册。
     *
     * <p>注册成功直接返回 token（注册即登录），并自动与管理员建立好友关系。</p>
     */
    @PostMapping("/register")
    public Result<LoginResponse> register(@RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        return Result.ok(authService.register(request, clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
    }

    /**
     * 退出登录。
     * <p>精简版 token 无会话标识，服务端无法主动作废某个 token；
     * 正确做法是客户端删除本地 token。将来加了 sid / refreshToken 后再实现服务端失效。</p>
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
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
