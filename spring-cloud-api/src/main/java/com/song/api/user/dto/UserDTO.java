package com.song.api.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户信息，服务间传输对象（对内使用，含密码哈希，禁止直接返回给前端）。
 */
@Data
public class UserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String account;

    /**
     * BCrypt 密码哈希，仅 auth 服务登录校验时使用。
     */
    private String passwordHash;

    private String nickname;

    private String avatar;

    /**
     * 1 正常，0 禁用。
     */
    private Integer status;

    private Long createdAt;

}
