package com.song.im;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * IM 服务：消息、会话、群。MVP 阶段为 Dubbo Provider 单体，
 * 内部按 message / conversation / group 分包，后续可平移到独立服务。
 */
@EnableDubbo
@EnableDiscoveryClient
@SpringBootApplication
public class ImApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImApplication.class, args);
    }

}
