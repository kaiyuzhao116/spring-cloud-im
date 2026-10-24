package com.song.im.dubbo;

import com.song.api.im.dto.SendMessageCmd;
import com.song.api.im.dto.SendResultDTO;
import com.song.api.im.service.ImMessageFacade;
import com.song.im.domain.SendRequest;
import com.song.im.domain.SendResult;
import com.song.im.service.MessageService;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * Dubbo 实现：只做参数转换，业务逻辑复用 {@link MessageService}。
 */
@DubboService(version = "1.0.0", group = "im")
public class ImMessageFacadeImpl implements ImMessageFacade {

    private final MessageService messageService;

    public ImMessageFacadeImpl(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public SendResultDTO send(SendMessageCmd cmd) {
        if (cmd == null || cmd.getFromUid() == null || cmd.getFromUid() <= 0) {
            throw new IllegalArgumentException("fromUid 不能为空");
        }

        SendRequest request = new SendRequest();
        request.setTo(cmd.getToUid());
        request.setCid(cmd.getClientMsgId());
        request.setContent(cmd.getContent());

        SendResult result = messageService.send(cmd.getFromUid(), request);
        return new SendResultDTO(result.msgId(), result.seq(), result.conversationId());
    }

    @Override
    public void recall(long uid, long toUid, long seq) {
        messageService.recall(uid, toUid, seq);
    }
}
