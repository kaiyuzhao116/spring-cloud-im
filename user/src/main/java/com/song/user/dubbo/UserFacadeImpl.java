package com.song.user.dubbo;

import com.song.api.user.dto.CreateUserCmd;
import com.song.api.user.dto.UserDTO;
import com.song.api.user.service.UserFacade;
import com.song.common.BizException;
import com.song.common.ResultCode;
import com.song.user.repository.UserRepository;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

/**
 * 用户服务 Dubbo 实现：供 auth 等其他服务调用。
 */
@DubboService(version = "1.0.0", group = "user")
public class UserFacadeImpl implements UserFacade {

    private final UserRepository userRepository;

    public UserFacadeImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDTO getById(Long userId) {
        return userRepository.findById(userId).orElse(null);
    }

    @Override
    public UserDTO getByAccount(String account) {
        return userRepository.findByAccount(account).orElse(null);
    }

    @Override
    public UserDTO create(CreateUserCmd cmd) {
        if (cmd == null || cmd.getAccount() == null || cmd.getAccount().isBlank()) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "账号不能为空");
        }
        if (cmd.getPasswordHash() == null || cmd.getPasswordHash().isBlank()) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "密码不能为空");
        }

        try {
            String nickname = (cmd.getNickname() == null || cmd.getNickname().isBlank())
                    ? cmd.getAccount() : cmd.getNickname();
            return userRepository.insert(cmd.getAccount(), cmd.getPasswordHash(), nickname);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：并发注册时也能拦住
            throw new BizException(409, "账号已存在");
        }
    }

    @Override
    public List<UserDTO> getByIds(List<Long> userIds) {
        return userRepository.findByIds(userIds);
    }

    @Override
    public void updatePassword(Long userId, String passwordHash) {
        if (userId == null || userId <= 0) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "用户不存在");
        }
        if (userRepository.updatePasswordHash(userId, passwordHash) == 0) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
    }

}
