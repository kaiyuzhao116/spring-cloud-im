package com.song.api.user.service;

import com.song.api.user.dto.UserDTO;

/**
 * 用户服务对外契约（Dubbo）。
 * <p>实现方：user 服务的 {@code UserFacadeImpl}；调用方：auth 等。</p>
 */
public interface UserFacade {

    UserDTO getById(Long userId);

    UserDTO getByAccount(String account);

}
