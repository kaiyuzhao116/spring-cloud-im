package com.song.im.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.im.config.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 发布"消息已落库"事件。
 *
 * <p>本类只负责"真的发出去"，<b>失败必须抛出</b>——由 {@code OutboxRelay} 捕获后重试。
 * 早期版本在这里吞掉异常，导致 MQ 抖动时推送永久丢失且无人知晓。</p>
 */
@Component
public class MessageEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MessageEventPublisher.class);

    /** Outbox 里表示"消息已落库"的事件类型，与路由键保持一致 */
    public static final String EVENT_MESSAGE_CREATED = RabbitMqConfig.ROUTING_KEY_CREATED;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RabbitTemplate rabbitTemplate;

    public MessageEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发送事件；失败抛 {@link IllegalStateException}，交给调用方决定重试。
     */
    public void publish(MessageEvent event) {
        try {
            String body = MAPPER.writeValueAsString(event);
            rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, RabbitMqConfig.ROUTING_KEY_CREATED, body);
            log.info("消息事件已发送 msgId={} to={} seq={}", event.getMsgId(), event.getToUid(), event.getSeq());
        } catch (Exception e) {
            // 不吞异常：消息仍在 outbox 表里，下一轮会重发
            throw new IllegalStateException("消息事件发送失败 msgId=" + event.getMsgId(), e);
        }
    }
}
