package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    //框架根据方法名自动生成SQL语句（这里按用户 + 项目查询）
    List<ChatSession> findByUserIdAndProjectIdOrderByUpdateTimeDesc(Long userId, Long projectId);
}
