package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderByCreateTimeAsc(Long sessionId);
    void deleteBySessionId(Long sessionId);

    /** 批次 B2：会话某角色消息数（用于判断是否到了 N 轮触发自动提炼） */
    long countBySessionIdAndRole(Long sessionId, ChatMessage.MessageRole role);

    /**
     * 消息全文搜索：按用户 + 正文包含关键字（忽略大小写），按时间倒序取最近若干条。
     * 多取一些是为了在服务层按会话去重后还能凑够目标条数。
     */
    List<ChatMessage> findTop30ByUserIdAndContentContainingIgnoreCaseOrderByCreateTimeDesc(
            Long userId, String content);
}
