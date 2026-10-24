package com.song.im.friend;

import com.song.api.im.dto.FriendDTO;
import com.song.im.support.OnlineQuery;
import com.song.im.support.PeerNameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 好友关系业务。
 *
 * <p>关键规则：</p>
 * <ul>
 *   <li>关系双向存两行（A→B、B→A），保证"查我的好友"能走索引</li>
 *   <li>建立关系幂等（重复调用不报错），注册流程可以安全重试</li>
 *   <li>发消息前必须校验好友，非好友一律拒绝（限制在后端，不在前端）</li>
 * </ul>
 */
@Service
public class FriendService {

    private static final Logger log = LoggerFactory.getLogger(FriendService.class);

    private final FriendRepository friendRepository;

    private final PeerNameResolver peerNameResolver;

    private final OnlineQuery onlineQuery;

    public FriendService(FriendRepository friendRepository, PeerNameResolver peerNameResolver,
                         OnlineQuery onlineQuery) {
        this.friendRepository = friendRepository;
        this.peerNameResolver = peerNameResolver;
        this.onlineQuery = onlineQuery;
    }

    /**
     * 建立双向好友关系（幂等）。
     *
     * @return true 表示本次真正新建；false 表示早就有了（或自己加自己）
     */
    @Transactional
    public boolean ensure(long uidA, long uidB) {
        if (uidA <= 0 || uidB <= 0 || uidA == uidB) {
            return false;
        }
        boolean existed = friendRepository.exists(uidA, uidB);
        try {
            friendRepository.upsertBoth(uidA, uidB);
        } catch (DuplicateKeyException e) {
            // 并发下偶发：另一请求刚插入，结果一致，视为成功
            log.debug("好友关系并发写入 uidA={} uidB={}", uidA, uidB);
        }
        if (!existed) {
            log.info("建立好友关系 uidA={} uidB={}", uidA, uidB);
        }
        return !existed;
    }

    public boolean isFriend(long uid, long friendUid) {
        if (uid == friendUid) {
            return false;
        }
        return friendRepository.exists(uid, friendUid);
    }

    /** 好友列表（批量取昵称与在线状态，避免 N+1 次 RPC） */
    public List<FriendDTO> friends(long uid) {
        List<Long> ids = friendRepository.friendIds(uid);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = peerNameResolver.nicknamesOf(ids);
        Set<Long> online = onlineQuery.onlineAmong(ids);

        List<FriendDTO> result = new ArrayList<>(ids.size());
        for (Long friendUid : ids) {
            result.add(new FriendDTO(friendUid, names.get(friendUid), null, online.contains(friendUid)));
        }
        return result;
    }
}
