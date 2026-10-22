/**
 * 支撑组件：不依赖 Netty 生命周期的纯技术能力。
 *
 * <p>计划类：</p>
 * <ul>
 *   <li>{@code JwtVerifier} —— 与 api-gateway 同款验签（HS384），只读 sub / exp</li>
 *   <li>{@code TokenExtractor} —— 从 Header / SubProtocol / query 提取 token</li>
 * </ul>
 */
package com.song.wsgateway.support;
