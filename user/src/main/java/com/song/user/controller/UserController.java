package com.song.user.controller;

import com.song.api.user.dto.UserDTO;
import com.song.common.BizException;
import com.song.common.Result;
import com.song.common.ResultCode;
import com.song.common.UserContext;
import com.song.user.repository.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户对外 REST 接口（经 api-gateway 访问，路径前缀 /api/user 已被网关剥掉）。
 */
@RestController
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 当前登录用户资料。用户身份来自网关注入的 X-User-Id，不解析 token。
     */
    @GetMapping("/profile")
    public Result<UserProfileVO> profile() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        UserDTO user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND.getCode(), "用户不存在"));
        return Result.ok(new UserProfileVO(user.getId(), user.getAccount(), user.getNickname(),
                user.getAvatar(), user.getStatus(), user.getCreatedAt()));
    }

    /**
     * 对外视图，绝不包含 passwordHash。
     */
    public record UserProfileVO(Long id, String account, String nickname, String avatar,
                                Integer status, Long createdAt) {
    }

}
