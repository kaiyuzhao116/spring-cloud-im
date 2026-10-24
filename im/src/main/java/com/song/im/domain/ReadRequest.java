package com.song.im.domain;

/**
 * 上报已读位置：客户端告诉我"这个会话我已经看到第 seq 条了"。
 *
 * <p>服务端只做"单调推进"（GREATEST），客户端乱序/重复上报都不会把已读位置往回退。</p>
 */
public record ReadRequest(String conv, long seq) {
}
