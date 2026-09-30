package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    //框架根据方法名自动生成SQL语句（这里查询userid）
    List<ChatSession> findByUserIdOrderByUpdateTimeDesc(Long userId);
}
