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
 * 好友相关通知：通过 MQ 广播给所有 ws-gateway，由持有目标用户连接的那台推给客户端。
 *
 * <p>和"发消息"的区别：这里不落库、不需要 seq，只是"通知一下"，
 * 所以丢了也无所谓（前端重连或下次刷新会拉到最新列表）。</p>
 */
@Component
public class FriendEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(FriendEventPublisher.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RabbitTemplate rabbitTemplate;

    public FriendEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 有人申请加你为好友 */
    public void notifyRequest(long fromUid, String fromName, long toUid) {
        send(toUid, "friend_request", fromUid, fromName);
    }

    /** 对方同意了你的好友申请 */
    public void notifyAccepted(long accepterUid, String accepterName, long toUid) {
        send(toUid, "friend_accepted", accepterUid, accepterName);
    }

    private void send(long toUid, String event, long fromUid, String fromName) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toUid", toUid);
            payload.put("event", event);
            payload.put("fromUid", fromUid);
            payload.put("fromName", fromName);
            payload.put("ts", System.currentTimeMillis());

            rabbitTemplate.convertAndSend(RabbitMqConfig.WS_EXCHANGE, RabbitMqConfig.ROUTING_KEY_NOTIFY,
                    MAPPER.writeValueAsString(payload));
            log.info("好友通知已投递 event={} to={} from={}", event, toUid, fromUid);
        } catch (Exception e) {
            // 通知不是关键路径：丢了最多是对方晚点看到
            log.warn("好友通知投递失败 event={} to={} msg={}", event, toUid, e.getMessage());
        }
    }
}
