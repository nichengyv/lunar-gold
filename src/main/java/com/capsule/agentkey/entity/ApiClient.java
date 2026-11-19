package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * P6-01：本地 API 客户端身份。
 *
 * 与用户的关系：客户端最终解析到同一个本地用户（user_id），因此所有归属校验
 * 仍走原有逻辑；本表只解决「可识别、可撤销、可限权」。
 *
 * 安全约定：secret_hash 存 SHA-256 十六进制，明文 secret 只在创建响应里出现一次。
 */
@Data
@Entity
@Table(name = "api_client", indexes = {
        @Index(name = "idx_api_client_user", columnList = "userId")
})
@EntityListeners(AuditingEntityListener.class)
public class ApiClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外标识，令牌内携带，12 位 Base64URL */
    @Column(nullable = false, unique = true, length = 32)
    private String clientId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String name;

    /** 明文 secret 的 SHA-256 十六进制，不存明文 */
    @Column(nullable = false, length = 64)
    private String secretHash;

    /** 逗号分隔的 scope 列表 */
    @Column(nullable = false, length = 255)
    private String scopes;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 非空即失效 */
    private LocalDateTime revokedAt;

    /** 每次认证成功更新，失败不更新 */
    private LocalDateTime lastUsedAt;
}
