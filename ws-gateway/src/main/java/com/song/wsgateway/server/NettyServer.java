package com.song.wsgateway.server;

import com.song.wsgateway.handler.AuthHandshakeHandler;
import com.song.wsgateway.handler.BusinessHandler;
import com.song.wsgateway.handler.ConnectionHandler;
import com.song.wsgateway.session.ChannelManager;
import com.song.wsgateway.support.ImClient;
import com.song.wsgateway.support.JwtVerifier;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Netty 服务端：随 Spring 生命周期启停。
 *
 * <p>启动顺序：Nacos / Dubbo 就绪 → 本类 start() → 绑定端口；
 * 关闭时先停止接收新连接，再优雅关闭线程组。</p>
 */
@Component
public class NettyServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private static final String WS_PATH = "/ws";

    private final NettyProperties properties;

    private final JwtVerifier jwtVerifier;

    private final ChannelManager channelManager;

    private final ImClient imClient;

    private final ExecutorService bizExecutor;

    private EventLoopGroup bossGroup;

    private EventLoopGroup workerGroup;

    private Channel serverChannel;

    private volatile boolean running;

    public NettyServer(NettyProperties properties, JwtVerifier jwtVerifier, ChannelManager channelManager,
                       ImClient imClient, @Qualifier("bizExecutor") ExecutorService bizExecutor) {
        this.properties = properties;
        this.jwtVerifier = jwtVerifier;
        this.channelManager = channelManager;
        this.imClient = imClient;
        this.bizExecutor = bizExecutor;
    }

    @Override
    public void start() {
        bossGroup = new NioEventLoopGroup(properties.getBossThreads());
        workerGroup = new NioEventLoopGroup(properties.getWorkerThreads());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            initPipeline(ch);
                        }
                    });

            serverChannel = bootstrap.bind(properties.getPort()).sync().channel();
            running = true;
            log.info("Netty 已启动 port={} path={} workerThreads={}",
                    properties.getPort(), WS_PATH,
                    properties.getWorkerThreads() == 0 ? "CPU*2" : properties.getWorkerThreads());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw new IllegalStateException("Netty 启动被中断", e);
        } catch (Exception e) {
            shutdownGroups();
            throw new IllegalStateException("Netty 启动失败 port=" + properties.getPort(), e);
        }
    }

    /**
     * 流水线装配（顺序不可随意调整）：
     * HTTP 编解码 → 请求聚合 → 握手验签 → WS 升级 → 空闲检测 → 连接管理。
     */
    private void initPipeline(SocketChannel ch) {
        WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(WS_PATH)
                // 允许 /ws?token=xxx 这种带查询参数的路径
                .checkStartsWith(true)
                .maxFramePayloadLength(properties.getMaxFrameLength())
                .build();

        ch.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(properties.getMaxFrameLength()))
                .addLast(new AuthHandshakeHandler(jwtVerifier))
                .addLast(new WebSocketServerProtocolHandler(wsConfig))
                .addLast(new IdleStateHandler(0, 0, properties.getHeartbeatIdleSeconds()))
                .addLast(new BusinessHandler(imClient, bizExecutor))
                .addLast(new ConnectionHandler(channelManager));
    }

    @Override
    public void stop() {
        log.info("Netty 正在关闭，当前连接数={}", channelManager.size());
        running = false;
        if (serverChannel != null) {
            serverChannel.close();
        }
        shutdownGroups();
        log.info("Netty 已关闭");
    }

    private void shutdownGroups() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 尽量晚启动、尽量早关闭，确保 Dubbo / Nacos 已就绪。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
