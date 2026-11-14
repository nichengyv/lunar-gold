package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.ToolApproval;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunEventRepository.FirstModelChunkTime;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.capsule.agentkey.repository.ToolApprovalRepository;
import com.capsule.agentkey.service.AgentExperienceMetricsService.ExperienceMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentExperienceMetricsServiceTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 11, 14, 12, 0);

    @Mock AgentRunRepository runRepository;
    @Mock AgentRunEventRepository eventRepository;
    @Mock ToolApprovalRepository approvalRepository;

    private SimpleMeterRegistry meterRegistry;
    private AgentExperienceMetricsService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new AgentExperienceMetricsService(
                runRepository, eventRepository, approvalRepository, meterRegistry);
    }

    @Test
    void usesOnlyRecentRunAndApprovalWindows() {
        List<AgentRun> runs = new ArrayList<>();
        for (long id = 1; id <= 100; id++) {
            runs.add(run(id, USER_ID, null, 0));
        }
        when(runRepository.findTop100ByUserIdOrderByCreateTimeDescIdDesc(USER_ID))
                .thenReturn(runs);
        when(eventRepository.findFirstModelChunkTimes(anyList())).thenReturn(List.of());
        when(approvalRepository
                .findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc(USER_ID))
                .thenReturn(List.of());

        ExperienceMetrics metrics = service.getMetrics(USER_ID);

        assertEquals(100, metrics.windowRuns());
        List<Long> expectedRunIds = runs.stream().map(AgentRun::getId).toList();
        verify(runRepository).findTop100ByUserIdOrderByCreateTimeDescIdDesc(USER_ID);
        verify(eventRepository).findFirstModelChunkTimes(expectedRunIds);
        verify(approvalRepository)
                .findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc(USER_ID);
        verify(eventRepository, never()).findByRunIdOrderBySeqAsc(anyLong());
    }

    @Test
    void calculatesNearestRankP95RatesAveragesAndTokens() {
        List<AgentRun> runs = new ArrayList<>();
        List<FirstModelChunkTime> firstChunks = new ArrayList<>();
        for (long id = 1; id <= 20; id++) {
            long tokens = id == 1 ? 100 : id == 2 ? 300 : 0;
            runs.add(run(id, USER_ID, BASE_TIME, tokens));
            firstChunks.addFirst(firstChunk(id, BASE_TIME.plus(id * 100, ChronoUnit.MILLIS)));
        }
        when(runRepository.findTop100ByUserIdOrderByCreateTimeDescIdDesc(USER_ID))
                .thenReturn(runs);
        when(eventRepository.findFirstModelChunkTimes(anyList())).thenReturn(firstChunks);
        when(approvalRepository
                .findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc(USER_ID))
                .thenReturn(List.of(
                        approval(USER_ID, BASE_TIME, 1000),
                        approval(USER_ID, BASE_TIME, 3001)));
        recordTool(USER_ID, "success", 6);
        recordTool(USER_ID, "failure", 3);
        recordTool(USER_ID, "error", 1);

        ExperienceMetrics metrics = service.getMetrics(USER_ID);

        assertNotNull(metrics.generatedAt());
        assertEquals(20, metrics.windowRuns());
        assertEquals(1900L, metrics.firstToken().p95Ms());
        assertEquals(20, metrics.firstToken().sampleCount());
        assertEquals(0.4, metrics.tool().failureRate(), 0.000001);
        assertEquals(10, metrics.tool().totalCount());
        assertEquals(4, metrics.tool().failedCount());
        assertEquals("PROCESS", metrics.tool().scope());
        assertEquals(2000.5, metrics.approval().averageWaitMs(), 0.000001);
        assertEquals(2, metrics.approval().sampleCount());
        assertEquals(200.0, metrics.tokens().averageTotalTokens(), 0.000001);
        assertEquals(400, metrics.tokens().totalTokens());
        assertEquals(2, metrics.tokens().sampleCount());
        verify(eventRepository, times(1)).findFirstModelChunkTimes(anyList());
        verify(eventRepository, never()).findByRunIdOrderBySeqAsc(anyLong());
    }

    @Test
    void returnsNullForEmptySamples() {
        when(runRepository.findTop100ByUserIdOrderByCreateTimeDescIdDesc(USER_ID))
                .thenReturn(List.of());
        when(approvalRepository
                .findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc(USER_ID))
                .thenReturn(List.of());

        ExperienceMetrics metrics = service.getMetrics(USER_ID);

        assertEquals(0, metrics.windowRuns());
        assertNull(metrics.firstToken().p95Ms());
        assertEquals(0, metrics.firstToken().sampleCount());
        assertNull(metrics.tool().failureRate());
        assertEquals(0, metrics.tool().totalCount());
        assertEquals(0, metrics.tool().failedCount());
        assertEquals("PROCESS", metrics.tool().scope());
        assertNull(metrics.approval().averageWaitMs());
        assertEquals(0, metrics.approval().sampleCount());
        assertNull(metrics.tokens().averageTotalTokens());
        assertEquals(0, metrics.tokens().totalTokens());
        assertEquals(0, metrics.tokens().sampleCount());
        verify(eventRepository, never()).findFirstModelChunkTimes(anyList());
    }

    @Test
    void isolatesRunApprovalAndTimerMetricsByUser() {
        AgentRun ownRun = run(101L, USER_ID, BASE_TIME, 100);
        AgentRun otherRun = run(202L, OTHER_USER_ID, BASE_TIME, 9000);
        FirstModelChunkTime ownChunk = firstChunk(101L, BASE_TIME.plus(100, ChronoUnit.MILLIS));
        FirstModelChunkTime otherChunk = firstChunk(202L, BASE_TIME.plus(900, ChronoUnit.MILLIS));
        when(runRepository.findTop100ByUserIdOrderByCreateTimeDescIdDesc(USER_ID))
                .thenReturn(List.of(ownRun));
        when(runRepository.findTop100ByUserIdOrderByCreateTimeDescIdDesc(OTHER_USER_ID))
                .thenReturn(List.of(otherRun));
        when(eventRepository.findFirstModelChunkTimes(anyList())).thenAnswer(invocation -> {
            List<Long> runIds = invocation.getArgument(0);
            if (runIds.equals(List.of(101L))) return List.of(ownChunk);
            if (runIds.equals(List.of(202L))) return List.of(otherChunk);
            return List.of();
        });
        when(approvalRepository
                .findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc(USER_ID))
                .thenReturn(List.of(approval(USER_ID, BASE_TIME, 1000)));
        when(approvalRepository
                .findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc(OTHER_USER_ID))
                .thenReturn(List.of(approval(OTHER_USER_ID, BASE_TIME, 9000)));
        recordTool(USER_ID, "success", 3);
        recordTool(USER_ID, "failure", 1);
        recordTool(OTHER_USER_ID, "failure", 4);

        ExperienceMetrics ownMetrics = service.getMetrics(USER_ID);
        ExperienceMetrics otherMetrics = service.getMetrics(OTHER_USER_ID);

        assertEquals(100L, ownMetrics.firstToken().p95Ms());
        assertEquals(100.0, ownMetrics.tokens().averageTotalTokens());
        assertEquals(1000.0, ownMetrics.approval().averageWaitMs());
        assertEquals(0.25, ownMetrics.tool().failureRate(), 0.000001);
        assertEquals(4, ownMetrics.tool().totalCount());
        assertEquals(1, ownMetrics.tool().failedCount());

        assertEquals(900L, otherMetrics.firstToken().p95Ms());
        assertEquals(9000.0, otherMetrics.tokens().averageTotalTokens());
        assertEquals(9000.0, otherMetrics.approval().averageWaitMs());
        assertEquals(1.0, otherMetrics.tool().failureRate(), 0.000001);
        assertEquals(4, otherMetrics.tool().totalCount());
        assertEquals(4, otherMetrics.tool().failedCount());
    }

    private AgentRun run(long id, long userId, LocalDateTime startTime, long totalTokens) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId(userId);
        run.setStartTime(startTime);
        run.setTotalTokens(totalTokens);
        return run;
    }

    private ToolApproval approval(long userId, LocalDateTime createTime, long waitMs) {
        ToolApproval approval = new ToolApproval();
        approval.setUserId(userId);
        approval.setCreateTime(createTime);
        approval.setDecisionTime(createTime.plus(waitMs, ChronoUnit.MILLIS));
        return approval;
    }

    private FirstModelChunkTime firstChunk(long runId, LocalDateTime createTime) {
        return new FirstModelChunkTime() {
            @Override
            public Long getRunId() {
                return runId;
            }

            @Override
            public LocalDateTime getFirstChunkTime() {
                return createTime;
            }
        };
    }

    private void recordTool(long userId, String result, int count) {
        Timer timer = Timer.builder("agent.tool.execution")
                .tag("userId", Long.toString(userId))
                .tag("result", result)
                .register(meterRegistry);
        for (int i = 0; i < count; i++) {
            timer.record(1, TimeUnit.MILLISECONDS);
        }
    }
}
