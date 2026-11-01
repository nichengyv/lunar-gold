package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chat_message", indexes = {
        @Index(name = "idx_session_id", columnList = "sessionId")
})
@EntityListeners(AuditingEntityListener.class)
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 50)
    private String toolName;

    /** 本轮助手消息的执行轨迹 JSON（思考/工具调用/来源/文件改动），历史还原时前端据此重建卡片 */
    @Column(columnDefinition = "TEXT")
    private String trace;

    @CreatedDate
    private LocalDateTime createTime;

    public enum MessageRole {
        USER, ASSISTANT, TOOL, SYSTEM
    }
}
