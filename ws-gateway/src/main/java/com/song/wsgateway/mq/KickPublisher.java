package com.song.wsgateway.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.wsgateway.config.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 发布踢人事件。
 *
 * <p>为什么用广播而不是本机直接踢：多实例时，旧连接可能在**另一台机器**上，
 * 本机的连接表里根本没有它。广播出去，谁有谁踢。</p>
 *
 * <p>发送失败只记日志：踢不掉最多是两端同时在线（可接受），不能影响新用户登录。</p>
 */
@Component
public class KickPublisher {

    private static final Logger log = LoggerFactory.getLogger(KickPublisher.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RabbitTemplate rabbitTemplate;

    public KickPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(long uid, String excludeConnId, long newConnTime) {
        try {
            String payload = MAPPER.writeValueAsString(new KickEvent(uid, excludeConnId, newConnTime));
            rabbitTemplate.convertAndSend(RabbitMqConfig.WS_EXCHANGE, RabbitMqConfig.ROUTING_KEY_KICK, payload);
            log.info("广播踢人事件 uid={} 保留连接={} 新连接时间={}", uid, excludeConnId, newConnTime);
        } catch (Exception e) {
            log.warn("广播踢人事件失败 uid={} msg={}", uid, e.getMessage());
        }
    }
}
