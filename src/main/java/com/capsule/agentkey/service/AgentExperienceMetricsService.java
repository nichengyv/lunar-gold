package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.ToolApproval;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.capsule.agentkey.repository.ToolApprovalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** P5-11-2：基于既有 Run、事件、审批与进程 Timer 的当前用户体验指标。 */
@Service
@RequiredArgsConstructor
public class AgentExperienceMetricsService {

    private static final String TOOL_TIMER_NAME = "agent.tool.execution";
    private static final String PROCESS = "PROCESS";
    /** P6-05b：自动来源不是人为入口，批准率不适用 */
    private static final Set<String> AUTO_SOURCES = Set.of(
            ToolApprovalRegistry.DecisionSource.TIMEOUT.name(),
            ToolApprovalRegistry.DecisionSource.SESSION_STOP.name(),
            ToolApprovalRegistry.DecisionSource.SYSTEM.name());

    private final AgentRunRepository runRepository;
    private final AgentRunEventRepository eventRepository;
    private final ToolApprovalRepository approvalRepository;
    private final MeterRegistry meterRegistry;

    @Transactional(readOnly = true)
    public ExperienceMetrics getMetrics(Long userId) {
        List<AgentRun> runs = runRepository.findTop100ByUserIdAndParentRunIdIsNullOrderByCreateTimeDescIdDesc(userId);
        return new ExperienceMetrics(
                Instant.now().toString(),
                runs.size(),
                firstTokenMetrics(runs),
                toolMetrics(userId),
                approvalMetrics(userId),
                tokenMetrics(runs));
    }

    private FirstTokenMetrics firstTokenMetrics(List<AgentRun> runs) {
        if (runs.isEmpty()) return new FirstTokenMetrics(null, 0);

        List<Long> runIds = runs.stream().map(AgentRun::getId).toList();
        Map<Long, LocalDateTime> firstChunkTimes = new HashMap<>();
        for (AgentRunEventRepository.FirstModelChunkTime row
                : eventRepository.findFirstModelChunkTimes(runIds)) {
            firstChunkTimes.put(row.getRunId(), row.getFirstChunkTime());
        }

        List<Long> latencies = new ArrayList<>();
        for (AgentRun run : runs) {
            LocalDateTime firstChunkTime = firstChunkTimes.get(run.getId());
            if (run.getStartTime() == null || firstChunkTime == null) continue;
            latencies.add(Duration.between(run.getStartTime(), firstChunkTime).toMillis());
        }
        if (latencies.isEmpty()) return new FirstTokenMetrics(null, 0);

        latencies.sort(Long::compareTo);
        int rank = (int) Math.ceil(latencies.size() * 0.95);
        return new FirstTokenMetrics(latencies.get(rank - 1), latencies.size());
    }

    private ToolMetrics toolMetrics(Long userId) {
        long totalCount = 0;
        long failedCount = 0;
        for (Timer timer : meterRegistry.find(TOOL_TIMER_NAME).timers()) {
            if (!userId.toString().equals(timer.getId().getTag("userId"))) continue;
            long count = timer.count();
            totalCount += count;
            if (!"success".equals(timer.getId().getTag("result"))) {
                failedCount += count;
            }
        }
        Double failureRate = totalCount == 0 ? null : (double) failedCount / totalCount;
        return new ToolMetrics(failureRate, totalCount, failedCount, PROCESS);
    }

    private ApprovalMetrics approvalMetrics(Long userId) {
        List<ToolApproval> approvals = approvalRepository
                .findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc(userId);
        long totalWaitMs = 0;
        int sampleCount = 0;
        Map<String, EntryAccumulator> groups = new LinkedHashMap<>();
        for (ToolApproval approval : approvals) {
            if (approval.getCreateTime() == null || approval.getDecisionTime() == null) continue;
            long waitMs = Duration.between(approval.getCreateTime(), approval.getDecisionTime()).toMillis();
            totalWaitMs += waitMs;
            sampleCount++;
            String entry = entryOf(approval);
            groups.computeIfAbsent(entry + '|' + (approval.getClientId() == null ? "" : approval.getClientId()),
                            key -> new EntryAccumulator(entry, approval.getClientId()))
                    .add(approval.getState(), waitMs);
        }
        Double averageWaitMs = sampleCount == 0 ? null : (double) totalWaitMs / sampleCount;
        return new ApprovalMetrics(averageWaitMs, sampleCount, entryMetrics(groups));
    }

