package com.song.im.support;

import com.song.api.user.dto.UserDTO;
import com.song.api.user.service.UserFacade;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过 Dubbo 反查用户昵称（会话列表展示用）。
 *
 * <p>为什么不直接 JOIN {@code sys_user}：那张表属于 user 服务，
 * 跨服务读别人的表会把两个服务绑死（改表结构要同时改两边）。
 * 这里走 Dubbo 契约，user 服务换库/换实现都不影响。</p>
 *
 * <p>失败一律降级为 null：昵称只是展示用的，user 服务挂了也不该让会话列表打不开。</p>
 */
@Component
public class PeerNameResolver {

    private static final Logger log = LoggerFactory.getLogger(PeerNameResolver.class);

    @DubboReference(version = "1.0.0", group = "user", check = false, timeout = 1000, retries = 0)
    private UserFacade userFacade;

    public String nicknameOf(long uid) {
        try {
            UserDTO user = userFacade.getById(uid);
            if (user == null) {
                return null;
            }
            return user.getNickname() != null ? user.getNickname() : user.getAccount();
        } catch (Exception e) {
            log.warn("查询昵称失败 uid={} msg={}", uid, e.getMessage());
            return null;
        }
    }

    /**
     * 批量查昵称（一次 RPC + 一条 SQL）。
     *
     * <p>会话列表 / 好友列表必须用这个：逐个 {@link #nicknameOf} 是 N+1 次 RPC。</p>
     */
    public Map<Long, String> nicknamesOf(Collection<Long> uids) {
        if (uids == null || uids.isEmpty()) {
            return Map.of();
        }
        try {
            List<UserDTO> users = userFacade.getByIds(new ArrayList<>(uids));
            Map<Long, String> result = new HashMap<>(users.size());
            for (UserDTO user : users) {
                String name = user.getNickname() != null ? user.getNickname() : user.getAccount();
                result.put(user.getId(), name);
            }
            return result;
        } catch (Exception e) {
            log.warn("批量查询昵称失败 size={} msg={}", uids.size(), e.getMessage());
            return Map.of();
        }
    }
}
