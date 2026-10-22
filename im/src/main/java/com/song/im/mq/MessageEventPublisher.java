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
 * <p>发送失败只记日志：消息已经在库里，用户仍可拉取，推送属于尽力而为。</p>
 */
@Component
public class MessageEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MessageEventPublisher.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RabbitTemplate rabbitTemplate;

    public MessageEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(MessageEvent event) {
        try {
            String body = MAPPER.writeValueAsString(event);
            rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, RabbitMqConfig.ROUTING_KEY_CREATED, body);
            log.info("消息事件已发送 msgId={} to={} seq={}", event.getMsgId(), event.getToUid(), event.getSeq());
        } catch (Exception e) {
            log.error("消息事件发送失败 msgId={}（消息已落库，不影响可拉取）", event.getMsgId(), e);
        }
    }
}
