package com.song.api.im.service;

import com.song.api.im.dto.FriendDTO;

import java.util.List;

/**
 * 好友关系契约（Dubbo）。
 *
 * <p>为什么放在 im 而不是 user：好友关系与"谁能给谁发消息"同属社交域，
 * 而消息校验在 im；放一起可以同库查询、同事务写入。</p>
 */
public interface ImFriendFacade {

    /**
     * 建立双向好友关系（幂等）。
     *
     * <p>注册成功后 auth 会调用它，把新用户与管理员互加好友。</p>
     *
     * @return true 表示本次新建，false 表示已存在
     */
    boolean ensureFriend(long uidA, long uidB);

    /** 是否已是好友（发消息前的校验） */
    boolean isFriend(long uidA, long uidB);

    /** 好友列表（含昵称） */
    List<FriendDTO> friends(long uid);
}
