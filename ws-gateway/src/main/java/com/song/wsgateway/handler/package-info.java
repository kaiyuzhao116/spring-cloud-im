/**
 * Netty Handler 层：连接生命周期与帧处理。
 *
 * <p>Pipeline 中的顺序：</p>
 * <pre>
 * HttpServerCodec → HttpObjectAggregator → AuthHandshakeHandler
 *   → WebSocketServerProtocolHandler → IdleStateHandler
 *   → BusinessHandler → GlobalExceptionHandler
 * </pre>
 *
 * <p>计划类：</p>
 * <ul>
 *   <li>{@code AuthHandshakeHandler} —— 握手前验签，把 userId 写入 Channel attr</li>
 *   <li>{@code HeartbeatHandler} —— 处理 PING/PONG 与 IdleStateEvent</li>
 *   <li>{@code BusinessHandler} —— 帧分发、业务线程池、Dubbo 异步回调</li>
 *   <li>{@code GlobalExceptionHandler} —— 异常兜底，回 ERR 帧</li>
 * </ul>
 *
 * <p>铁律：本包任何 handler 都不得在 EventLoop 线程里执行阻塞调用
 * （数据库、同步 Dubbo、sleep），否则会卡住该 EventLoop 上的所有连接。</p>
 */
package com.song.wsgateway.handler;
