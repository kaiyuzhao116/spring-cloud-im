package com.song.wsgateway.mq;

/**
 * 踢人事件（广播给所有 ws-gateway 实例）。
 *
 * @param uid           要踢的用户
 * @param excludeConnId 保留哪条连接（就是刚登录的那条）
 * @param newConnTime   新连接的建立时间（毫秒）
 *
 * <p>为什么要带 newConnTime：用户快速重连时，两个 kick 事件可能乱序到达，
 * 先发的旧事件若照踢不误，会把更新的连接也踢掉（表现为"刚连上又断了"）。
 * 踢人时只处理"比 newConnTime 更早"的连接，乱序事件就自动失效。</p>
 */
public record KickEvent(long uid, String excludeConnId, long newConnTime) {
}
