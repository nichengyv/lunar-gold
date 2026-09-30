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

import java.util.List;

@Service
@Slf4j
public class ChatService {

    @Autowired
    private ChatSessionRepository sessionRepo;//注入依赖

    @Autowired
    private ChatMessageRepository messageRepo;

    @Transactional
    public ChatSession createSession(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title);
        return sessionRepo.save(session);
    }

    public List<ChatSession> listSessions(Long userId) {
        //调用Repository层，拿取userid
        return sessionRepo.findByUserIdOrderByUpdateTimeDesc(userId);
    }

    public List<ChatMessage> getHistory(Long sessionId, Long userId) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问");
        }
        return messageRepo.findBySessionIdOrderByCreateTimeAsc(sessionId);
    }

    @Transactional
    public ChatMessage saveMessage(Long userId, Long sessionId, ChatMessage.MessageRole role, String content, String toolName) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(userId);
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setToolName(toolName);
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