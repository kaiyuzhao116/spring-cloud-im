package com.song.wsgateway.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Netty 接入层配置（对应 application.yml 里的 netty.*）。
 */
@Component
@ConfigurationProperties(prefix = "netty")
public class NettyProperties {

    /** 监听端口 */
    private int port = 8089;

    /** boss 线程数（负责 accept，1 个够） */
    private int bossThreads = 1;

    /** worker 线程数（0 = CPU 核数 * 2） */
    private int workerThreads = 0;

    /** 读空闲秒数，超过则断开连接 */
    private int heartbeatIdleSeconds = 60;

    /** 单帧最大长度（字节） */
    private int maxFrameLength = 65536;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public void setBossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getHeartbeatIdleSeconds() {
        return heartbeatIdleSeconds;
    }

    public void setHeartbeatIdleSeconds(int heartbeatIdleSeconds) {
        this.heartbeatIdleSeconds = heartbeatIdleSeconds;
    }

    public int getMaxFrameLength() {
        return maxFrameLength;
    }

    public void setMaxFrameLength(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }
}
