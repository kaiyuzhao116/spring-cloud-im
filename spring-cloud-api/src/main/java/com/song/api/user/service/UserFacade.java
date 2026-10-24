package com.song.api.user.service;

import com.song.api.user.dto.CreateUserCmd;
import com.song.api.user.dto.UserDTO;

/**
 * 用户服务对外契约（Dubbo）。
 * <p>实现方：user 服务的 {@code UserFacadeImpl}；调用方：auth 等。</p>
 */
public interface UserFacade {

    UserDTO getById(Long userId);

    UserDTO getByAccount(String account);

    /**
     * 创建用户（注册）。
     *
     * <p>密码由调用方（auth）哈希后传入，user 服务不接触明文。
     * 账号重复时抛 {@code BizException(code=409)}，由唯一索引兜底而非先查后插。</p>
     */
    UserDTO create(CreateUserCmd cmd);

    /**
     * 批量查询（好友列表、会话列表展示昵称用，避免 N+1 次 RPC）。
     */
    java.util.List<UserDTO> getByIds(java.util.List<Long> userIds);
}
