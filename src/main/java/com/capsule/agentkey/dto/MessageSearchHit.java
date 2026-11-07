package com.capsule.agentkey.dto;

import java.time.LocalDateTime;

/**
 * 消息全文搜索命中项。
 * 按会话归并：同一会话里命中多句时只返回最新一条，避免侧栏被同一会话刷屏。
 */
public record MessageSearchHit(
        Long messageId,
        Long sessionId,
        Long projectId,
        String sessionTitle,
        String role,
        String snippet,
        LocalDateTime createTime
) {
}
