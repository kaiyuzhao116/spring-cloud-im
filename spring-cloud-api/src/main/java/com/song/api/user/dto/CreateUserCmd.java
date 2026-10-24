package com.song.api.user.dto;

import java.io.Serializable;

/**
 * 创建用户（注册）入参。
 *
 * <p>密码是**已哈希**的值：密码策略与哈希算法由 auth（认证中心）统一负责，
 * user 服务只负责落库，不接触明文。</p>
 */
public class CreateUserCmd implements Serializable {

    private static final long serialVersionUID = 1L;

    private String account;

    /** BCrypt 哈希值 */
    private String passwordHash;

    private String nickname;

    public CreateUserCmd() {
    }

    public CreateUserCmd(String account, String passwordHash, String nickname) {
        this.account = account;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
}
