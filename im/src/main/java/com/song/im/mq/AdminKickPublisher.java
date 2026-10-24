package com.song.im.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.im.config.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理员强制下线：广播一条踢人事件，让持有该用户连接的实例把它全部关掉。
 *
 * <p>复用单端登录那套 {@code ws.kick}：</p>
 * <ul>
 *   <li>{@code excludeConnId = null} → 不保留任何连接（全踢）</li>
 *   <li>{@code newConnTime = Long.MAX_VALUE} → 任何连接都比它早，都会被踢</li>
 * </ul>
 */
@Component
public class AdminKickPublisher {

    private static final Logger log = LoggerFactory.getLogger(AdminKickPublisher.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RabbitTemplate rabbitTemplate;

    public AdminKickPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void kickAll(long uid) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("uid", uid);
            payload.put("excludeConnId", null);
            payload.put("newConnTime", Long.MAX_VALUE);

            rabbitTemplate.convertAndSend(RabbitMqConfig.WS_EXCHANGE, RabbitMqConfig.ROUTING_KEY_KICK,
                    MAPPER.writeValueAsString(payload));
            log.info("管理员踢人事件已投递 uid={}", uid);
        } catch (Exception e) {
            log.warn("管理员踢人事件投递失败 uid={} msg={}", uid, e.getMessage());
        }
    }
}
