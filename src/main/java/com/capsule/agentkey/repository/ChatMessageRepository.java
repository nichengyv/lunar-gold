package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderByCreateTimeAsc(Long sessionId);
    void deleteBySessionId(Long sessionId);
}
