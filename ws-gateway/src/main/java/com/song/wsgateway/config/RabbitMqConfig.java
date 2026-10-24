package com.song.wsgateway.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 拓扑（消费端）。
 *
 * <p>关键：每个 ws-gateway 实例声明**匿名、独占、自动删除**的队列，
 * 绑定到同一个 topic exchange。这样事件会广播到所有实例，
 * 各实例再查"本机有没有这个用户的连接"。</p>
 *
 * <p>如果共用同一个队列，就变成竞争消费——只有一台实例收到，其他实例上的用户永远收不到。</p>
 *
 * <p>两个交换机：</p>
 * <ul>
 *   <li>{@code im.events} / {@code message.created}：新消息，需要推给接收方</li>
 *   <li>{@code ws.events} / {@code ws.kick}：单端登录踢人，需要关掉旧连接</li>
 * </ul>
 */
@Configuration
public class RabbitMqConfig {

    public static final String IM_EXCHANGE = "im.events";

    public static final String ROUTING_KEY_CREATED = "message.created";

    public static final String WS_EXCHANGE = "ws.events";

    public static final String ROUTING_KEY_KICK = "ws.kick";

    // ==================== 新消息推送 ====================

    @Bean
    public TopicExchange imEventsExchange() {
        return new TopicExchange(IM_EXCHANGE, true, false);
    }

    /** 匿名队列：名字由 Broker 生成，连接断开自动删除 */
    @Bean
    public Queue pushQueue() {
        return QueueBuilder.nonDurable().exclusive().autoDelete().build();
    }

    @Bean
    public Binding pushBinding(@Qualifier("pushQueue") Queue pushQueue,
                               @Qualifier("imEventsExchange") TopicExchange imEventsExchange) {
        return BindingBuilder.bind(pushQueue).to(imEventsExchange).with(ROUTING_KEY_CREATED);
    }

    // ==================== 单端登录踢人 ====================

    @Bean
    public TopicExchange wsEventsExchange() {
        return new TopicExchange(WS_EXCHANGE, true, false);
    }

    @Bean
    public Queue kickQueue() {
        return QueueBuilder.nonDurable().exclusive().autoDelete().build();
    }

    @Bean
    public Binding kickBinding(@Qualifier("kickQueue") Queue kickQueue,
                               @Qualifier("wsEventsExchange") TopicExchange wsEventsExchange) {
        return BindingBuilder.bind(kickQueue).to(wsEventsExchange).with(ROUTING_KEY_KICK);
    }
}
