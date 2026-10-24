package com.song.im.dubbo;

import com.song.api.im.dto.FriendDTO;
import com.song.api.im.service.ImFriendFacade;
import com.song.im.friend.FriendService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * 好友关系 Dubbo 实现：供 auth（注册时自动加管理员）等调用。
 */
@DubboService(version = "1.0.0", group = "im")
public class ImFriendFacadeImpl implements ImFriendFacade {

    private final FriendService friendService;

    public ImFriendFacadeImpl(FriendService friendService) {
        this.friendService = friendService;
    }

    @Override
    public boolean ensureFriend(long uidA, long uidB) {
        return friendService.ensure(uidA, uidB);
    }

    @Override
    public boolean isFriend(long uidA, long uidB) {
        return friendService.isFriend(uidA, uidB);
    }

    @Override
    public List<FriendDTO> friends(long uid) {
        return friendService.friends(uid);
    }
}
