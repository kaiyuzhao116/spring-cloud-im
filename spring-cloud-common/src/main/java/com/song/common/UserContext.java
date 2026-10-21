package com.song.common;

/**
 * 当前登录用户上下文。
 * <p>由网关验签后通过请求头下传，服务端由拦截器写入 ThreadLocal，业务代码只从这里取。</p>
 */
public final class UserContext {

    private static final ThreadLocal<Info> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId, String sessionId, String deviceType) {
        HOLDER.set(new Info(userId, sessionId, deviceType));
    }

    public static Long getUserId() {
        Info info = HOLDER.get();
        return info == null ? null : info.userId();
    }

    public static String getSessionId() {
        Info info = HOLDER.get();
        return info == null ? null : info.sessionId();
    }

    public static String getDeviceType() {
        Info info = HOLDER.get();
        return info == null ? null : info.deviceType();
    }

    /**
     * 必须在请求结束时调用，否则线程池复用会导致用户串号。
     */
    public static void clear() {
        HOLDER.remove();
    }

    public record Info(Long userId, String sessionId, String deviceType) {
    }
}
