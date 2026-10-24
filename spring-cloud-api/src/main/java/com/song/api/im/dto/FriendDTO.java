package com.song.api.im.dto;

import java.io.Serializable;

/**
 * 好友列表项。
 *
 * <p>昵称由 im 反查 user 服务填充；查不到时为 null，前端退回显示 uid。</p>
 */
public class FriendDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long uid;

    private String nickname;

    private String remark;

    /** 是否在线（来自 ws-gateway 维护的在线表） */
    private boolean online;

    public FriendDTO() {
    }

    public FriendDTO(Long uid, String nickname, String remark) {
        this(uid, nickname, remark, false);
    }

    public FriendDTO(Long uid, String nickname, String remark, boolean online) {
        this.uid = uid;
        this.nickname = nickname;
        this.remark = remark;
        this.online = online;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
