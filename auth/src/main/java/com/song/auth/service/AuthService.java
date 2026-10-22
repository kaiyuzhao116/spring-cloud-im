package com.song.auth.service;

import com.song.api.user.dto.UserDTO;
import com.song.api.user.service.UserFacade;
import com.song.auth.config.JwtProperties;
import com.song.auth.domain.LoginRequest;
import com.song.auth.domain.LoginResponse;
import com.song.auth.manager.JwtManager;
import com.song.auth.manager.SessionManager;
import com.song.common.BizException;
import com.song.common.ResultCode;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录编排：失败计数 → 查用户 → 校验密码 → 建会话 → 签 token。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final SessionManager sessionManager;

    private final JwtManager jwtManager;

    private final JwtProperties jwtProperties;

    @DubboReference(version = "1.0.0", group = "user")
    private UserFacade userFacade;

    public AuthService(SessionManager sessionManager, JwtManager jwtManager, JwtProperties jwtProperties) {
        this.sessionManager = sessionManager;
        this.jwtManager = jwtManager;
        this.jwtProperties = jwtProperties;
    }

    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        String account = request.getAccount().trim();
        String deviceType = request.getDeviceType() == null ? "APP" : request.getDeviceType().toUpperCase();

        // 1. 失败次数过多直接拒绝，防撞库
        if (sessionManager.getFailCount(account) >= jwtProperties.getMaxFailCount()) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS.getCode(), "失败次数过多，请 15 分钟后再试");
        }

        // 2. Dubbo 查用户
        UserDTO user = userFacade.getByAccount(account);
        if (user == null) {
            sessionManager.increaseFailCount(account, jwtProperties.getFailWindowSeconds());
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "账号或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "账号已被禁用");
        }

        // 3. 校验密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            long failCount = sessionManager.increaseFailCount(account, jwtProperties.getFailWindowSeconds());
            log.warn("登录失败 account={}, 累计失败次数={}", account, failCount);
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "账号或密码错误");
        }
        sessionManager.clearFailCount(account);

        // 4. 签发 token（精简版：payload 只有 sub / iat / exp）
        String accessToken = jwtManager.sign(user.getId());

        log.info("登录成功 userId={}, account={}, deviceType={}", user.getId(), account, deviceType);
        return new LoginResponse(user.getId(), user.getNickname(), accessToken,
                jwtManager.getExpireSeconds(), deviceType);
    }

}
