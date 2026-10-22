package com.song.wsgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务线程池：承接 Dubbo 调用等阻塞操作，避免卡住 Netty 的 EventLoop。
 */
@Configuration
public class ExecutorConfig {

    @Bean(name = "bizExecutor", destroyMethod = "shutdown")
    public ExecutorService bizExecutor() {
        AtomicInteger index = new AtomicInteger(1);
        return Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "ws-biz-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }
}
