package com.song.wsgateway.session;

/**
 * 连接关闭原因。
 *
 * <p>用枚举而不是裸字符串：写错会编译不过，也便于以后统计（如"今天 idle 踢了多少"）。</p>
 */
public enum CloseReason {

    /** 对端主动关闭（关页面、调 ws.close、断网） */
    CLIENT_CLOSED("client_closed"),

    /** 60 秒没有任何数据往来，服务端踢掉 */
    IDLE_TIMEOUT("idle_timeout"),

    /** token 已过期，服务端主动断开（下一小步实现） */
    TOKEN_EXPIRED("token_expired"),

    /** handler 里抛了异常 */
    ERROR("error");

    private final String code;

    CloseReason(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
