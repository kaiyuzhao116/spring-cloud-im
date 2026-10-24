package com.song.auth.service;

import com.song.api.im.service.ImFriendFacade;
import com.song.api.user.dto.CreateUserCmd;
import com.song.api.user.dto.UserDTO;
import com.song.api.user.service.UserFacade;
import com.song.auth.config.JwtProperties;
import com.song.auth.domain.LoginRequest;
import com.song.auth.domain.LoginResponse;
import com.song.auth.domain.RegisterRequest;
import com.song.auth.manager.JwtManager;
import com.song.auth.manager.SessionManager;
import com.song.common.BizException;
import com.song.common.ResultCode;
import io.jsonwebtoken.Claims;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证编排：登录（失败计数 → 校验密码 → 签 token）、注册（校验 → 落库 → 加管理员好友 → 签 token）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 续签宽限：票过期后仍允许换新票的时间（用户挂了会儿回来） */
    private static final long RENEW_GRACE_MS = 10 * 60 * 1000L;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final SessionManager sessionManager;

    private final JwtManager jwtManager;

    private final JwtProperties jwtProperties;

    @DubboReference(version = "1.0.0", group = "user")
    private UserFacade userFacade;

    @DubboReference(version = "1.0.0", group = "im", check = false, timeout = 2000, retries = 0)
    private ImFriendFacade friendFacade;

    /** 每个新用户自动加这个账号为好友（默认 admin） */
    @Value("${app.admin-uid:1}")
    private long adminUid;

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

    /**
     * 注册：校验 → BCrypt → user 服务落库（唯一键兜底）→ 自动与管理员加好友 → 直接签发 token。
     *
     * <p>注册即登录：省掉一次输入，体验更好。</p>
     */
    public LoginResponse register(RegisterRequest request, String ip, String userAgent) {
        String account = normalizeAccount(request.getAccount());
        validatePassword(request.getPassword());
        String nickname = normalizeNickname(request.getNickname(), account);
        String deviceType = request.getDeviceType() == null ? "APP" : request.getDeviceType().toUpperCase();

        // 密码只在内存里存在一次；哈希后明文即弃，绝不落日志
        String passwordHash = passwordEncoder.encode(request.getPassword());

        UserDTO created = userFacade.create(new CreateUserCmd(account, passwordHash, nickname));
        log.info("注册成功 userId={}, account={}", created.getId(), account);

        // 新用户自动与管理员互加好友；失败只记日志——不能因为社交关系让注册失败
        try {
            boolean added = friendFacade.ensureFriend(created.getId(), adminUid);
            log.info("自动加管理员好友 userId={} adminUid={} 新建={}", created.getId(), adminUid, added);
        } catch (Exception e) {
            log.warn("自动加管理员好友失败 userId={} msg={}", created.getId(), e.getMessage());
        }

        String accessToken = jwtManager.sign(created.getId());
        return new LoginResponse(created.getId(), created.getNickname(), accessToken,
                jwtManager.getExpireSeconds(), deviceType);
    }

    /**
     * 滑动续签：拿一张（刚过期的）旧票换一张新票。
     *
     * <p>四条校验缺一不可：</p>
     * <ol>
     *   <li>签名必须正确（防止随便编一张票来换）</li>
     *   <li>过期时间不能超过宽限窗口（防止拿几个月前的旧票换新票）</li>
     *   <li>没有被管理员作废（否则被踢的人会借续签复活）</li>
     *   <li>账号未被禁用</li>
     * </ol>
     */
    public LoginResponse renew(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少凭证，请重新登录");
        }
        String token = authorization.substring(7).trim();

        Claims claims;
        try {
            claims = jwtManager.parseAllowExpired(token);
        } catch (Exception e) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "登录已失效，请重新登录");
        }

        long uid;
        try {
            uid = Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "登录已失效，请重新登录");
        }

        long issuedAt = claims.getIssuedAt() == null ? 0 : claims.getIssuedAt().getTime();
        long expireAt = claims.getExpiration() == null ? 0 : claims.getExpiration().getTime();
        long now = System.currentTimeMillis();

        if (expireAt + RENEW_GRACE_MS < now) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "登录已过期，请重新登录");
        }
        if (sessionManager.isTokenRevoked(uid, issuedAt)) {
            // 被管理员踢过：旧票一律不能换新票
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "登录状态已失效，请重新登录");
        }

        UserDTO user = userFacade.getById(uid);
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "用户不存在");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "账号已被禁用");
        }

        String fresh = jwtManager.sign(uid);
        log.debug("token 已续签 uid={}", uid);
        return new LoginResponse(uid, user.getNickname(), fresh,
                jwtManager.getExpireSeconds(), "APP");
    }

    /**
     * 管理员重置他人密码。
     *
     * <p>哈希由本服务生成（唯一持有密码策略的地方），user 服务只落库。</p>
     */
    public void resetPassword(long operatorUid, long targetUid, String newPassword) {
        UserDTO operator = userFacade.getById(operatorUid);
        if (operator == null || !"admin".equalsIgnoreCase(operator.getRole())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "需要管理员权限");
        }
        validatePassword(newPassword);

        userFacade.updatePassword(targetUid, passwordEncoder.encode(newPassword));
        log.info("管理员重置密码 operator={} target={}", operatorUid, targetUid);
    }

    /** 账号统一小写，避免 Admin / admin 变两个账号 */
    private String normalizeAccount(String raw) {
        String account = raw == null ? "" : raw.trim().toLowerCase();
        if (!account.matches("^[a-z0-9_]{4,20}$")) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "账号需 4~20 位，仅限字母、数字、下划线");
        }
        return account;
    }

    /** 8~20 位，必须同时包含字母和数字 */
    private void validatePassword(String password) {
        if (password == null || !password.matches("^(?=.*[A-Za-z])(?=.*\\d).{8,20}$")) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "密码需 8~20 位，且同时包含字母和数字");
        }
    }

    private String normalizeNickname(String raw, String account) {
        String nickname = raw == null ? "" : raw.trim();
        if (nickname.isEmpty()) {
            return account;
        }
        if (nickname.length() > 16) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "昵称最多 16 个字");
        }
        return nickname;
    }

}
