package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * AgentRun 运行内发生的离散事件。
 *
 * <p>断线续传时按 {@link #runId} + {@link #seq} 重放；
 * 前端 SSE 订阅可以从最后收到的 seq + 1 开始拉取。
 */
@Data
@Entity
@Table(name = "agent_run_event", uniqueConstraints =
        @UniqueConstraint(name = "uk_run_event_seq", columnNames = {"run_id", "seq"}))
@EntityListeners(AuditingEntityListener.class)
public class AgentRunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    /** 运行内单调递增序号 */
    @Column(nullable = false)
    private Integer seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EventType eventType;

    /** 事件载荷 JSON：状态变更记录 targetState，工具调用记录 tool/arguments，审批记录 approvalId 等 */
    @Column(columnDefinition = "TEXT")
    private String payload;

    @CreatedDate
    private LocalDateTime createTime;

    /** 事件类型（用于前端 SSE 订阅和断线续传重放） */
    public enum EventType {
        /** 状态机迁移：payload.targetState + payload.fromState */
        STATE_CHANGED,
        /** ReAct 循环产出一个模型 chunk */
        MODEL_CHUNK,
        /** 发起工具调用 */
        TOOL_CALL,
        /** 工具调用返回 */
        TOOL_RESULT,
        /** P5-01：本轮加载的提示词版本，payload 为 scenario -> id@version */
        PROMPT_LOADED,
        /** 进入 WAITING_APPROVAL，payload.approvalId + tool */
        APPROVAL_REQUESTED,
        /** 审批被解析，payload.decision (APPROVE/REJECT) */
        APPROVAL_RESOLVED,
        /** P5-13：交付完成判定，与 Run 执行生命周期分开 */
        COMPLETION,
        /** P5-13：重复调用或连续无推进导致的安全停止 */
        NO_PROGRESS,
        /** P5-05：变更文件诊断结果，payload 含文件、行号、严重级与消息 */
        DIAGNOSTICS,
        /** P5-06：项目验证结果，payload 含状态、命令、退出码与审计信息 */
        VERIFY,
        /** 终态前的错误事件 */
        ERROR,
        /** 用户主动取消 */
        CANCELED,
        /** P7-06：agent 暂停等待用户输入，payload 含 questionId/question/header/options/multi_select */
        USER_QUESTION,
        /** P7-06：用户回答了 agent 的询问，payload 含 questionId/selected/custom */
        USER_ANSWERED,
        /** R1：任务计划快照变更，payload.steps 为完整步骤列表（断线重放时重建计划） */
        PLAN_UPDATED,
        /** R7b：可续跑断点已写入，payload 含 round / messages / bytes（完整快照在 agent_run.checkpoint_json） */
        CHECKPOINT
    }
}
