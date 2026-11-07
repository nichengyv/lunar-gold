package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Agent 规则 / Instruction（对标 Cursor .cursorrules + Trae @Rules + Copilot System Prompt）
 *
 * 三层 scope：
 *   GLOBAL  — 用户级，所有项目所有会话生效（编码风格、语言偏好、通用原则）
 *   PROJECT — 项目级，某个项目下所有会话生效（技术栈说明、目录约定）
 *   SESSION — 会话级，只在当前会话生效（角色预设：架构师/审查员/安全专家）
 *
 * 拼接进 system prompt 时的顺序（后者权重更高，可覆盖前者）：
 *   GLOBAL rules → PROJECT rules → SESSION rules → 内置固定骨架 → 项目指令 → RAG → 用户提问
 *
 * builtin=true 的规则是系统预置，用户可以关/开但不能删（保护系统规则）。
 */
@Data
@Entity
@Table(name = "agent_rule", indexes = {
        @Index(name = "idx_ar_scope", columnList = "scope"),
        @Index(name = "idx_ar_project", columnList = "projectId"),
        @Index(name = "idx_ar_user", columnList = "userId"),
        @Index(name = "idx_ar_session", columnList = "sessionId")
})
@EntityListeners(AuditingEntityListener.class)
public class AgentRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则名称（短标签，显示给用户） */
    @Column(nullable = false, length = 80)
    private String name;

    /** 规则描述（给用户看的说明文字） */
    @Column(length = 200)
    private String description;

    /**
     * 规则内容（拼进 system prompt 的文本）。
     * 应该是一段完整的指令，比如"你是一位资深 Java 架构师..."
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 作用域：GLOBAL / PROJECT / SESSION */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleScope scope;

    /** 关联用户（GLOBAL 规则必有，PROJECT/SESSION 可选冗余） */
    @Column
    private Long userId;

    /** 关联项目（PROJECT 规则必有） */
    @Column
    private Long projectId;

    /** 关联会话（SESSION 规则必有） */
    @Column
    private Long sessionId;

    /** 是否启用 */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 内置规则：true=系统预置，用户不可删除，可关/开 */
    @Column(nullable = false)
    private boolean builtin = false;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public enum RuleScope {
        GLOBAL,   // 用户级：所有项目所有会话
        PROJECT,  // 项目级：某个项目下所有会话
        SESSION   // 会话级：仅当前会话
    }
}
