package com.song.wsgateway.support;

import com.song.api.im.dto.SendMessageCmd;
import com.song.api.im.dto.SendResultDTO;
import com.song.api.im.service.ImMessageFacade;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/**
 * im 服务的 Dubbo 客户端封装。
 *
 * <p>注意：调用是阻塞的，必须在业务线程池里执行，绝不能在 Netty 的 EventLoop 里调。</p>
 */
@Component
public class ImClient {

    @DubboReference(version = "1.0.0", group = "im", check = false, timeout = 3000, retries = 0)
    private ImMessageFacade imMessageFacade;

    public SendResultDTO send(long fromUid, long toUid, String clientMsgId, String content) {
        SendMessageCmd cmd = new SendMessageCmd();
        cmd.setFromUid(fromUid);
        cmd.setToUid(toUid);
        cmd.setClientMsgId(clientMsgId);
        cmd.setContent(content);
        return imMessageFacade.send(cmd);
    }
}
