package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 用户自助接入的 MCP Server（仅桌面端）。
 *
 * <p>与 application.yml 里开发者配置的 server 并存：YAML server 只读，本表由用户增删改。
 * 环境变量可能包含令牌，{@code envEncrypted} 存 AES-GCM 密文，接口只返回变量名。
 */
@Data
@Entity
@Table(name = "mcp_server", indexes = {
        @Index(name = "idx_mcp_server_user", columnList = "userId")
})
@EntityListeners(AuditingEntityListener.class)
public class McpServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 展示名与工具前缀，同一用户下唯一 */
    @Column(nullable = false, length = 64)
    private String name;

    /** 可执行命令，例如 npx、uvx、node；由 ProcessBuilder 直接启动，不经过 shell */
    @Column(nullable = false, length = 512)
    private String command;

    /** JSON 数组，启动参数 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String argsJson = "[]";

    /** AES-GCM 加密后的 JSON 对象；null 表示没有环境变量 */
    @Column(columnDefinition = "TEXT")
    private String envEncrypted;

    /** 来源插件 id（市场安装时写入，手动添加为 null） */
    @Column(length = 128)
    private String pluginId;

    /** 来源插件版本，用于更新检查 */
    @Column(length = 32)
    private String pluginVersion;

    @Column(nullable = false)
    private boolean enabled = true;

    @CreatedDate
    private LocalDateTime createTime;

    @LastModifiedDate
    private LocalDateTime updateTime;
}
