package com.song.im.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 拓扑：im 只负责声明交换机并发布事件，队列由消费方（ws-gateway）各自声明。
 */
@Configuration
public class RabbitMqConfig {

    /** 消息事件交换机（topic，持久化） */
    public static final String EXCHANGE = "im.events";

    /** 消息已落库的路由键 */
    public static final String ROUTING_KEY_CREATED = "message.created";

    /** 通知类事件（好友申请/同意等），由 ws-gateway 推给在线用户 */
    public static final String WS_EXCHANGE = "ws.events";

    public static final String ROUTING_KEY_NOTIFY = "ws.notify";

    /** 踢人事件（单端登录 / 管理员强制下线），由 ws-gateway 消费 */
    public static final String ROUTING_KEY_KICK = "ws.kick";

    @Bean
    public TopicExchange imEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /**
     * 通知交换机。
     *
     * <p>im 端也声明一次：即使 ws-gateway 还没启动，消息也不会因为交换机不存在被丢弃。</p>
     */
    @Bean
    public TopicExchange wsEventsExchangeForNotify() {
        return new TopicExchange(WS_EXCHANGE, true, false);
    }
}
