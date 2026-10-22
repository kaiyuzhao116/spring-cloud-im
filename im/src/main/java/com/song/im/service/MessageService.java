package com.song.im.service;

import com.song.common.BizException;
import com.song.common.ResultCode;
import com.song.im.domain.ConversationId;
import com.song.im.domain.SendRequest;
import com.song.im.domain.SendResult;
import com.song.im.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

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

    private final MessageRepository messageRepository;

    private final SeqGenerator seqGenerator;

    public MessageService(MessageRepository messageRepository, SeqGenerator seqGenerator) {
        this.messageRepository = messageRepository;
        this.seqGenerator = seqGenerator;
    }

    @Transactional
    public SendResult send(long fromUid, SendRequest request) {
        validate(request);

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
        return new SendResult(msgId, seq, conversationId);
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
