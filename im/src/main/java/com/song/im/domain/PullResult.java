package com.song.im.domain;

import java.util.List;

/**
 * 拉取结果。
 *
 * <p>{@code messages} 按 seq 升序（方便前端直接渲染），
 * {@code hasMore} 表示"更早的还有"，供前端上滑加载。</p>
 */
public record PullResult(List<MessageItem> messages, boolean hasMore) {
}
