package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 待审查的文件变更：Agent 写文件时先落盘 + 记录 oldContent/newContent，
 * 前端输入框上方浮现审查卡片，用户可逐个/批量撤销或保留。
 *
 * 状态流转：PENDING → REVERTED / ACCEPTED
 * - PENDING：刚生成，还没处理
 * - REVERTED：用户点了撤销，文件已回到 oldContent
 * - ACCEPTED：用户点了保留，状态锁死不能再撤销
 */
@Data
@Entity
@Table(name = "pending_file_change", indexes = {
        @Index(name = "idx_pfc_project", columnList = "projectId"),
        @Index(name = "idx_pfc_session", columnList = "sessionId"),
        @Index(name = "idx_pfc_turn", columnList = "sessionId,turnId"),
        @Index(name = "idx_pfc_status", columnList = "status")
})
@EntityListeners(AuditingEntityListener.class)
public class PendingFileChange {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    /** 来源会话：可空（MCP 工具可能没有会话关联） */
    private Long sessionId;

    /** 第六十八批 A3：本轮执行 id，同一轮多次改动共享，用于「撤回本轮」 */
    @Column(length = 64)
    private String turnId;

    @Column(nullable = false)
    private Long userId;

    /** 相对工作区根目录的路径，'/' 分隔 */
    @Column(nullable = false, length = 500)
    private String filePath;

    /** create / modify */
    @Column(nullable = false, length = 20)
    private String action;

    /** 产生变更的工具名：write_file / edit_file / mcp_* */
    @Column(nullable = false, length = 60)
    private String source;

    /** agent 写之前的文件内容（新文件时为 null） */
    @Column(columnDefinition = "TEXT")
    private String oldContent;

    /** agent 写完后的文件内容（撤销会把文件改回 oldContent） */
    @Column(columnDefinition = "TEXT")
    private String newContent;

    /** 逐行 diff（"+ "-" "@@" 前缀） */
    @Column(columnDefinition = "TEXT")
    private String diffText;

    private Integer additions;
    private Integer deletions;

    /** PENDING / REVERTED / ACCEPTED */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @CreatedDate
    private LocalDateTime createTime;
}
