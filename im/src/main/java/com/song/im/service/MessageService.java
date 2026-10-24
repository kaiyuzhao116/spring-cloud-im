package com.song.im.service;

import com.song.common.BizException;
import com.song.common.ResultCode;
import com.song.im.domain.ConversationId;
import com.song.im.domain.ConversationItem;
import com.song.im.domain.MessageItem;
import com.song.im.domain.PullResult;
import com.song.im.domain.SendRequest;
import com.song.im.domain.SendResult;
import com.song.im.friend.FriendService;
import com.song.im.mq.FriendEventPublisher;
import com.song.im.mq.MessageEvent;
import com.song.im.mq.MessageEventPublisher;
import com.song.im.repository.MessageRepository;
import com.song.im.support.PeerNameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 发消息：校验 → 幂等 → 取 seq → 落库（消息 + 双方卡片）。
 *
 * <p>先落库，后续再由推送链路去通知对方；落库成功即视为发送成功。</p>
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private static final int MAX_CONTENT_LENGTH = 2000;

    private static final int PREVIEW_LENGTH = 100;

    /** 单次拉取上限，防止客户端一次要 10 万条 */
    private static final int MAX_PULL_LIMIT = 100;

    /** 撤回时间窗口：2 分钟 */
    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;

    /** 撤回后在会话列表里显示的预览文案 */
    private static final String RECALLED_PREVIEW = "[消息已撤回]";

    private final MessageRepository messageRepository;

    private final SeqGenerator seqGenerator;

    private final MessageEventPublisher eventPublisher;

    private final PeerNameResolver peerNameResolver;

    private final FriendService friendService;

    private final FriendEventPublisher friendEventPublisher;

    public MessageService(MessageRepository messageRepository, SeqGenerator seqGenerator,
                          MessageEventPublisher eventPublisher, PeerNameResolver peerNameResolver,
                          FriendService friendService, FriendEventPublisher friendEventPublisher) {
        this.messageRepository = messageRepository;
        this.seqGenerator = seqGenerator;
        this.eventPublisher = eventPublisher;
        this.peerNameResolver = peerNameResolver;
        this.friendService = friendService;
        this.friendEventPublisher = friendEventPublisher;
    }

    @Transactional
    public SendResult send(long fromUid, SendRequest request) {
        validate(request);

        // 好友校验：非好友一律拒绝。限制必须在后端，前端隐藏输入框挡不住脚本直连
        if (!friendService.isFriend(fromUid, request.getTo())) {
            throw new BizException(403, "请先添加好友再聊天");
        }

        String conversationId = ConversationId.single(fromUid, request.getTo());

        // 幂等：同一个 cid 重发，直接返回上次结果，不新增
        MessageRepository.MessageRow existing =
                messageRepository.findByClientMsgId(fromUid, request.getCid());
        if (existing != null) {
            log.info("重复消息命中幂等 from={} cid={} seq={}", fromUid, request.getCid(), existing.seq());
            return new SendResult(existing.id(), existing.seq(), conversationId);
        }

        long seq = seqGenerator.next(conversationId);

        long msgId;
        try {
            msgId = messageRepository.insert(conversationId, seq, fromUid, request.getTo(),
                    request.getContent(), request.getCid());
        } catch (DuplicateKeyException e) {
            // 并发下同一 cid 撞唯一键：回查后返回已有结果
            MessageRepository.MessageRow dup =
                    messageRepository.findByClientMsgId(fromUid, request.getCid());
            if (dup != null) {
                return new SendResult(dup.id(), dup.seq(), conversationId);
            }
            throw e;
        }

        messageRepository.upsertMembers(conversationId, fromUid, request.getTo(), seq,
                preview(request.getContent()), Timestamp.from(Instant.now()));

        log.info("消息落库成功 msgId={} conversation={} seq={} from={} to={}",
                msgId, conversationId, seq, fromUid, request.getTo());

        // 事件必须在事务提交后再发，否则消费端可能查不到这条消息
        publishAfterCommit(new MessageEvent(msgId, conversationId, seq, fromUid,
                request.getTo(), request.getContent(), System.currentTimeMillis()));

        return new SendResult(msgId, seq, conversationId);
    }

    /**
     * 拉取历史消息（按 seq 倒序取，返回时转为升序）。
     *
     * @param beforeSeq 取该 seq 之前的消息；传 0 或负数表示取最新的
     * @param limit     期望条数，内部夹在 1~100
     */
    public PullResult pull(long uid, String conversationId, long beforeSeq, int limit) {
        if (!ConversationId.contains(conversationId, uid)) {
            throw new BizException(403, "无权查看该会话");
        }

        int size = Math.min(Math.max(limit, 1), MAX_PULL_LIMIT);
        long upperBound = beforeSeq <= 0 ? Long.MAX_VALUE : beforeSeq;

        // 多取一条，用来判断"更早的还有没有"
        List<MessageItem> rows = messageRepository.findBefore(conversationId, upperBound, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }

        // 数据库按 seq 倒序取出（利于 LIMIT），返回给前端时转成升序
        List<MessageItem> ascending = new ArrayList<>(rows);
        Collections.reverse(ascending);
        return new PullResult(ascending, hasMore);
    }

    /**
     * 拉取 {@code afterSeq} 之后的消息（升序），用于断线重连后补缺口。
     *
     * <p>客户端只需要记住"我最后看到的 seq"，重连时传上来，
     * 服务端把漏掉的按顺序给它，不需要推送补发。</p>
     */
    public PullResult pullAfter(long uid, String conversationId, long afterSeq, int limit) {
        if (!ConversationId.contains(conversationId, uid)) {
            throw new BizException(403, "无权查看该会话");
        }

        int size = Math.min(Math.max(limit, 1), MAX_PULL_LIMIT);
        List<MessageItem> rows = messageRepository.findAfter(conversationId, afterSeq, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        return new PullResult(rows, hasMore);
    }

    /**
     * 会话列表（按最近消息时间倒序）。
     *
     * <p>只看 im_conversation_member 一张表，按 uid 走索引，不回消息表。</p>
     */
    public List<ConversationItem> conversations(long uid, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_PULL_LIMIT);
        List<ConversationItem> items = messageRepository.findConversations(uid, size);

        // 昵称来自 user 服务；查不到就留 null，前端会退回显示 uid
        return items.stream()
                .map(item -> new ConversationItem(
                        item.conversationId(),
                        item.peerUid(),
                        peerNameResolver.nicknameOf(item.peerUid()),
                        item.lastSeq(),
                        item.lastReadSeq(),
                        item.unread(),
                        item.preview(),
                        item.lastMsgAt()))
                .toList();
    }

    /**
     * 上报已读：把 last_read_seq 推进到 seq（只增不减）。
     *
     * <p>未读数是算出来的（last_seq - last_read_seq），所以这里推进一次，
     * 会话列表里的未读立刻变小，不需要另外维护计数。</p>
     */
    public void markRead(long uid, String conversationId, long seq) {
        if (!ConversationId.contains(conversationId, uid)) {
            throw new BizException(403, "无权操作该会话");
        }
        int rows = messageRepository.markRead(conversationId, uid, seq);
        log.debug("已读上报 uid={} conversation={} seq={} 影响行数={}", uid, conversationId, seq, rows);
    }

    /**
     * 撤回消息。
     *
     * <p>规则：只能撤自己发的、只能撤 2 分钟内的、不能重复撤。</p>
     *
     * <p>做法是"软删 + 清空内容"：保留行（seq 不能有洞，会话 seq 靠连续递增），
     * 只把 content 置空并打 recalled 标记。</p>
     */
    @Transactional
    public void recall(long uid, long toUid, long seq) {
        if (toUid <= 0) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "撤回目标不能为空");
        }
        String conversationId = ConversationId.single(uid, toUid);
        if (!ConversationId.contains(conversationId, uid)) {
            throw new BizException(403, "无权操作该会话");
        }

        MessageRepository.RecallRow row = messageRepository.findForRecall(conversationId, seq);
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "消息不存在");
        }
        if (row.fromUid() != uid) {
            throw new BizException(403, "只能撤回自己发送的消息");
        }
        if (row.recalled()) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "该消息已撤回");
        }
        if (System.currentTimeMillis() - row.createdAt() > RECALL_WINDOW_MS) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "超过 2 分钟，不能撤回");
        }

        if (messageRepository.markRecalled(conversationId, seq, uid) == 0) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "撤回失败，请重试");
        }
        messageRepository.updatePreview(conversationId, seq, RECALLED_PREVIEW);

        // 通知对端刷新那一条（通知丢了也不影响数据，历史里已带 recalled 标记）
        friendEventPublisher.notifyMessageRecalled(toUid, seq, uid);
        log.info("消息已撤回 conversation={} seq={} uid={}", conversationId, seq, uid);
    }

    private void publishAfterCommit(MessageEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publish(event);
                }
            });
        } else {
            eventPublisher.publish(event);
        }
    }

    private void validate(SendRequest request) {
        if (request.getTo() == null || request.getTo() <= 0) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "接收方不能为空");
        }
        if (request.getCid() == null || request.getCid().isBlank()) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "cid 不能为空");
        }
        if (request.getCid().length() > 64) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "cid 过长（≤64）");
        }
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "内容不能为空");
        }
        if (request.getContent().length() > MAX_CONTENT_LENGTH) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "内容超过 2000 字");
        }
    }

    private String preview(String content) {
        if (content.length() <= PREVIEW_LENGTH) {
            return content;
        }
        return content.substring(0, PREVIEW_LENGTH);
    }
}