    /** P6-05b：入口判定优先级为「自动来源 → 客户端 → Web → 未知」，历史记录不猜测为 Web。 */
    private String entryOf(ToolApproval approval) {
        String source = approval.getDecisionSource();
        if (source != null && AUTO_SOURCES.contains(source)) return "AUTO";
        if (approval.getClientId() != null) return "CLIENT";
        if (ToolApprovalRegistry.DecisionSource.USER.name().equals(source)
                || ToolApprovalRegistry.DecisionSource.BATCH.name().equals(source)) return "WEB";
        return "UNKNOWN";
    }

    private List<ApprovalEntryMetrics> entryMetrics(Map<String, EntryAccumulator> groups) {
        List<ApprovalEntryMetrics> entries = new ArrayList<>(groups.size());
        for (EntryAccumulator group : groups.values()) {
            entries.add(group.toMetrics());
        }
        entries.sort(Comparator.comparingInt(ApprovalEntryMetrics::totalCount).reversed()
                .thenComparing(ApprovalEntryMetrics::entry)
                .thenComparing(ApprovalEntryMetrics::clientId,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        return entries;
    }

    private static final class EntryAccumulator {
        private final String entry;
        private final String clientId;
        private int totalCount;
        private int approvedCount;
        private int rejectedCount;
        private long totalDecisionMs;

        private EntryAccumulator(String entry, String clientId) {
            this.entry = entry;
            this.clientId = clientId;
        }

        private void add(String state, long decisionMs) {
            totalCount++;
            if ("APPROVED".equals(state)) approvedCount++;
            else if ("REJECTED".equals(state)) rejectedCount++;
            totalDecisionMs += decisionMs;
        }

        private ApprovalEntryMetrics toMetrics() {
            int decidedByHuman = approvedCount + rejectedCount;
            Double approvalRate = "AUTO".equals(entry) || decidedByHuman == 0
                    ? null
                    : (double) approvedCount / decidedByHuman;
            Double averageDecisionMs = totalCount == 0 ? null : (double) totalDecisionMs / totalCount;
            return new ApprovalEntryMetrics(entry, clientId, totalCount, approvedCount, rejectedCount,
                    approvalRate, averageDecisionMs);
        }
    }

    private TokenMetrics tokenMetrics(List<AgentRun> runs) {
        long totalTokens = 0;
        int sampleCount = 0;
        for (AgentRun run : runs) {
            if (run.getTotalTokens() <= 0) continue;
            totalTokens += run.getTotalTokens();
            sampleCount++;
        }
        Double averageTotalTokens = sampleCount == 0 ? null : (double) totalTokens / sampleCount;
        return new TokenMetrics(averageTotalTokens, totalTokens, sampleCount);
    }

    public record ExperienceMetrics(
            String generatedAt,
            int windowRuns,
            FirstTokenMetrics firstToken,
            ToolMetrics tool,
            ApprovalMetrics approval,
            TokenMetrics tokens) {
    }

    public record FirstTokenMetrics(Long p95Ms, int sampleCount) {
    }

    /** totalCount/failedCount 均为当前进程累计，不属于最近 100 Run 窗口。 */
    public record ToolMetrics(
            Double failureRate,
            long totalCount,
            long failedCount,
            String scope) {
    }

    /** P6-05b：按入口拆分的审批指标；AUTO 的 approvalRate 恒为 null */
    public record ApprovalMetrics(Double averageWaitMs, int sampleCount, List<ApprovalEntryMetrics> byEntry) {
    }

    /** entry 为 WEB / CLIENT / AUTO / UNKNOWN；clientId 仅在 CLIENT 组非空 */
    public record ApprovalEntryMetrics(
            String entry,
            String clientId,
            int totalCount,
            int approvedCount,
            int rejectedCount,
            Double approvalRate,
            Double averageDecisionMs) {
    }

    public record TokenMetrics(Double averageTotalTokens, long totalTokens, int sampleCount) {
    }
}
