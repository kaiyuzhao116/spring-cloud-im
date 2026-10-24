package com.song.api.user.dto;

import java.io.Serializable;

/**
 * 用户公开资料（搜索/看他人时使用）。
 *
 * <p>**绝不能直接用 {@link UserDTO}**：它带 {@code passwordHash}，
 * 一旦某次误返回就是事故。公开资料只暴露 id / account / nickname / avatar。</p>
 */
public class UserPublicDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 账号：加好友时需要让对方确认"就是这个人" */
    private String account;

    private String nickname;

    private String avatar;

    public UserPublicDTO() {
    }

    public UserPublicDTO(Long id, String account, String nickname, String avatar) {
        this.id = id;
        this.account = account;
        this.nickname = nickname;
        this.avatar = avatar;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
}
