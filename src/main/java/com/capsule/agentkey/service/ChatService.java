package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.ChatMessageRepository;
import com.capsule.agentkey.repository.ChatSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class ChatService {

    @Autowired
    private ChatSessionRepository sessionRepo;//注入依赖

    @Autowired
    private ChatMessageRepository messageRepo;

    @Transactional
    public ChatSession createSession(Long userId, Long projectId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setProjectId(projectId);
        session.setTitle(title);
        return sessionRepo.save(session);
    }

    public List<ChatSession> listSessions(Long userId, Long projectId) {
        //调用Repository层，按用户 + 项目拿取会话
        return sessionRepo.findByUserIdAndProjectIdOrderByUpdateTimeDesc(userId, projectId);
    }

    /** 校验会话归属：会话不存在或不属于当前用户都统一提示 */
    public ChatSession getOwnedSession(Long sessionId, Long userId) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问");
        }
        return session;
    }

    public List<ChatMessage> getHistory(Long sessionId, Long userId) {
        getOwnedSession(sessionId, userId);
        return messageRepo.findBySessionIdOrderByCreateTimeAsc(sessionId);
    }

    /** 重命名会话（校验归属，标题长度与创建时保持一致） */
    @Transactional
    public void renameSession(Long sessionId, Long userId, String title) {
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException("会话名称不能为空");
        }
        if (trimmed.length() > 100) {
            throw new BusinessException("会话名称不能超过 100 个字符");
        }
        ChatSession session = getOwnedSession(sessionId, userId);
        session.setTitle(trimmed);
        sessionRepo.save(session);
    }

    @Transactional
    public ChatMessage saveMessage(Long userId, Long sessionId, ChatMessage.MessageRole role, String content, String toolName) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(userId);
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setToolName(toolName);
        //B4：任一消息落库都刷新会话 updateTime，
        //否则会话列表"按最近活跃排序"（findByUserIdOrderByUpdateTimeDesc）名不副实
        sessionRepo.findById(sessionId)
                .ifPresent(s -> s.setUpdateTime(LocalDateTime.now()));
        return messageRepo.save(msg);
    }

    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问");
        }
        messageRepo.deleteBySessionId(sessionId);
        sessionRepo.delete(session);
    }
}