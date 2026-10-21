package com.song.wsgateway;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 长连接接入层（Netty 独立进程，非 Web 容器）。
 * 后续在此实现：WebSocket / TCP 握手、心跳、编解码、连接注册到 Redis、通过 Dubbo 调用 im 服务。
 */
@EnableDubbo
@SpringBootApplication
public class WsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(WsGatewayApplication.class, args);
    }

}
