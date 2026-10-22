package com.song.im.service;

import com.song.im.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 会话内序号生成器。
 *
 * <p>为什么用 Redis：会话内序号要求连续、并发不重复；数据库自增是全局的，
 * 用 {@code MAX(seq)+1} 又需要行锁。</p>
 *
 * <p>防坑：Redis 被清空后计数器会从 1 重来，撞上唯一键
 * {@code uk_conv_seq} 导致整个会话发不出消息，所以 INCR 前先做一次
 * 「不存在就从数据库 MAX(seq) 恢复」。</p>
 */
@Component
public class SeqGenerator {

    private static final Logger log = LoggerFactory.getLogger(SeqGenerator.class);

    private static final String KEY_PREFIX = "im:seq:";

    private final StringRedisTemplate redisTemplate;

    private final MessageRepository messageRepository;

    public SeqGenerator(StringRedisTemplate redisTemplate, MessageRepository messageRepository) {
        this.redisTemplate = redisTemplate;
        this.messageRepository = messageRepository;
    }

    /**
     * 取该会话的下一个序号（从 1 开始，连续递增）。
     */
    public long next(String conversationId) {
        String key = KEY_PREFIX + conversationId;

        if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
            long max = messageRepository.maxSeq(conversationId);
            // SETNX：并发时只有一个人写成功，其他人用已有值
            redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(max));
            log.info("seq 计数器重建 conversation={} from={}", conversationId, max);
        }

        Long seq = redisTemplate.opsForValue().increment(key);
        return seq == null ? 0L : seq;
    }
}
