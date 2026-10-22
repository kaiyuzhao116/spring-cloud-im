/**
 * Netty 服务端启动层。
 *
 * <p>职责：创建 EventLoopGroup、组装 Pipeline、绑定端口、优雅停机。
 * 本包只做"把服务跑起来"，不含任何业务判断。</p>
 *
 * <p>计划类：</p>
 * <ul>
 *   <li>{@code NettyServer} —— 实现 SmartLifecycle，最后启动最先停止</li>
 *   <li>{@code NettyProperties} —— {@code @ConfigurationProperties("netty")}</li>
 *   <li>{@code PipelineInitializer} —— 六件套 Pipeline 组装</li>
 * </ul>
 */
package com.song.wsgateway.server;
