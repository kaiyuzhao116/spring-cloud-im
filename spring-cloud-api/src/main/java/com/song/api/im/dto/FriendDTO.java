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

    public FriendDTO() {
    }

    public FriendDTO(Long uid, String nickname, String remark) {
        this.uid = uid;
        this.nickname = nickname;
        this.remark = remark;
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
