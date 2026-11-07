package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 用户全局默认对话参数（设置页"对话默认"tab 的数据落库）。
 *
 * 每个用户一条记录（userId 唯一），存默认模型/推理档位/工作权限。
 * 新会话启动时用这个值初始化 chat store。
 */
@Data
@Entity
@Table(name = "user_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uk_us_user", columnNames = "userId")
})
@EntityListeners(AuditingEntityListener.class)
public class UserSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 64)
    private String defaultModel = "deepseek-chat";

    @Column(length = 16)
    private String defaultReasoning = "STANDARD";

    @Column(length = 16)
    private String defaultPermission = "MANUAL";

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
