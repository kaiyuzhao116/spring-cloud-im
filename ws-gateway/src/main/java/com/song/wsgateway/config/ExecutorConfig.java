package com.song.wsgateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务线程池：承接 Dubbo 调用等阻塞操作，避免卡住 Netty 的 EventLoop。
 *
 * <p>为什么不用 {@code Executors.newFixedThreadPool}：它内部是无界队列，
 * 下游变慢时任务会无限堆积直到 OOM，且拒绝策略永远不会触发。</p>
 *
 * <p>这里用<b>有界队列 + CallerRunsPolicy</b>：队列满时由调用者（Netty IO 线程）
 * 自己执行，形成背压——变慢但不丢消息、不撑爆内存。</p>
 */
@Configuration
public class ExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutorConfig.class);

    @Bean(name = "bizExecutor", destroyMethod = "shutdown")
    public ExecutorService bizExecutor(@Value("${ws.biz.threads:8}") int threads,
                                       @Value("${ws.biz.queue-capacity:5000}") int queueCapacity) {
        AtomicInteger index = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ws-biz-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());

        log.info("业务线程池已初始化 threads={} queueCapacity={} 拒绝策略=CallerRunsPolicy",
                threads, queueCapacity);
        return executor;
    }
}
