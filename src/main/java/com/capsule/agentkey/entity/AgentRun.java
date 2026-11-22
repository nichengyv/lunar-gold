package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Agent 一次运行的生命周期根实体。
 *
 * <p>状态流转由 {@code AgentRunService} 控制，DB 层只存储状态值。
 * 状态机完整转移表：
 *
 * <pre>
 * QUEUED       → RUNNING           : 启动线程
 * RUNNING      → WAITING_APPROVAL  : 工具调用需要审批
 * RUNNING      → SUCCEEDED         : 正常完成
 * RUNNING      → FAILED            : 异常终止
 * RUNNING      → CANCELLED         : 用户停止 / 超时 / 审批超时
 * WAITING_APPROVAL → RUNNING       : 批准
 * WAITING_APPROVAL → CANCELLED     : 拒绝 / 超时
 * 任何终态 (SUCCEEDED/FAILED/CANCELLED) 不再迁移
 * </pre>
 *
 * <p>{@link #clientRequestId} 提供幂等入口，唯一约束在 DB 层。
 */
@Data
@Entity
@Table(name = "agent_run")
@EntityListeners(AuditingEntityListener.class)
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 所属聊天会话；桌面端直接工具运行可为空 */
    private Long sessionId;

    /** 所属项目；桌面端跨项目执行可为空 */
    private Long projectId;

    /** 客户端幂等键；NULL 允许，不参与唯一约束 */
    @Column(length = 128)
    private String clientRequestId;

    /**
     * R7c：父 Run id；顶层 Run 为空，子智能体 Run 指向发起委派的父 Run。
     *
     * <p>顶层历史、最近会话 Run、运行中列表与用量查询都只统计 parentRunId 为空的 Run。
     */
    private Long parentRunId;

    /** R7c：父 Run 内稳定的子任务标识；与 parentRunId 一起唯一，保证重试不重复建 Run */
    @Column(length = 80)
    private String delegationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunState state;

    /** 请求快照 JSON：prompt、tool policy、参数等 */
    @Column(columnDefinition = "TEXT")
    private String requestJson;

    /** 成功/失败结果 JSON（可选） */
    @Column(columnDefinition = "TEXT")
    private String resultJson;

    /**
     * R1：任务计划快照 JSON（List&lt;PlanStep&gt;）。
     *
     * <p>计划不再只活在工具线程的 ThreadLocal 里：update_plan / finalizePlan 都会写这里，
     * 断线重放、续跑与审计可以从数据库恢复同一份计划。
     * 不参与默认 JSON 序列化，避免 Run 列表接口被计划正文撑大；前端通过事件流获取。
     */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String planJson;

    /**
     * R7b（P8-01）：最近一个可续跑断点的 JSON 快照（{@code RunCheckpoint}）。
     *
     * <p>只在「一轮工具批已全部落地、下一轮模型调用前」写入，只保留最新一份。
     * 事件流里的 {@code CHECKPOINT} 事件记录轮次与字节数，这里存完整消息快照；
     * 续跑时以它作为初始模型上下文，避免从头再来。同样不参与默认 JSON 序列化。
     */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String checkpointJson;

    /** 失败时的标准化错误码，与 P3-06 诊断卡片共用枚举 */
    @Column(length = 40)
    private String errorCode;

    /** 失败时的人类可读描述 */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** P4-07：本 run 累计输入 token */
    private long promptTokens;

    /** P4-07：本 run 累计输出 token */
    private long completionTokens;

    /** P4-07：本 run 累计总 token（输入 + 输出） */
    private long totalTokens;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @CreatedDate
    private LocalDateTime createTime;

    @LastModifiedDate
    private LocalDateTime updateTime;

    /** 运行状态 */
    public enum RunState {
        /** 已入库，等待调度 */
        QUEUED,
        /** ReAct 线程正在运行 */
        RUNNING,
        /** 等待用户审批工具调用 */
        WAITING_APPROVAL,
        /** P7-06：等待用户回答 agent 提出的决策询问 */
        WAITING_USER_INPUT,
        /** 正常完成 */
        SUCCEEDED,
        /** 异常终止 */
        FAILED,
        /** 用户停止 / 审批超时 / 放弃 */
        CANCELLED
    }

    /** 终态（任何终态不再迁移） */
    public static boolean isTerminal(RunState state) {
        return state == RunState.SUCCEEDED
                || state == RunState.FAILED
                || state == RunState.CANCELLED;
    }
}
