package com.capsule.agentkey.agent.run;

import com.capsule.agentkey.agent.subtask.SubtaskResult;
import com.capsule.agentkey.agent.subtask.SubtaskSpec;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次 Run 的可续跑断点（R7b / P8-01）。
 *
 * <p>断点只在「一轮工具批已全部落地、下一轮模型调用前」写入，包含继续执行所需的
 * 全部循环状态：已发送给模型的完整消息列表、轮次、预算累计与停滞相关计数。
 * 续跑时用它替换初始上下文，避免从用户消息重新开始。
 *
 * <p>不包含：审批挂起态与 ask_user_question 挂起态（R7b 明确只覆盖 RUNNING 崩溃；
 * 若进程在等待审批/用户输入时退出，仍按 P7-01 语义终止，需用户重新发起）。
 *
 * <p>序列化后的字符串无法被 Jackson 反序列化成 {@link ObjectNode} 之外的强类型，
 * 因此 messages 保持 Jackson 节点类型，避免额外的 DTO 转换。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RunCheckpoint {

    /** 已完成的 ReAct 轮次；续跑从下一轮开始，轮次预算按剩余额度计算 */
    private int round;

    /** 已消耗的执行时长（毫秒），续跑时用于时间预算扣减 */
    private long elapsedMs;

    /** 已产生的文件变更；续跑后候选完成仍需验证 */
    private boolean workspaceChanged;

    /** 验证覆盖轮次与尝试次数，续跑后不重复验证已通过的批次 */
    private long changeEpoch;
    private long verifiedEpoch;
    private int verifyAttempts;

    /** 预算告警是否已注入，避免续跑后重复提醒 */
    private boolean budgetHintSent;

    /** 未解决的写失败 / 审批拒绝，参与候选完成的 INCOMPLETE 判定 */
    private boolean unresolvedWriteFailure;
    private boolean unresolvedApprovalRejection;

    /** 验证不可用提示是否已下发 */
    private boolean verifyUnavailableNotified;

    /** 已累计 token，续跑后与本次用量合并落库 */
    private long promptTokens;
    private long completionTokens;

    /** 完整模型消息列表（system + 历史 + 本轮 tool 调用与结果） */
    private List<ObjectNode> messages;

    /** 断点写入时间（ISO 字符串），仅用于排查；用字符串避免依赖 ObjectMapper 的 JSR310 模块 */
    private String savedAt;

    /**
     * R7c：尚未完成的委派断点。
     *
     * <p>父 Run 在启动子任务前与每个子任务终结后写入；全部子任务结果与
     * {@code delegate_subtasks} 的 tool 响应进入权威消息后清除。
     * 续跑时据此复用已验收成功的子 Run、恢复可续跑子 Run，并从下一个未完成下标继续。
     */
    private PendingDelegation pendingDelegation;

    /** 委派执行进度：完整规格、已产生结果、下一子任务下标与原始 toolCallId */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingDelegation {
        private String delegationId;
        private List<SubtaskSpec> specs;
        private int nextIndex;
        private List<SubtaskResult> results;
        private String toolCallId;
    }
}
