package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chat_attachment", indexes = {
        @Index(name = "idx_chat_att_msg", columnList = "messageId"),
        @Index(name = "idx_chat_att_user", columnList = "userId")
})
@EntityListeners(AuditingEntityListener.class)
public class ChatAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column
    private Long messageId;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 50)
    private String fileType;

    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false, length = 500)
    private String storagePath;

    // TEXT 在 MySQL/PostgreSQL 中均为合法类型（LONGTEXT 是 MySQL 专有，统一 PG 后不可用）
    @Column(columnDefinition = "TEXT")
    private String contentText;

    @Column(length = 500)
    private String description;

    @CreatedDate
    private LocalDateTime createTime;
}