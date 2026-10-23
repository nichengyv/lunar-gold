package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chat_session", indexes = {
        @Index(name = "idx_user_id", columnList = "userId")
})
@EntityListeners(AuditingEntityListener.class)
public class ChatSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 所属项目：会话列表按项目过滤 */
    @Column(nullable = false)
    private Long projectId;

    @Column(length = 100)
    private String title;

    @CreatedDate
    private LocalDateTime createTime;

    @LastModifiedDate
    private LocalDateTime updateTime;
}
