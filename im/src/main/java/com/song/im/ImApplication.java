package com.song.im;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IM 服务：消息、会话、群。MVP 阶段为单体（Dubbo Provider + HTTP 8085），
 * 内部按 message / conversation / group 分包，后续可平移到独立服务。
 *
 * <p>scanBasePackages = "com.song"：让 spring-cloud-common 里的
 * GlobalExceptionHandler、UserContextInterceptor 生效。</p>
 */
@EnableDubbo
@EnableDiscoveryClient
@EnableScheduling          // Outbox 投递器（OutboxRelay）依赖定时任务
@SpringBootApplication(scanBasePackages = "com.song")
public class ImApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImApplication.class, args);
    }

}
