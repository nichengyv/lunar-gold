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

    /** 较早对话的滚动压缩摘要：历史超预算时生成，后续每轮注入 system prompt */
    @Column(columnDefinition = "TEXT")
    private String contextSummary;

    /** 已压缩到的最后一条 chat_message.id，增量压缩游标，避免每轮重复压缩同一段历史 */
    private Long summaryUntilId;

    /** 会话使用的对话模型；为空按配置默认模型处理 */
    @Column(length = 60)
    private String model;

    /** 推理档位：FAST / STANDARD / DEEP；为空按 STANDARD 处理 */
    @Column(length = 20)
    private String reasoning;

    /** 工作权限：READ_ONLY / WRITE / EXECUTE；为空按 EXECUTE 处理 */
    @Column(length = 20)
    private String permission;

    @CreatedDate
    private LocalDateTime createTime;

    @LastModifiedDate
    private LocalDateTime updateTime;
}
