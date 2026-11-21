package com.capsule.agentkey.agent.run;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.AgentRunEvent.EventType;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R0：事件 seq 原子分配与计划快照写入的契约。
 *
 * <p>P7-04c 回滚根因之一是 seq 用「查最大值 + 1」却没有任何串行化：
 * 两个线程同时追加会拿到同一个 seq，撞 uk_run_event_seq 唯一约束。
 * 这里锁定行为：先锁 Run 行，再查 max，再插入；计划只更新 plan_json 列。
 */
@ExtendWith(MockitoExtension.class)
class RunEventStoreTest {

    @Mock AgentRunRepository runRepo;
    @Mock AgentRunEventRepository eventRepo;

    private RunEventStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = new RunEventStore(runRepo, eventRepo, mapper);
    }

    @Test
    void 追加事件先锁Run行再按最大值加一分配seq() {
        when(runRepo.lockForEventAppend(1L)).thenReturn(Optional.of(new AgentRun()));
        when(eventRepo.findMaxSeqByRunId(1L)).thenReturn(4);

        int seq = store.append(1L, EventType.TOOL_CALL, Map.of("tool", "read_file"));

        assertEquals(5, seq);
        InOrder order = inOrder(runRepo, eventRepo);
        order.verify(runRepo).lockForEventAppend(1L);
        order.verify(eventRepo).findMaxSeqByRunId(1L);
        order.verify(eventRepo).save(any(AgentRunEvent.class));

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRepo).save(captor.capture());
        assertEquals(5, captor.getValue().getSeq());
        assertEquals(EventType.TOOL_CALL, captor.getValue().getEventType());
        assertTrue(captor.getValue().getPayload().contains("read_file"));
    }

    @Test
    void 空事件流从1开始() {
        when(runRepo.lockForEventAppend(2L)).thenReturn(Optional.of(new AgentRun()));
        when(eventRepo.findMaxSeqByRunId(2L)).thenReturn(null);

        assertEquals(1, store.append(2L, EventType.STATE_CHANGED, Map.of("targetState", "RUNNING")));
    }

    @Test
    void run不存在时拒绝追加且不写事件() {
        when(runRepo.lockForEventAppend(9L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> store.append(9L, EventType.ERROR, Map.of()));

        verify(eventRepo, never()).save(any());
    }

    @Test
    void 保存计划只更新plan列并追加PLAN_UPDATED事件() {
        when(runRepo.updatePlanJson(eq(1L), anyString())).thenReturn(1);
        when(runRepo.lockForEventAppend(1L)).thenReturn(Optional.of(new AgentRun()));
        when(eventRepo.findMaxSeqByRunId(1L)).thenReturn(2);

        List<PlanStep> steps = List.of(
                new PlanStep("读取项目", "completed"),
                new PlanStep("修改接口", "in_progress"));
        store.savePlan(1L, steps);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(runRepo).updatePlanJson(eq(1L), json.capture());
        assertTrue(json.getValue().contains("\"title\":\"读取项目\""), json.getValue());
        assertTrue(json.getValue().contains("\"status\":\"in_progress\""), json.getValue());
        verify(runRepo, never()).save(any()); // 禁止整行 save 覆盖并发状态

        ArgumentCaptor<AgentRunEvent> event = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRepo).save(event.capture());
        assertEquals(3, event.getValue().getSeq());
        assertEquals(EventType.PLAN_UPDATED, event.getValue().getEventType());
        assertTrue(event.getValue().getPayload().contains("修改接口"));
    }

    @Test
    void 计划快照可以读回() throws Exception {
        AgentRun run = new AgentRun();
        run.setPlanJson(mapper.writeValueAsString(List.of(
                new PlanStep("a", "completed"),
                new PlanStep("b", "aborted"))));
        when(runRepo.findById(1L)).thenReturn(Optional.of(run));

        assertEquals(2, store.loadPlan(1L).size());
        assertEquals("aborted", store.loadPlan(1L).get(1).status());
    }

    @Test
    void 没有计划或JSON损坏时返回空列表() {
        when(runRepo.findById(1L)).thenReturn(Optional.of(new AgentRun()));
        assertTrue(store.loadPlan(1L).isEmpty());

        AgentRun broken = new AgentRun();
        broken.setPlanJson("{not-json");
        when(runRepo.findById(2L)).thenReturn(Optional.of(broken));
        assertTrue(store.loadPlan(2L).isEmpty());
    }
}
