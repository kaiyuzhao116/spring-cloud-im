/**
 * 协议层：帧定义与编解码。
 *
 * <p>MVP 使用 JSON 文本帧（便于抓包调试），字段固定后不再改；
 * 后续量上来再考虑 Protobuf 二进制帧。</p>
 *
 * <p>计划类：</p>
 * <ul>
 *   <li>{@code Frame} —— 统一帧结构（type / cid / to / content / ts / code）</li>
 *   <li>{@code FrameType} —— PING / PONG / MSG / ACK / READ / KICK / ERR / BYE</li>
 *   <li>{@code FrameCodec} —— JSON ↔ TextWebSocketFrame，含大小与类型校验</li>
 * </ul>
 */
package com.song.wsgateway.protocol;
