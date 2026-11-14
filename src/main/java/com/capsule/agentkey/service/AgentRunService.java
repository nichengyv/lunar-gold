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
                RunState.SUCCEEDED,          // 正常完成
                RunState.FAILED,             // 异常终止
                RunState.CANCELLED));        // 用户停止
        m.put(RunState.WAITING_APPROVAL, Set.of(
                RunState.RUNNING,           // 批准
                RunState.CANCELLED,          // 拒绝 / 超时
                RunState.FAILED));           // 审批等待期间外层异常
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

    /** 旧进程中的执行线程已消失，QUEUED/RUNNING 不能伪装成仍可恢复；审批记录单独持久化并保留。 */
    @PostConstruct
    @Transactional
    public void recoverAfterRestart() {
        List<AgentRun> stale = runRepo.findByStateIn(List.of(RunState.QUEUED, RunState.RUNNING));
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
                List.of(RunState.RUNNING, RunState.WAITING_APPROVAL));
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
        appendInternal(runId, EventType.APPROVAL_RESOLVED, payload);
        return transition(runId, RunState.RUNNING, null, null);
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
        Integer maxSeq = eventRepo.findMaxSeqByRunId(runId);
        int nextSeq = (maxSeq == null ? 0 : maxSeq) + 1;
        AgentRunEvent ev = new AgentRunEvent();
        ev.setRunId(runId);
        ev.setSeq(nextSeq);
        ev.setEventType(type);
        ev.setPayload(payload == null ? null : writeJson(payload));
        eventRepo.save(ev);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 事件写 JSON 失败不应该影响主流程——降级为 toString，避免 DB 写入失败
            log.warn("事件 payload 序列化失败，降级为 toString: {}", e.getMessage());
            return String.valueOf(value);
        }
    }
}
