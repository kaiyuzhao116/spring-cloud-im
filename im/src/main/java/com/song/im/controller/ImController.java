package com.song.im.controller;

import com.song.common.BizException;
import com.song.common.Result;
import com.song.common.ResultCode;
import com.song.common.UserContext;
import com.song.im.domain.ConversationItem;
import com.song.im.domain.PullResult;
import com.song.im.domain.ReadRequest;
import com.song.im.domain.SendRequest;
import com.song.im.domain.SendResult;
import com.song.im.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /**
     * 拉取历史消息。
     *
     * <p>经网关访问：{@code GET /api/im/messages?conv=s_1_2&beforeSeq=0&limit=20}
     * （网关 StripPrefix=2 后到达本方法的 {@code /messages}）。</p>
     *
     * @param beforeSeq 上界（不含该 seq）；0 表示取最新
     */
    @GetMapping("/messages")
    public Result<PullResult> pull(@RequestParam("conv") String conv,
                                   @RequestParam(value = "beforeSeq", defaultValue = "0") long beforeSeq,
                                   @RequestParam(value = "afterSeq", defaultValue = "0") long afterSeq,
                                   @RequestParam(value = "limit", defaultValue = "20") int limit,
                                   @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = resolveUserId(userIdHeader);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        // afterSeq > 0：补缺口（升序）；否则：翻历史/取最新（升序返回）
        if (afterSeq > 0) {
            return Result.ok(messageService.pullAfter(userId, conv, afterSeq, limit));
        }
        return Result.ok(messageService.pull(userId, conv, beforeSeq, limit));
    }

    /**
     * 会话列表（含未读数），按最近消息时间倒序。
     *
     * <p>经网关：{@code GET /api/im/conversations?limit=50}</p>
     */
    @GetMapping("/conversations")
    public Result<List<ConversationItem>> conversations(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = resolveUserId(userIdHeader);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        return Result.ok(messageService.conversations(userId, limit));
    }

    /**
     * 上报已读位置。
     *
     * <p>经网关：{@code POST /api/im/read}，body {@code {"conv":"s_1_2","seq":43}}</p>
     */
    @PostMapping("/read")
    public Result<Void> markRead(@RequestBody ReadRequest request,
                                 @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = resolveUserId(userIdHeader);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        if (request.conv() == null || request.conv().isBlank()) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "conv 不能为空");
        }
        messageService.markRead(userId, request.conv(), request.seq());
        return Result.ok(null);
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
