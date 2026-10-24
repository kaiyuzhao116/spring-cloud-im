package com.song.im.friend;

import com.song.common.BizException;
import com.song.common.ResultCode;
import com.song.im.domain.FriendRequestItem;
import com.song.im.support.PeerNameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 好友申请业务：发起 → 对方同意/拒绝。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>不能加自己、不能加已经是好友的人</li>
 *   <li>同一对用户只有一条申请记录，重复申请覆盖旧的（不会刷屏）</li>
 *   <li>只有"被申请方"能处理自己的申请（SQL 里带 {@code to_uid = ?}）</li>
 * </ul>
 */
@Service
public class FriendRequestService {

    private static final Logger log = LoggerFactory.getLogger(FriendRequestService.class);

    private static final int MAX_MESSAGE_LENGTH = 100;

    private final FriendRequestRepository requestRepository;

    private final FriendService friendService;

    private final PeerNameResolver peerNameResolver;

    public FriendRequestService(FriendRequestRepository requestRepository,
                                FriendService friendService,
                                PeerNameResolver peerNameResolver) {
        this.requestRepository = requestRepository;
        this.friendService = friendService;
        this.peerNameResolver = peerNameResolver;
    }

    public void send(long fromUid, long toUid, String message) {
        if (toUid <= 0) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "请选择要添加的用户");
        }
        if (fromUid == toUid) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "不能添加自己为好友");
        }
        if (friendService.isFriend(fromUid, toUid)) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "你们已经是好友了");
        }

        String msg = message == null ? null : message.trim();
        if (msg != null && msg.length() > MAX_MESSAGE_LENGTH) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "验证消息最多 100 字");
        }

        requestRepository.upsertPending(fromUid, toUid, msg);
        log.info("好友申请 from={} to={}", fromUid, toUid);
    }

    /** 我收到的待处理申请（含申请人昵称） */
    public List<FriendRequestItem> incoming(long uid, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        List<FriendRequestRepository.Row> rows = requestRepository.listIncoming(uid, size);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> fromUids = rows.stream().map(FriendRequestRepository.Row::fromUid).toList();
        Map<Long, String> names = peerNameResolver.nicknamesOf(fromUids);

        List<FriendRequestItem> result = new ArrayList<>(rows.size());
        for (FriendRequestRepository.Row row : rows) {
            result.add(new FriendRequestItem(row.id(), row.fromUid(), names.get(row.fromUid()),
                    row.message(), row.status(), row.createdAt()));
        }
        return result;
    }

    /**
     * 同意：标记申请 + 建立双向好友关系（同一事务，避免"同意了却没成为好友"）。
     */
    @Transactional
    public void accept(long uid, long requestId) {
        FriendRequestRepository.Row row = requirePending(uid, requestId);
        requestRepository.markHandled(requestId, uid, 1);
        friendService.ensure(row.fromUid(), uid);
        log.info("同意好友申请 requestId={} from={} to={}", requestId, row.fromUid(), uid);
    }

    public void reject(long uid, long requestId) {
        requirePending(uid, requestId);
        requestRepository.markHandled(requestId, uid, 2);
        log.info("拒绝好友申请 requestId={} uid={}", requestId, uid);
    }

    private FriendRequestRepository.Row requirePending(long uid, long requestId) {
        FriendRequestRepository.Row row = requestRepository.findById(requestId);
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "申请不存在");
        }
        if (row.status() != 0) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "该申请已处理");
        }
        return row;
    }
}
