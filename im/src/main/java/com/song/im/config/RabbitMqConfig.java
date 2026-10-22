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

    @Bean
    public TopicExchange imEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }
}
