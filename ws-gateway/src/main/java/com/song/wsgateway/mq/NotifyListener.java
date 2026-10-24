package com.song.wsgateway.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.wsgateway.protocol.Frame;
import com.song.wsgateway.session.ChannelManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费业务通知（好友申请、对方同意），推给本机在线的目标用户。
 *
 * <p>和消息推送的区别：这里没有 seq、不落库，对方不在线就直接丢弃，
 * 前端下次刷新列表就能看到最新状态。</p>
 */
@Component
public class NotifyListener {

    private static final Logger log = LoggerFactory.getLogger(NotifyListener.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChannelManager channelManager;

    public NotifyListener(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @RabbitListener(queues = "#{notifyQueue.name}")
    public void onNotify(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            long toUid = node.path("toUid").asLong();
            String event = node.path("event").asText("");
            long fromUid = node.path("fromUid").asLong();
            String fromName = node.path("fromName").asText("");

            int pushed = channelManager.pushToUser(toUid, Frame.notify(event, fromUid, fromName));
            log.info("推送通知 event={} to={} from={} 命中连接数={}", event, toUid, fromUid, pushed);
        } catch (Exception e) {
            // 通知失败不重投：它只是"提醒刷新"，不是数据同步
            log.warn("通知处理失败: {}", e.getMessage());
        }
    }
}
