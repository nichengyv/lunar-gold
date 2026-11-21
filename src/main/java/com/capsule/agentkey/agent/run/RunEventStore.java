package com.capsule.agentkey.agent.run;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.AgentRunEvent.EventType;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Run 事件流与计划快照的唯一写入入口（R1）。
 *
 * <p>重构前 {@code AgentRunService.appendInternal} 直接 {@code findMaxSeqByRunId + 1}，
 * 多线程同时追加（ReAct 线程 + 审批 HTTP 线程）会读到同一个 max，后写入者触发
 * {@code uk_run_event_seq} 唯一约束并把整个事务打挂。现在追加前先对 Run 行加排他锁，
 * seq 分配与事件插入在同一事务内完成，唯一约束保留为兜底不变量。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunEventStore {

    private final AgentRunRepository runRepo;
    private final AgentRunEventRepository eventRepo;
    private final ObjectMapper objectMapper;

    /**
     * 追加一条事件，返回分配到的 seq。
     *
     * <p>调用方必须处于事务中（通常由 {@code AgentRunService} 的迁移方法开启）；
     * 行锁持有到事务提交，因此同一 Run 的并发追加会串行拿到连续的 seq。
     */
    @Transactional
    public int append(Long runId, EventType type, Map<String, Object> payload) {
        runRepo.lockForEventAppend(runId)
                .orElseThrow(() -> new IllegalArgumentException("run 不存在，无法追加事件：id=" + runId));
        Integer maxSeq = eventRepo.findMaxSeqByRunId(runId);
        int nextSeq = (maxSeq == null ? 0 : maxSeq) + 1;

        AgentRunEvent event = new AgentRunEvent();
        event.setRunId(runId);
        event.setSeq(nextSeq);
        event.setEventType(type);
        event.setPayload(payload == null ? null : writeJson(payload));
        eventRepo.save(event);
        return nextSeq;
    }

    /**
     * 持久化任务计划快照，并追加 {@link EventType#PLAN_UPDATED} 事件。
     *
     * <p>只更新 plan_json 列，不整行 save，避免把并发状态迁移写入的 state 覆盖回旧值。
     */
    @Transactional
    public void savePlan(Long runId, List<PlanStep> steps) {
        List<PlanStep> snapshot = steps == null ? List.of() : List.copyOf(steps);
        int updated = runRepo.updatePlanJson(runId, writeJson(snapshot));
        if (updated == 0) {
            throw new IllegalArgumentException("run 不存在，无法保存计划：id=" + runId);
        }
        append(runId, EventType.PLAN_UPDATED, Map.of("steps", snapshot));
    }

    /** 读取已持久化的计划快照；没有、解析失败都返回空列表，不阻塞新一轮执行 */
    @Transactional(readOnly = true)
    public List<PlanStep> loadPlan(Long runId) {
        String json = runRepo.findById(runId).map(AgentRun::getPlanJson).orElse(null);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<PlanStep>>() {});
        } catch (Exception e) {
            log.warn("计划快照解析失败，按空计划处理：runId={}, error={}", runId, e.getMessage());
            return List.of();
        }
    }

    /** 单份断点快照的字节上限；超过时保留上一份可用断点，避免把 agent_run 撑爆 */
    static final int MAX_CHECKPOINT_CHARS = 4_000_000;

    /**
     * 写入可续跑断点（R7b）。
     *
     * <p>只更新 {@code agent_run.checkpoint_json} 一列，并在事件流追加一条轻量
     * {@link EventType#CHECKPOINT} 记录轮次与字节数；超过上限时跳过本次写入并保留上一份断点，
     * 让续跑退回到更早的安全点而不是失败。
     */
    @Transactional
    public void saveCheckpoint(Long runId, RunCheckpoint checkpoint) {
        if (checkpoint == null) {
            return;
        }
        if (checkpoint.getSavedAt() == null) {
            checkpoint.setSavedAt(java.time.LocalDateTime.now().toString());
        }
        String json = writeJson(checkpoint);
        if (json.length() > MAX_CHECKPOINT_CHARS) {
            log.warn("断点快照超过上限，保留上一份断点：runId={}, chars={}, limit={}",
                    runId, json.length(), MAX_CHECKPOINT_CHARS);
            return;
        }
        int updated = runRepo.updateCheckpointJson(runId, json);
        if (updated == 0) {
            throw new IllegalArgumentException("run 不存在，无法保存断点：id=" + runId);
        }
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("round", checkpoint.getRound());
        meta.put("messages", checkpoint.getMessages() == null ? 0 : checkpoint.getMessages().size());
        meta.put("bytes", json.length());
        append(runId, EventType.CHECKPOINT, meta);
    }

    /** 读取最近一次断点；没有或解析失败返回空，调用方据此拒绝续跑而不是带着残缺上下文执行 */
    @Transactional(readOnly = true)
    public java.util.Optional<RunCheckpoint> loadCheckpoint(Long runId) {
        String json = runRepo.findById(runId).map(AgentRun::getCheckpointJson).orElse(null);
        if (json == null || json.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(objectMapper.readValue(json, RunCheckpoint.class));
        } catch (Exception e) {
            log.warn("断点快照解析失败，按不可续跑处理：runId={}, error={}", runId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // 事件写 JSON 失败不应该影响主流程——降级为 toString，避免 DB 写入失败
            log.warn("事件 payload 序列化失败，降级为 toString: {}", e.getMessage());
            return String.valueOf(value);
        }
    }
}
