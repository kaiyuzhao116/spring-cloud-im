package com.song.auth.domain;

/**
 * 登录响应（精简版：不再返回 sessionId）。
 */
public record LoginResponse(Long userId,
                            String nickname,
                            String accessToken,
                            long expiresIn,
                            String deviceType) {
}
