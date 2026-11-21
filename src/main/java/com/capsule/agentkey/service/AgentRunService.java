package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRun.RunState;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.AgentRunEvent.EventType;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Agent 运行状态机 + 事件流门面。
 *
 * <p>所有公开的状态迁移方法都：
 * <ol>
 *   <li>校验当前状态是否允许迁移（{@link #transitionAllowed}）；
 *   <li>更新 {@link AgentRun#state} 与时间戳；
 *   <li>持久化一条 {@link EventType#STATE_CHANGED} 事件；
 *   <li>返回更新后的 run。
 * </ol>
 *
 * <p>内部转移表是 {@link #TRANSITIONS} 的不可变 Map，任何非法迁移都被防御性拒绝。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunService {

    private final AgentRunRepository runRepo;
    private final AgentRunEventRepository eventRepo;
    private final ObjectMapper objectMapper;
    /** R1：事件与计划快照的唯一写入入口；状态迁移的审计事件也统一走它 */
    private final com.capsule.agentkey.agent.run.RunEventStore runEventStore;

    /**
     * 合法状态转移表。key = 当前状态；value = 允许迁移到的目标状态集合。
     * 任何不在此表中或目标集合里不存在的迁移都是非法的。
     */
    private static final Map<RunState, Set<RunState>> TRANSITIONS;

    static {
        Map<RunState, Set<RunState>> m = new EnumMap<>(RunState.class);
        m.put(RunState.QUEUED, Set.of(
                RunState.RUNNING,           // 启动
                RunState.CANCELLED,          // 启动前取消
                RunState.FAILED));           // 启动前异常（例如幂等冲突后拒绝调度）
        m.put(RunState.RUNNING, Set.of(
                RunState.WAITING_APPROVAL,   // 工具调用需要审批
                RunState.WAITING_USER_INPUT, // P7-06：agent 暂停等用户回答决策询问
                RunState.SUCCEEDED,          // 正常完成
                RunState.FAILED,             // 异常终止
                RunState.CANCELLED));        // 用户停止
        m.put(RunState.WAITING_APPROVAL, Set.of(
                RunState.RUNNING,           // 批准
                RunState.CANCELLED,          // 拒绝 / 超时
                RunState.FAILED));           // 审批等待期间外层异常
        m.put(RunState.WAITING_USER_INPUT, Set.of(
                RunState.RUNNING,           // 用户回答，继续执行
                RunState.CANCELLED,          // 用户停止
                RunState.FAILED));           // 等待期间外层异常
        // 终态不再迁移
        m.put(RunState.SUCCEEDED, Set.of());
        m.put(RunState.FAILED, Set.of());
        m.put(RunState.CANCELLED, Set.of());
        TRANSITIONS = Collections.unmodifiableMap(m);
    }

    /** 查询当前状态是否允许迁移到 target */
    public static boolean transitionAllowed(RunState current, RunState target) {
        if (current == null || target == null) return false;
        Set<RunState> allowed = TRANSITIONS.get(current);
        return allowed != null && allowed.contains(target);
    }

    /**
     * 旧进程中的执行线程已消失，QUEUED / RUNNING / WAITING_APPROVAL / WAITING_USER_INPUT 都不能伪装成仍可恢复。
     * 等待审批或等待用户输入的 Run 同样依赖进程内的 ReAct 线程，重启后恢复也无法继续执行，故一并终止；
     * 残留 PENDING 审批由 {@link ToolApprovalRegistry#restorePersistedApprovals()} 落终态。
     */
    @PostConstruct
    @Transactional
    public void recoverAfterRestart() {
        List<AgentRun> stale = runRepo.findByStateIn(List.of(
                RunState.QUEUED, RunState.RUNNING, RunState.WAITING_APPROVAL, RunState.WAITING_USER_INPUT));
        for (AgentRun run : stale) {
            run.setState(RunState.FAILED);
            run.setErrorCode("PROCESS_RESTARTED");
            run.setErrorMessage("应用重启，原执行线程已终止");
            run.setEndTime(LocalDateTime.now());
            runRepo.save(run);
            appendInternal(run.getId(), EventType.STATE_CHANGED,
                    Map.of("targetState", RunState.FAILED, "errorCode", "PROCESS_RESTARTED"));
        }
        if (!stale.isEmpty()) log.warn("应用重启，将 {} 条中断运行标记为 FAILED", stale.size());
    }

    // ---------------------------------------------------------------------
    // 读写
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<AgentRun> findById(Long id) {
        return runRepo.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<AgentRun> findByIdAndUserId(Long id, Long userId) {
        return runRepo.findById(id).filter(r -> r.getUserId().equals(userId));
    }

    @Transactional(readOnly = true)
    public Optional<AgentRun> findByIdempotent(Long userId, String clientRequestId) {
        if (clientRequestId == null) return Optional.empty();
        return runRepo.findByUserIdAndClientRequestId(userId, clientRequestId);
    }

    @Transactional(readOnly = true)
    public List<AgentRun> findRunningByUser(Long userId) {
        return runRepo.findByUserIdAndStateIn(userId,
                List.of(RunState.RUNNING, RunState.WAITING_APPROVAL, RunState.WAITING_USER_INPUT));
    }

    /** P5-12-2：会话列表状态点——批量取每个会话最近一条 run 的状态 */
    @Transactional(readOnly = true)
    public Map<Long, String> lastRunStatesBySession(Long userId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> states = new HashMap<>();
        for (AgentRun run : runRepo.findLatestByUserIdAndSessionIdIn(userId, sessionIds)) {
            if (run.getSessionId() != null) {
                states.put(run.getSessionId(), run.getState().name());
            }
        }
        return states;
    }

    @Transactional(readOnly = true)
    public List<AgentRunEvent> findEvents(Long runId, Integer sinceSeq) {
        if (sinceSeq == null) {
            return eventRepo.findByRunIdOrderBySeqAsc(runId);
        }
        return eventRepo.findByRunIdAndSeqGreaterThanOrderBySeqAsc(runId, sinceSeq);
    }

    // ---------------------------------------------------------------------
    // 创建 / 幂等
    // ---------------------------------------------------------------------

    public record CreateResult(AgentRun run, boolean created) {
    }

    /**
     * 创建一条 QUEUED 状态的 run。
     * 如果给出 {@code clientRequestId} 且用户下已有同幂等键的 run，返回旧记录和 created=false，
     * 调用方据此阻止同一请求再次进入执行链。
     */
    @Transactional
    public CreateResult create(Long userId,
                               Long sessionId,
                               Long projectId,
                               String clientRequestId,
                               String requestJson) {
        if (clientRequestId != null) {
            Optional<AgentRun> existing = runRepo.findByUserIdAndClientRequestId(userId, clientRequestId);
            if (existing.isPresent()) {
                log.info("幂等命中：userId={}, clientRequestId={}, existingRun={}", userId, clientRequestId, existing.get().getId());
                return new CreateResult(existing.get(), false);
            }
        }
        AgentRun run = new AgentRun();
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setProjectId(projectId);
        run.setClientRequestId(clientRequestId);
        run.setState(RunState.QUEUED);
        run.setRequestJson(requestJson);
        AgentRun saved = runRepo.save(run);
        log.info("新建 run：id={}, userId={}, state=QUEUED", saved.getId(), userId);
        appendInternal(saved.getId(), EventType.STATE_CHANGED, Map.of("targetState", RunState.QUEUED));
        return new CreateResult(saved, true);
    }

    // ---------------------------------------------------------------------
    // 状态迁移
    // ---------------------------------------------------------------------

    /** QUEUED → RUNNING */
    @Transactional
    public AgentRun start(Long runId) {
        return transition(runId, RunState.RUNNING, null, null);
    }

    /** RUNNING → WAITING_APPROVAL；payload 里放 approvalId 和工具名供前端展示 */
    @Transactional
    public AgentRun pauseForApproval(Long runId, String approvalId, String toolName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approvalId);
        payload.put("tool", toolName);
        appendInternal(runId, EventType.APPROVAL_REQUESTED, payload);
        return transition(runId, RunState.WAITING_APPROVAL, null, null);
    }

    /** WAITING_APPROVAL → RUNNING（批准）；payload 带决策来源与批次，供 ChangeSet 与历史对账 */
    @Transactional
    public AgentRun resumeAfterApproval(Long runId, String decision, String source, String batchId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", decision);
        if (source != null) payload.put("source", source);
        if (batchId != null) payload.put("batchId", batchId);
        // P6-01：客户端令牌发起的运行补审计字段；Web / 云端发起时保持原样，不带该字段
        String clientId = clientIdOf(runId);
        if (clientId != null) payload.put("clientId", clientId);
        appendInternal(runId, EventType.APPROVAL_RESOLVED, payload);
        return transition(runId, RunState.RUNNING, null, null);
    }

    // -----------------------------------------------------------------
    // P7-06：ask_user_question（执行中决策询问）
    // -----------------------------------------------------------------

    /** RUNNING → WAITING_USER_INPUT；agent 暂停等用户回答决策询问 */
    @Transactional
    public AgentRun pauseForUserInput(Long runId, String questionId, String question,
                                       String header, List<Map<String, Object>> options, boolean multiSelect) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questionId", questionId);
        payload.put("question", question);
        if (header != null) payload.put("header", header);
        if (options != null && !options.isEmpty()) payload.put("options", options);
        payload.put("multiSelect", multiSelect);
        appendInternal(runId, EventType.USER_QUESTION, payload);
        return transition(runId, RunState.WAITING_USER_INPUT, null, null);
    }

    /** WAITING_USER_INPUT → RUNNING；payload 带用户回答（selected + 可选 custom） */
    @Transactional
    public AgentRun resumeAfterUserInput(Long runId, String questionId,
                                          List<String> selected, String custom) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questionId", questionId);
        payload.put("selected", selected != null ? selected : List.of());
        if (custom != null && !custom.isBlank()) payload.put("custom", custom);
        appendInternal(runId, EventType.USER_ANSWERED, payload);
        return transition(runId, RunState.RUNNING, null, null);
    }

    /**
     * P7-06：等待超时 / 线程中断时把运行拉回 RUNNING，避免 Run 永久停在 WAITING_USER_INPUT。
     *
     * <p>只有当前仍是 WAITING_USER_INPUT 才迁移：用户已作答、Run 已取消或已终结时保持原状态，
     * 不能覆盖并发路径写入的最终结果。事件记录 {@code USER_ANSWERED + abandoned=true}，
     * 前端重放事件流时据此收起询问卡片。</p>
     */
    @Transactional
    public void abandonUserQuestion(Long runId, String questionId, String reason) {
        AgentRun run = runRepo.findById(runId).orElse(null);
        if (run == null || run.getState() != RunState.WAITING_USER_INPUT) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questionId", questionId);
        payload.put("abandoned", true);
        payload.put("reason", reason == null ? "UNKNOWN" : reason);
        appendInternal(runId, EventType.USER_ANSWERED, payload);
        transition(runId, RunState.RUNNING, null, null);
    }

    /** 从运行请求快照里读 clientId；解析失败按「非客户端发起」处理，不影响审批主流程 */
    private String clientIdOf(Long runId) {
        String requestJson = runRepo.findById(runId).map(AgentRun::getRequestJson).orElse(null);
        if (requestJson == null || requestJson.isBlank()) {
            return null;
        }
        try {
            var clientId = objectMapper.readTree(requestJson).get("clientId");
            return clientId == null || clientId.isNull() ? null : clientId.asText();
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** RUNNING → SUCCEEDED；可带 resultJson */
    @Transactional
    public AgentRun succeed(Long runId, String resultJson) {
        AgentRun run = transition(runId, RunState.SUCCEEDED, null, null);
        if (resultJson != null) {
            run.setResultJson(resultJson);
            run = runRepo.save(run);
        }
        return run;
    }

    /** RUNNING / WAITING_APPROVAL → FAILED；带标准化 errorCode + 人类可读 errorMessage */
    @Transactional
    public AgentRun fail(Long runId, String errorCode, String errorMessage) {
        AgentRun run = transition(runId, RunState.FAILED, null, null);
        run.setErrorCode(errorCode);
        run.setErrorMessage(errorMessage);
        return runRepo.save(run);
    }

    /** 任意非终态 → CANCELLED；用户停止、审批超时、系统中止都走这里 */
    @Transactional
    public AgentRun cancel(Long runId, String reason) {
        appendInternal(runId, EventType.CANCELED,
                reason == null ? Map.of() : Map.of("reason", reason));
        return transition(runId, RunState.CANCELLED, null, null);
    }

    // ---------------------------------------------------------------------
    // 聊天流收尾：已是终态则跳过，避免重复迁移
    // ---------------------------------------------------------------------

    /** RUNNING / WAITING_APPROVAL → SUCCEEDED；已终态直接跳过。异常由调用方兜底。 */
    @Transactional
    public void succeedIfActive(Long runId, String resultJson) {
        runRepo.findById(runId)
                .filter(run -> !AgentRun.isTerminal(run.getState()))
                .ifPresent(run -> succeed(runId, resultJson));
    }

    /** 非终态 → FAILED；已终态直接跳过。异常由调用方兜底。 */
    @Transactional
    public void failIfActive(Long runId, String errorCode, String errorMessage) {
        runRepo.findById(runId)
                .filter(run -> !AgentRun.isTerminal(run.getState()))
                .ifPresent(run -> fail(runId, errorCode, errorMessage));
    }

    /** 非终态 → CANCELLED；已终态直接跳过。异常由调用方兜底。 */
    @Transactional
    public void cancelIfActive(Long runId, String reason) {
        runRepo.findById(runId)
                .filter(run -> !AgentRun.isTerminal(run.getState()))
                .ifPresent(run -> cancel(runId, reason));
    }

    // ---------------------------------------------------------------------
    // P4-07：token 用量
    // ---------------------------------------------------------------------

    /**
     * 累加本 run 的 token 用量。
     * 多轮工具调用会分多次上报，这里必须累加而不是覆盖；
     * 落库失败不能影响对话收尾，调用方负责兜底。
     */
    @Transactional
    public void recordUsage(Long runId, long promptTokens, long completionTokens) {
        if (promptTokens <= 0 && completionTokens <= 0) {
            return;
        }
        runRepo.findById(runId).ifPresent(run -> {
            run.setPromptTokens(run.getPromptTokens() + promptTokens);
            run.setCompletionTokens(run.getCompletionTokens() + completionTokens);
            run.setTotalTokens(run.getPromptTokens() + run.getCompletionTokens());
            runRepo.save(run);
        });
    }

    /** 会话维度用量汇总，供统计面板展示 */
    @Transactional(readOnly = true)
    public Map<String, Long> usageBySession(Long sessionId, Long userId) {
        Map<String, Long> usage = new java.util.LinkedHashMap<>();
        usage.put("promptTokens", nullSafe(runRepo.sumPromptTokensBySession(sessionId, userId)));
        usage.put("completionTokens", nullSafe(runRepo.sumCompletionTokensBySession(sessionId, userId)));
        usage.put("totalTokens", nullSafe(runRepo.sumTotalTokensBySession(sessionId, userId)));
        return usage;
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }

    // ---------------------------------------------------------------------
    // 事件追加（工具调用、模型 chunk 等非状态事件）
    // ---------------------------------------------------------------------

    @Transactional
    public void appendEvent(Long runId, EventType type, Map<String, Object> payload) {
        appendInternal(runId, type, payload);
    }

    // ---------------------------------------------------------------------
    // 内部：带校验的状态迁移
    // ---------------------------------------------------------------------

    private AgentRun transition(Long runId, RunState target, String errorCode, String errorMessage) {
        AgentRun run = runRepo.findById(runId).orElseThrow(() ->
                new IllegalArgumentException("run 不存在：id=" + runId));
        RunState from = run.getState();
        if (!transitionAllowed(from, target)) {
            throw new IllegalStateException(
                    String.format("非法状态迁移：runId=%d, from=%s, target=%s", runId, from, target));
        }
        run.setState(target);
        LocalDateTime now = LocalDateTime.now();
        if (target == RunState.RUNNING && run.getStartTime() == null) {
            run.setStartTime(now);
        }
        if (AgentRun.isTerminal(target)) {
            run.setEndTime(now);
        }
        if (errorCode != null) run.setErrorCode(errorCode);
        if (errorMessage != null) run.setErrorMessage(errorMessage);
        AgentRun saved = runRepo.save(run);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromState", from);
        payload.put("targetState", target);
        if (errorCode != null) payload.put("errorCode", errorCode);
        appendInternal(runId, EventType.STATE_CHANGED, payload);
        return saved;
    }

    private void appendInternal(Long runId, EventType type, Map<String, Object> payload) {
        // R1：seq 分配与事件插入都收敛到 RunEventStore（行锁 + 唯一约束兜底），
        // 避免并发追加读到同一个 max(seq)
        runEventStore.append(runId, type, payload);
    }

    /**
     * R1：读取 Run 已持久化的任务计划快照。
     * 续跑、断线重放和前端恢复都从这里取权威版本；没有计划时返回空列表。
     */
    @Transactional(readOnly = true)
    public List<com.capsule.agentkey.agent.run.PlanStep> loadPlan(Long runId) {
        return runEventStore.loadPlan(runId);
    }

    // ---------------------------------------------------------------------
    // R7b（P8-01）：断点快照与显式续跑
    // ---------------------------------------------------------------------

    /** 写入最近一份可续跑断点；持久化失败由调用方降级为日志，不中断主循环 */
    @Transactional
    public void saveCheckpoint(Long runId, com.capsule.agentkey.agent.run.RunCheckpoint checkpoint) {
        runEventStore.saveCheckpoint(runId, checkpoint);
    }

    /** 读取可续跑断点；没有或损坏时返回空，调用方据此拒绝续跑 */
    @Transactional(readOnly = true)
    public Optional<com.capsule.agentkey.agent.run.RunCheckpoint> loadCheckpoint(Long runId) {
        return runEventStore.loadCheckpoint(runId);
    }

    /** 当前 Run 是否具备显式续跑条件：已被重启中断，且留下过断点 */
    public static boolean isResumable(AgentRun run) {
        return run != null
                && run.getState() == RunState.FAILED
                && "PROCESS_RESTARTED".equals(run.getErrorCode())
                && run.getCheckpointJson() != null
                && !run.getCheckpointJson().isBlank();
    }

    /**
     * FAILED(PROCESS_RESTARTED) → RUNNING：用户显式点击「继续执行」时调用。
     *
     * <p>终态不迁移是常规不变量；这里是唯一的显式例外，且有双重前置条件：
     * 失败原因是进程重启，并且确实存在可续跑断点。普通失败（执行异常、预算超限、
     * 工具错误）仍然保持终态，不能被续跑。
     *
     * <p>不走 {@link #transition} 是因为静态转移表对 FAILED 保持空集合；
     * 这里把「重启中断」视为可恢复的中间态，并显式写一条 STATE_CHANGED 事件留痕。
     */
    @Transactional
    public AgentRun resumeInterrupted(Long runId) {
        AgentRun run = runRepo.findById(runId).orElseThrow(() ->
                new IllegalArgumentException("run 不存在：id=" + runId));
        if (!isResumable(run)) {
            throw new IllegalStateException(
                    String.format("运行不可续跑：runId=%d, state=%s, errorCode=%s",
                            runId, run.getState(), run.getErrorCode()));
        }
        RunState from = run.getState();
        run.setState(RunState.RUNNING);
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.setEndTime(null);
        if (run.getStartTime() == null) {
            run.setStartTime(LocalDateTime.now());
        }
        AgentRun saved = runRepo.save(run);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromState", from);
        payload.put("targetState", RunState.RUNNING);
        payload.put("resumed", true);
        appendInternal(runId, EventType.STATE_CHANGED, payload);
        log.info("显式续跑运行：runId={}, from=FAILED/PROCESS_RESTARTED", runId);
        return saved;
    }
}
