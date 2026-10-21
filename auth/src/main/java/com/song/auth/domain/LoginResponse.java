package com.song.auth.domain;

/**
 * 登录响应。sessionId 即 JWT 里的 sid，前端可用于"退出本设备"。
 */
public record LoginResponse(Long userId,
                            String nickname,
                            String accessToken,
                            long expiresIn,
                            String sessionId,
                            String deviceType) {
}
