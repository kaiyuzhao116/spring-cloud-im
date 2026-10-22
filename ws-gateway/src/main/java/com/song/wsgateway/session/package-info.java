/**
 * 连接会话层：本地连接表与身份上下文。
 *
 * <p>职责：维护 connId → Channel 的映射，以及 Channel 上的身份属性。
 * P1 起再叠加 Redis 全局路由（ws:online / ws:conn / ws:instance）。</p>
 *
 * <p>计划类：</p>
 * <ul>
 *   <li>{@code ChannelAttrs} —— AttributeKey 常量：USER_ID / DEVICE / CONN_ID / TOKEN_EXP</li>
 *   <li>{@code ChannelManager} —— 本地 ConcurrentHashMap&lt;String, Channel&gt;</li>
 *   <li>{@code OnlineRouter} ——（P1）Redis 路由读写</li>
 * </ul>
 */
package com.song.wsgateway.session;
