package com.song.common;

/**
 * 统一业务返回码。
 */
public enum ResultCode {

    SUCCESS(0, "ok"),
    BAD_REQUEST(400, "请求参数不合法"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "没有权限"),
    NOT_FOUND(404, "资源不存在"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试"),
    SERVER_ERROR(500, "系统繁忙，请稍后再试"),
    SERVICE_UNAVAILABLE(503, "依赖服务不可用");

    private final int code;

    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
