package com.song.auth.controller;

import com.song.auth.domain.LoginRequest;
import com.song.auth.domain.LoginResponse;
import com.song.auth.domain.RegisterRequest;
import com.song.auth.service.AuthService;
import com.song.common.BizException;
import com.song.common.Result;
import com.song.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
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
     * 滑动续签：用旧票换新票。
     *
     * <p>此路径在网关白名单里（不走验签），因为旧票可能"刚刚过期"；
     * 真正的校验在 auth 内部做（签名、宽限期、作废状态、账号状态）。</p>
     */
    @PostMapping("/renew")
    public Result<LoginResponse> renew(HttpServletRequest httpRequest) {
        return Result.ok(authService.renew(httpRequest.getHeader("Authorization")));
    }

    /**
     * 管理员重置他人密码。
     *
     * <p>经网关：{@code POST /api/auth/admin/users/{id}/password}</p>
     */
    @PostMapping("/admin/users/{id}/password")
    public Result<Void> resetPassword(@PathVariable("id") Long id,
                                      @RequestBody ResetPasswordRequest request,
                                      jakarta.servlet.http.HttpServletRequest httpRequest) {
        Long operatorUid = resolveUserId(httpRequest.getHeader("X-User-Id"));
        if (operatorUid == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        authService.resetPassword(operatorUid, id, request.password());
        return Result.ok(null);
    }

    private Long resolveUserId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record ResetPasswordRequest(String password) {
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
