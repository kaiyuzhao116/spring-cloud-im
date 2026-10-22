package com.song.wsgateway.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消费端 MQ 拓扑。
 *
 * <p>关键：每个 ws-gateway 实例声明一个**匿名、独占、自动删除**的队列，
 * 都绑定到同一个 fanout 之外的 topic exchange 上。这样事件会广播到所有实例，
 * 各实例再查"本机有没有这个用户的连接"。</p>
 *
 * <p>如果共用同一个队列，就变成竞争消费——只有一台实例收到，其他实例上的用户永远收不到。</p>
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE = "im.events";

    public static final String ROUTING_KEY_CREATED = "message.created";

    @Bean
    public TopicExchange imEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /** 匿名队列：名字由 Broker 生成，连接断开自动删除 */
    @Bean
    public Queue pushQueue() {
        return QueueBuilder.nonDurable().exclusive().autoDelete().build();
    }

    @Bean
    public Binding pushBinding(Queue pushQueue, TopicExchange imEventsExchange) {
        return BindingBuilder.bind(pushQueue).to(imEventsExchange).with(ROUTING_KEY_CREATED);
    }
}
