package com.song.user.dubbo;

import com.song.api.user.dto.UserDTO;
import com.song.api.user.service.UserFacade;
import com.song.user.repository.UserRepository;
import org.apache.dubbo.config.annotation.DubboService;

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

}
