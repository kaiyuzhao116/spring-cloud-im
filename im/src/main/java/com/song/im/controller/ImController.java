package com.song.im.controller;

import com.song.common.BizException;
import com.song.common.Result;
import com.song.common.ResultCode;
import com.song.common.UserContext;
import com.song.im.domain.SendRequest;
import com.song.im.domain.SendResult;
import com.song.im.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * im 对外 HTTP 接口（联调用，正式入口仍是网关 + ws-gateway）。
 *
 * <p>发送者只认 {@code X-User-Id}（网关注入）；本地直测时 Apifox 手动加这个头。</p>
 */
@RestController
public class ImController {

    private static final Logger log = LoggerFactory.getLogger(ImController.class);

    private final MessageService messageService;

    public ImController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping("/message")
    public Result<SendResult> send(@RequestBody SendRequest request,
                                   @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long fromUid = resolveUserId(userIdHeader);
        if (fromUid == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        log.info("收到发消息请求 from={} to={} cid={}", fromUid, request.getTo(), request.getCid());
        return Result.ok(messageService.send(fromUid, request));
    }

    private Long resolveUserId(String userIdHeader) {
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            try {
                return Long.valueOf(userIdHeader.trim());
            } catch (NumberFormatException e) {
                throw new BizException(ResultCode.BAD_REQUEST.getCode(), "X-User-Id 格式错误");
            }
        }
        return UserContext.getUserId();
    }
}
