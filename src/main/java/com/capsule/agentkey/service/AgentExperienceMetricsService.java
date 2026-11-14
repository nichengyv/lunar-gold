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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** P5-11-2：基于既有 Run、事件、审批与进程 Timer 的当前用户体验指标。 */
@Service
@RequiredArgsConstructor
public class AgentExperienceMetricsService {

    private static final String TOOL_TIMER_NAME = "agent.tool.execution";
    private static final String PROCESS = "PROCESS";

    private final AgentRunRepository runRepository;
    private final AgentRunEventRepository eventRepository;
    private final ToolApprovalRepository approvalRepository;
    private final MeterRegistry meterRegistry;

    @Transactional(readOnly = true)
    public ExperienceMetrics getMetrics(Long userId) {
        List<AgentRun> runs = runRepository.findTop100ByUserIdOrderByCreateTimeDescIdDesc(userId);
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
        for (ToolApproval approval : approvals) {
            if (approval.getCreateTime() == null || approval.getDecisionTime() == null) continue;
            totalWaitMs += Duration.between(approval.getCreateTime(), approval.getDecisionTime()).toMillis();
            sampleCount++;
        }
        Double averageWaitMs = sampleCount == 0 ? null : (double) totalWaitMs / sampleCount;
        return new ApprovalMetrics(averageWaitMs, sampleCount);
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

    public record ApprovalMetrics(Double averageWaitMs, int sampleCount) {
    }

    public record TokenMetrics(Double averageTotalTokens, long totalTokens, int sampleCount) {
    }
}
