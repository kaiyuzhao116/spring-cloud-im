package com.song.api.im.service;

import com.song.api.im.dto.SendMessageCmd;
import com.song.api.im.dto.SendResultDTO;

/**
 * 消息服务对外契约（Dubbo）。
 *
 * <p>实现方：im 服务的 {@code ImMessageFacadeImpl}；调用方：ws-gateway。</p>
 */
public interface ImMessageFacade {

    /**
     * 发消息：落库成功即返回（推送由调用方或后续链路负责）。
     *
     * @param cmd 发送者 / 接收者 / 幂等键 / 内容
     * @return 消息 ID、会话内序号、会话 ID
     */
    SendResultDTO send(SendMessageCmd cmd);

    /**
     * 撤回消息：只允许发送者本人、且在自己发出后的限定时间内。
     *
     * @param uid  操作人
     * @param toUid 会话对端（单聊会话由双方 uid 推出，前端不必传 conv）
     * @param seq   要撤回的消息在当前会话内的序号
     */
    void recall(long uid, long toUid, long seq);

}
