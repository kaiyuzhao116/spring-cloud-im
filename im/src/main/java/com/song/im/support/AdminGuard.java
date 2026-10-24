package com.song.im.support;

import com.song.api.user.dto.UserDTO;
import com.song.api.user.service.UserFacade;
import com.song.common.BizException;
import com.song.common.ResultCode;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 管理员校验。
 *
 * <p>身份来自网关注入的 {@code X-User-Id}（已验签），这里再查一次 role——
 * 不用改 JWT 结构，也避免把权限判断散落到各处。</p>
 */
@Component
public class AdminGuard {

    private static final Logger log = LoggerFactory.getLogger(AdminGuard.class);

    @DubboReference(version = "1.0.0", group = "user", check = false, timeout = 1000, retries = 0)
    private UserFacade userFacade;

    public void requireAdmin(long uid) {
        try {
            UserDTO user = userFacade.getById(uid);
            if (user != null && "admin".equalsIgnoreCase(user.getRole())) {
                return;
            }
        } catch (Exception e) {
            log.warn("管理员校验失败 uid={} msg={}", uid, e.getMessage());
        }
        throw new BizException(ResultCode.FORBIDDEN.getCode(), "需要管理员权限");
    }
}
