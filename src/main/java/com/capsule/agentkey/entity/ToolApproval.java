package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "tool_approval")
@EntityListeners(AuditingEntityListener.class)
public class ToolApproval {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String approvalId;

    @Column(nullable = false)
    private Long userId;

    private Long sessionId;

    @Column(nullable = false, length = 100)
    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String arguments;

    @Column(nullable = false, length = 20)
    private String risk;

    /** PENDING / APPROVED / REJECTED */
    @Column(nullable = false, length = 20)
    private String state;

    @Column(nullable = false)
    private Long expiresAt;

    @Column(nullable = false)
    private boolean rememberSession;

    private LocalDateTime decisionTime;

    /** P5-10-2：决策来源 USER / BATCH / TIMEOUT / SESSION_STOP / SYSTEM；旧记录为 NULL */
    @Column(length = 20)
    private String decisionSource;

    @CreatedDate
    private LocalDateTime createTime;

    @LastModifiedDate
    private LocalDateTime updateTime;
}
