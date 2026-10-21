package com.song.user;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 用户服务：资料、好友关系、黑名单、设备。
 * <p>扫描 com.song 以便加载 spring-cloud-common 里的全局异常处理与身份拦截器。</p>
 */
@EnableDubbo
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.song")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }

}
