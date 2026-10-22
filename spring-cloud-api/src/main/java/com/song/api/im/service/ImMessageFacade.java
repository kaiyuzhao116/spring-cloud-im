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

}
