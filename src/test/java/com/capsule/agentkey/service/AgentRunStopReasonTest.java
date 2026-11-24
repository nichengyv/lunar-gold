package com.capsule.agentkey.service;

import com.capsule.agentkey.agent.run.RunEventStore;
import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRun.RunState;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** R8a：停止原因落库、终态判断与「用户主动停止可显式续跑」。 */
@ExtendWith(MockitoExtension.class)
class AgentRunStopReasonTest {

    @Mock AgentRunRepository runRepo;
    @Mock AgentRunEventRepository eventRepo;

    private AgentRunService service;

    @BeforeEach
    void setup() {
        service = new AgentRunService(runRepo, eventRepo, new ObjectMapper(),
                new RunEventStore(runRepo, eventRepo, new ObjectMapper()));
        lenient().when(eventRepo.findMaxSeqByRunId(anyLong())).thenReturn(null);
        lenient().when(runRepo.save(any(AgentRun.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentRun run(Long id, RunState state) {
        AgentRun r = new AgentRun();
        r.setId(id);
        r.setUserId(1L);
        r.setSessionId(3L);
        r.setState(state);
        r.setCreateTime(LocalDateTime.now());
        return r;
    }

    @Test
    void 用户主动停止且带断点时可显式续跑() {
        AgentRun r = run(1L, RunState.CANCELLED);
        r.setStopReason("USER_STOP");
        r.setCheckpointJson("{\"messages\":[]}");

        assertTrue(AgentRunService.isResumableByUser(r));

        r.setStopReason(null);
        assertFalse(AgentRunService.isResumableByUser(r), "非用户停止的取消不可续跑");
        r.setStopReason("USER_STOP");
        r.setCheckpointJson("  ");
        assertFalse(AgentRunService.isResumableByUser(r), "没有断点不可续跑");
    }

    @Test
    void 取消时补写stop_reason且保留已写入的USER_STOP() {
        AgentRun r = run(1L, RunState.RUNNING);
        when(runRepo.findById(1L)).thenReturn(Optional.of(r));
        when(runRepo.lockForEventAppend(1L)).thenReturn(Optional.of(r));

        service.cancel(1L, "agent cancelled");
        assertEquals("CANCELLED", r.getStopReason());

        r.setStopReason("USER_STOP");
        AgentRun again = run(2L, RunState.RUNNING);
        again.setStopReason("USER_STOP");
        when(runRepo.findById(2L)).thenReturn(Optional.of(again));
        when(runRepo.lockForEventAppend(2L)).thenReturn(Optional.of(again));
        service.cancel(2L, "agent cancelled");
        assertEquals("USER_STOP", again.getStopReason(), "已写入的 USER_STOP 不能被覆盖");
    }

    @Test
    void 用户停止只标记活跃Run() {
        AgentRun active = run(2L, RunState.RUNNING);
        AgentRun done = run(1L, RunState.SUCCEEDED);
        when(runRepo.findByUserIdAndParentRunIdIsNullAndSessionIdOrderByCreateTimeDescIdDesc(1L, 3L))
                .thenReturn(List.of(active, done));

        int marked = service.markStopReasonForSession(1L, 3L, "USER_STOP");

        assertEquals(1, marked);
        assertEquals("USER_STOP", active.getStopReason());
        assertNull(done.getStopReason());
    }

    @Test
    void 最近终态Run跳过排队中的新任务() {
        AgentRun queued = run(3L, RunState.QUEUED);
        AgentRun cancelled = run(2L, RunState.CANCELLED);
        when(runRepo.findByUserIdAndParentRunIdIsNullAndSessionIdOrderByCreateTimeDescIdDesc(1L, 3L))
                .thenReturn(List.of(queued, cancelled));

        assertEquals(Optional.of(cancelled), service.findLatestTerminalRun(1L, 3L));
    }
}
