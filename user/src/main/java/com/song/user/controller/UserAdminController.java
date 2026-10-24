package com.song.user.controller;

import com.song.api.user.dto.UserDTO;
import com.song.common.BizException;
import com.song.common.Result;
import com.song.common.ResultCode;
import com.song.common.UserContext;
import com.song.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台用户管理（仅管理员）。
 *
 * <p>经网关访问：{@code /api/admin/users/**}</p>
 *
 * <p>权限校验放在本服务内：网关已经验签并注入可信的 {@code X-User-Id}，
 * 这里只需查这个 id 的 role 是否为 admin，不必再改 JWT 结构。</p>
 */
@RestController
public class UserAdminController {

    private static final Logger log = LoggerFactory.getLogger(UserAdminController.class);

    private static final int MAX_LIST = 200;

    private final UserRepository userRepository;

    public UserAdminController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 用户列表（可按账号/昵称模糊搜索） */
    @GetMapping("/admin/users")
    public Result<List<AdminUserVO>> list(@RequestParam(value = "keyword", required = false) String keyword,
                                          @RequestParam(value = "limit", defaultValue = "100") int limit) {
        requireAdmin();

        int size = Math.min(Math.max(limit, 1), MAX_LIST);
        List<AdminUserVO> users = userRepository.listForAdmin(keyword, size).stream()
                .map(u -> new AdminUserVO(u.getId(), u.getAccount(), u.getNickname(), u.getRole(),
                        u.getStatus(), u.getCreatedAt()))
                .toList();
        return Result.ok(users);
    }

    /**
     * 启用 / 禁用。
     *
     * <p>后台的"删除用户"用禁用实现：禁用的用户无法登录，但历史消息里的 uid 仍然有效。</p>
     */
    @PostMapping("/admin/users/{id}/status")
    public Result<Void> updateStatus(@PathVariable("id") Long id, @RequestBody StatusCommand command) {
        UserDTO admin = requireAdmin();
        if (admin.getId().equals(id)) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "不能禁用自己");
        }
        int status = command.status() == null ? 1 : command.status();
        if (userRepository.updateStatus(id, status) == 0) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        log.info("管理员变更用户状态 adminUid={} targetUid={} status={}", admin.getId(), id, status);
        return Result.ok(null);
    }

    private UserDTO requireAdmin() {
        Long uid = UserContext.getUserId();
        if (uid == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        UserDTO me = userRepository.findById(uid)
                .orElseThrow(() -> new BizException(ResultCode.UNAUTHORIZED.getCode(), "用户不存在"));
        if (!"admin".equalsIgnoreCase(me.getRole())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "需要管理员权限");
        }
        return me;
    }

    public record StatusCommand(Integer status) {
    }

    public record AdminUserVO(Long id, String account, String nickname, String role,
                              Integer status, Long createdAt) {
    }
}
