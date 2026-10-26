package com.song.im.controller;

import com.song.api.im.dto.FriendDTO;
import com.song.common.BizException;
import com.song.common.Result;
import com.song.common.ResultCode;
import com.song.common.UserContext;
import com.song.im.friend.FriendRequestService;
import com.song.im.friend.FriendService;
import com.song.im.mq.AdminKickPublisher;
import com.song.im.support.AdminGuard;
import com.song.im.support.KickRegistry;
import com.song.im.support.OnlineQuery;
import com.song.im.domain.ConversationItem;
import com.song.im.domain.FriendRequestItem;
import com.song.im.domain.PullResult;

import com.song.im.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private final FriendService friendService;

    private final FriendRequestService friendRequestService;

    private final OnlineQuery onlineQuery;

    private final AdminGuard adminGuard;

    private final AdminKickPublisher adminKickPublisher;

    private final KickRegistry kickRegistry;

    public ImController(MessageService messageService, FriendService friendService,
                        FriendRequestService friendRequestService, OnlineQuery onlineQuery,
                        AdminGuard adminGuard, AdminKickPublisher adminKickPublisher,
                        KickRegistry kickRegistry) {
        this.messageService = messageService;
        this.friendService = friendService;
        this.friendRequestService = friendRequestService;
        this.onlineQuery = onlineQuery;
        this.adminGuard = adminGuard;
        this.adminKickPublisher = adminKickPublisher;
        this.kickRegistry = kickRegistry;
    }

    /**
     * 发起好友申请。
     *
     * <p>经网关：{@code POST /api/im/friends/requests}，body {@code {"toUid":3,"message":"我是小王"}}</p>
     */
    @PostMapping("/friends/requests")
    public Result<Void> sendFriendRequest(@RequestBody FriendRequestCommand command,
                                          @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = resolveUserId(userIdHeader);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        friendRequestService.send(userId, command.toUid(), command.message());
        return Result.ok(null);
    }

    /** 我收到的待处理好友申请 */
    @GetMapping("/friends/requests")
    public Result<List<FriendRequestItem>> friendRequests(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = resolveUserId(userIdHeader);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        return Result.ok(friendRequestService.incoming(userId, 50));
    }

    /** 同意好友申请 */
    @PostMapping("/friends/requests/{id}/accept")
    public Result<Void> acceptFriendRequest(@PathVariable("id") long id,
                                            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = resolveUserId(userIdHeader);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        friendRequestService.accept(userId, id);
        return Result.ok(null);
    }

    /** 拒绝好友申请 */
    @PostMapping("/friends/requests/{id}/reject")
    public Result<Void> rejectFriendRequest(@PathVariable("id") long id,
                                            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = resolveUserId(userIdHeader);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        friendRequestService.reject(userId, id);
        return Result.ok(null);
    }

    /** 好友申请请求体 */
    public record FriendRequestCommand(long toUid, String message) {
    }

    /**
     * 批量查在线状态（后台用）。
     *
     * <p>经网关：{@code GET /api/im/online?uids=1,2,3}</p>
     */
    @GetMapping("/online")
    public Result<List<OnlineVO>> online(@RequestParam("uids") String uids,
                                         @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = resolveUserId(userIdHeader);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        adminGuard.requireAdmin(userId);

        List<Long> ids = new java.util.ArrayList<>();
        for (String part : uids.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    ids.add(Long.parseLong(trimmed));
                } catch (NumberFormatException ignored) {
                    // 忽略非法 id
                }
            }
        }
        if (ids.isEmpty()) {
            return Result.ok(List.of());
        }

        java.util.Set<Long> onlineSet = onlineQuery.onlineAmong(ids);
        List<OnlineVO> result = ids.stream()
                .map(id -> new OnlineVO(id, onlineSet.contains(id)))
                .toList();
        return Result.ok(result);
    }

    /**
     * 管理员强制下线：踢掉该用户的所有连接。
     *
     * <p>经网关：{@code POST /api/im/admin/kick/{uid}}</p>
     */
    @PostMapping("/admin/kick/{uid}")
    public Result<Void> kick(@PathVariable("uid") long targetUid,
                             @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = resolveUserId(userIdHeader);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        adminGuard.requireAdmin(userId);
        if (userId == targetUid) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "不能踢自己");
        }

        // ① 让旧 token 作废（否则对方刷新一下又连上了）
        kickRegistry.markKicked(targetUid);
        // ② 断开当前连接
        adminKickPublisher.kickAll(targetUid);

        log.info("管理员踢人 operator={} target={}", userId, targetUid);
        return Result.ok(null);
    }

    /** 在线状态返回项 */
    public record OnlineVO(long uid, boolean online) {
    }

    /**
     * 好友列表（含昵称）。
     *
     * <p>经网关：{@code GET /api/im/friends}</p>
     */
    @GetMapping("/friends")
    public Result<List<FriendDTO>> friends(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = resolveUserId(userIdHeader);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
        }
        return Result.ok(friendService.friends(userId));
    }

    /*
     * 曾经有一个 POST /message，用于 HTTP 直接发消息（调试用）。
     * 已移除，原因：
     *   ① 业务上没有任何调用方——前端走 WebSocket，ws-gateway 走 Dubbo
     *   ② 它绕过了接入层全部保护（令牌桶 10/秒、队列水位、4KB 单帧），
     *      等于给了一条"无限速发消息"的旁路，可被用来压垮 im 与 MySQL
     * 需要发消息请走 WebSocket；造测试数据可用 deploy/ws-demo.py。
     */

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

    /*
     * POST /read（已读上报）已移除：未读数不再维护，前端也不显示红点。
     * 会话表里的 last_read_seq 列保留，仅作历史数据。
     */

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
