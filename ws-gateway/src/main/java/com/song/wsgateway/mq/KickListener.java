package com.song.wsgateway.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.wsgateway.session.ChannelManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费踢人事件：把本机上该用户的旧连接以 4001 关掉。
 *
 * <p>每个实例都会收到（各自一个匿名队列），但只有真正持有旧连接的那台会踢到人。</p>
 *
 * <p>异常必须自行吞掉，否则消息会被反复重投。</p>
 */
@Component
public class KickListener {

    private static final Logger log = LoggerFactory.getLogger(KickListener.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChannelManager channelManager;

    public KickListener(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @RabbitListener(queues = "#{kickQueue.name}")
    public void onKick(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            long uid = node.path("uid").asLong();
            String excludeConnId = node.path("excludeConnId").asText(null);
            long newConnTime = node.path("newConnTime").asLong(0);

            int kicked = channelManager.kickOthers(uid, excludeConnId, newConnTime);
            log.info("收到踢人事件 uid={} 排除={} 本机踢掉={} 条", uid, excludeConnId, kicked);
        } catch (Exception e) {
            log.warn("踢人事件处理失败: {}", e.getMessage());
        }
    }
}
