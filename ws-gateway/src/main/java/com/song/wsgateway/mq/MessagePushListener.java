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
 * 消费"消息已落库"事件，推给本机在线的接收方。
 *
 * <p>推送是尽力而为：用户不在线就直接返回，靠客户端重连后拉取补偿。
 * 异常必须自行吞掉，否则消息会被反复重投。</p>
 */
@Component
public class MessagePushListener {

    private static final Logger log = LoggerFactory.getLogger(MessagePushListener.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChannelManager channelManager;

    public MessagePushListener(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @RabbitListener(queues = "#{pushQueue.name}")
    public void onMessage(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            long toUid = node.path("toUid").asLong();
            long fromUid = node.path("fromUid").asLong();
            long seq = node.path("seq").asLong();
            String content = node.path("content").asText("");

            int online = channelManager.pushToUser(toUid, Frame.down(fromUid, seq, content));
            log.info("推送消息 to={} from={} seq={} 命中连接数={}", toUid, fromUid, seq, online);
        } catch (Exception e) {
            log.warn("推送事件处理失败: {}", e.getMessage());
        }
    }
}
