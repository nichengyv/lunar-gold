package com.capsule.agentkey.service;

import com.capsule.agentkey.agent.run.RunEventStore;
import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRun.RunState;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.AgentRunEvent.EventType;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 4 第一批：AgentRunService 状态机单元测试。
 * 所有 Repository 调用都走 Mockito，不连数据库。
 */
@ExtendWith(MockitoExtension.class)
class AgentRunServiceTest {

    @Mock AgentRunRepository runRepo;
    @Mock AgentRunEventRepository eventRepo;

    private AgentRunService service;
    private final ObjectMapper om = new ObjectMapper();

    /** 准备一个可被 Repository 返回的 run 实例 */
    private AgentRun run(Long id, RunState state) {
        AgentRun r = new AgentRun();
        r.setId(id);
        r.setUserId(1L);
        r.setState(state);
        r.setCreateTime(LocalDateTime.now());
        return r;
    }

    @BeforeEach
    void setup() {
        service = new AgentRunService(runRepo, eventRepo, om,
                new RunEventStore(runRepo, eventRepo, om));
        // 默认：事件 repo 查最大 seq 返回 null（空表）
        lenient().when(eventRepo.findMaxSeqByRunId(anyLong())).thenReturn(null);
        // R1：追加事件前会先锁 Run 行；mock 默认返回空 Optional，会让 append 抛错
        lenient().when(runRepo.lockForEventAppend(anyLong()))
                .thenReturn(Optional.of(run(0L, RunState.RUNNING)));
    }

    // ------------------------------------------------------------------
    // 状态转移表静态校验
    // ------------------------------------------------------------------

    @Test
    void 静态转移表覆盖所有合法迁移() {
        // QUEUED → RUNNING
        assertTrue(AgentRunService.transitionAllowed(RunState.QUEUED, RunState.RUNNING));
        // RUNNING → WAITING_APPROVAL
        assertTrue(AgentRunService.transitionAllowed(RunState.RUNNING, RunState.WAITING_APPROVAL));
        // WAITING_APPROVAL → RUNNING（批准）
        assertTrue(AgentRunService.transitionAllowed(RunState.WAITING_APPROVAL, RunState.RUNNING));
        // RUNNING → SUCCEEDED / FAILED / CANCELLED
        assertTrue(AgentRunService.transitionAllowed(RunState.RUNNING, RunState.SUCCEEDED));
        assertTrue(AgentRunService.transitionAllowed(RunState.RUNNING, RunState.FAILED));
        assertTrue(AgentRunService.transitionAllowed(RunState.RUNNING, RunState.CANCELLED));
        // WAITING_APPROVAL → CANCELLED（拒绝 / 超时）
        assertTrue(AgentRunService.transitionAllowed(RunState.WAITING_APPROVAL, RunState.CANCELLED));
        // QUEUED 取消启动
        assertTrue(AgentRunService.transitionAllowed(RunState.QUEUED, RunState.CANCELLED));
    }

    @Test
    void 终态禁止再迁移() {
        for (RunState terminal : List.of(RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED)) {
            assertFalse(AgentRunService.transitionAllowed(terminal, RunState.RUNNING));
            assertFalse(AgentRunService.transitionAllowed(terminal, RunState.QUEUED));
            assertFalse(AgentRunService.transitionAllowed(terminal, RunState.WAITING_APPROVAL));
            assertFalse(AgentRunService.transitionAllowed(terminal, RunState.SUCCEEDED));
        }
    }

    @Test
    void 非法迁移被拒绝() {
        // WAITING_APPROVAL 不能直接 SUCCEEDED（必须先 RUNNING）
        assertFalse(AgentRunService.transitionAllowed(RunState.WAITING_APPROVAL, RunState.SUCCEEDED));
        // QUEUED 不能直接 FAILED（除非启动前异常，当前未开放）
        assertFalse(AgentRunService.transitionAllowed(RunState.QUEUED, RunState.WAITING_APPROVAL));
    }

    @Test
    void 重启把等待审批的Run一并终止为FAILED并写终态事件() {
        AgentRun waiting = run(7L, RunState.WAITING_APPROVAL);
        when(runRepo.findByStateIn(argThat((List<RunState> states) -> states != null
                && states.contains(RunState.WAITING_APPROVAL)))).thenReturn(List.of(waiting));

        service.recoverAfterRestart();

        assertEquals(RunState.FAILED, waiting.getState(), "等待审批的 Run 重启后不能伪装成仍可继续");
        assertEquals("PROCESS_RESTARTED", waiting.getErrorCode());
        assertNotNull(waiting.getEndTime());
        verify(runRepo).save(waiting);

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRepo).save(captor.capture());
        AgentRunEvent event = captor.getValue();
        assertEquals(7L, event.getRunId());
        assertEquals(EventType.STATE_CHANGED, event.getEventType());
        assertTrue(event.getPayload().contains("PROCESS_RESTARTED"),
                "终态事件必须可被 sinceSeq 重放，客户端才能看到中断原因");
    }

    // ------------------------------------------------------------------
    // 全链路 happy path + 事件验证
    // ------------------------------------------------------------------

    @Test
    void create_start_run_succeed_全链路写对状态和事件() {
        // Arrange：findById 返回当前可变 run，save 同步更新 holder
        AgentRun[] holder = new AgentRun[]{ run(42L, RunState.QUEUED) };
        when(runRepo.findById(42L)).thenAnswer(inv -> Optional.of(holder[0]));
        when(runRepo.save(any(AgentRun.class))).thenAnswer(inv -> {
            holder[0] = inv.getArgument(0);
            holder[0].setId(42L);
            return holder[0];
        });
        when(eventRepo.findMaxSeqByRunId(42L)).thenReturn(null, 1, 2);

        // Act
        AgentRunService.CreateResult createResult = service.create(1L, 10L, 20L, null, "{\"prompt\":\"hello\"}");
        AgentRun created = createResult.run();
        assertTrue(createResult.created());
        AgentRun started = service.start(42L);
        AgentRun succeeded = service.succeed(42L, "{\"ok\":true}");

        // Assert 状态：mock 返回同一可变实体，历史变量不能当作状态快照
        assertEquals(42L, created.getId());
        assertEquals(42L, started.getId());
        assertEquals(RunState.SUCCEEDED, succeeded.getState());
        assertNotNull(succeeded.getStartTime(), "进入 RUNNING 后应写入 startTime");
        assertNotNull(succeeded.getEndTime(), "SUCCEEDED 应写入 endTime");

        // Assert 事件：QUEUED + RUNNING + SUCCEEDED 各一条 STATE_CHANGED
        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRepo, times(3)).save(captor.capture());
        List<AgentRunEvent> saved = captor.getAllValues();
        assertEquals(1, saved.get(0).getSeq());
        assertEquals(2, saved.get(1).getSeq());
        assertEquals(3, saved.get(2).getSeq());
        assertEquals(EventType.STATE_CHANGED, saved.get(0).getEventType());
        assertEquals(EventType.STATE_CHANGED, saved.get(1).getEventType());
        assertEquals(EventType.STATE_CHANGED, saved.get(2).getEventType());
    }

    @Test
    void 审批路径_WAITING_APPROVAL_批准后回到RUNNING() {
        AgentRun[] holder = new AgentRun[]{ run(7L, RunState.RUNNING) };
        when(runRepo.findById(7L)).thenAnswer(inv -> Optional.of(holder[0]));
        when(runRepo.save(any(AgentRun.class))).thenAnswer(inv -> {
            holder[0] = inv.getArgument(0);
            holder[0].setId(7L);
            return holder[0];
        });
        // 模拟已有 1 条事件（seq=1）
        when(eventRepo.findMaxSeqByRunId(7L)).thenReturn(1);

        AgentRun paused = service.pauseForApproval(7L, "ap-xyz", "write_file");
        assertEquals(RunState.WAITING_APPROVAL, paused.getState());

        AgentRun resumed = service.resumeAfterApproval(7L, "APPROVE", "USER", null);
        assertEquals(RunState.RUNNING, resumed.getState());

        // 事件计数：pauseForApproval 内部写 APPROVAL_REQUESTED(seq=2) + STATE_CHANGED(seq=3)；
        // resumeAfterApproval 写 APPROVAL_RESOLVED(seq=4) + STATE_CHANGED(seq=5) → 4 条
        ArgumentCaptor<AgentRunEvent> cap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRepo, times(4)).save(cap.capture());
        List<AgentRunEvent> saved = cap.getAllValues();
        assertEquals(2, saved.get(0).getSeq());
        assertEquals(EventType.APPROVAL_REQUESTED, saved.get(0).getEventType());
        assertEquals(EventType.STATE_CHANGED, saved.get(1).getEventType());
        assertEquals(EventType.APPROVAL_RESOLVED, saved.get(2).getEventType());
        assertEquals(EventType.STATE_CHANGED, saved.get(3).getEventType());
    }

    @Test
    void 决策询问路径_WAITING_USER_INPUT_回答后回到RUNNING() {
        AgentRun[] holder = new AgentRun[]{ run(8L, RunState.RUNNING) };
        when(runRepo.findById(8L)).thenAnswer(inv -> Optional.of(holder[0]));
        when(runRepo.save(any(AgentRun.class))).thenAnswer(inv -> {
            holder[0] = inv.getArgument(0);
            holder[0].setId(8L);
            return holder[0];
        });
        when(eventRepo.findMaxSeqByRunId(8L)).thenReturn(1);

        AgentRun paused = service.pauseForUserInput(8L, "scope", "选 A 还是 B？", "改动范围",
                List.of(Map.of("label", "A")), false);
        assertEquals(RunState.WAITING_USER_INPUT, paused.getState());

        AgentRun resumed = service.resumeAfterUserInput(8L, "scope", List.of("A"), null);
        assertEquals(RunState.RUNNING, resumed.getState());

        // pause 写 USER_QUESTION + STATE_CHANGED；resume 写 USER_ANSWERED + STATE_CHANGED → 4 条
        ArgumentCaptor<AgentRunEvent> cap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRepo, times(4)).save(cap.capture());
        List<AgentRunEvent> saved = cap.getAllValues();
        assertEquals(EventType.USER_QUESTION, saved.get(0).getEventType());
        assertTrue(saved.get(0).getPayload().contains("\"questionId\":\"scope\""), saved.get(0).getPayload());
        assertEquals(EventType.STATE_CHANGED, saved.get(1).getEventType());
        assertEquals(EventType.USER_ANSWERED, saved.get(2).getEventType());
        assertEquals(EventType.STATE_CHANGED, saved.get(3).getEventType());
    }

    @Test
    void 询问超时把Run拉回RUNNING并记录abandoned() {
        AgentRun[] holder = new AgentRun[]{ run(18L, RunState.WAITING_USER_INPUT) };
        when(runRepo.findById(18L)).thenAnswer(inv -> Optional.of(holder[0]));
        when(runRepo.save(any(AgentRun.class))).thenAnswer(inv -> {
            holder[0] = inv.getArgument(0);
            holder[0].setId(18L);
            return holder[0];
        });
        when(eventRepo.findMaxSeqByRunId(18L)).thenReturn(0);

        service.abandonUserQuestion(18L, "scope", "USER_INPUT_TIMEOUT");

        assertEquals(RunState.RUNNING, holder[0].getState(), "超时后不能永久停在 WAITING_USER_INPUT");
        ArgumentCaptor<AgentRunEvent> cap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRepo, times(2)).save(cap.capture());
        assertEquals(EventType.USER_ANSWERED, cap.getAllValues().get(0).getEventType());
        String payload = cap.getAllValues().get(0).getPayload();
        assertTrue(payload.contains("\"abandoned\":true"), payload);
        assertTrue(payload.contains("USER_INPUT_TIMEOUT"), payload);
        assertEquals(EventType.STATE_CHANGED, cap.getAllValues().get(1).getEventType());
    }

    @Test
    void 已不在等待状态时abandon不覆盖状态() {
        AgentRun resumed = run(19L, RunState.RUNNING);
        when(runRepo.findById(19L)).thenReturn(Optional.of(resumed));

        service.abandonUserQuestion(19L, "scope", "USER_INPUT_TIMEOUT");

        assertEquals(RunState.RUNNING, resumed.getState());
        verify(eventRepo, never()).save(any());
        verify(runRepo, never()).save(any());
    }

    @Test
    void 决策询问的合法迁移边界() {
        // 进入：只能从 RUNNING
        assertTrue(AgentRunService.transitionAllowed(RunState.RUNNING, RunState.WAITING_USER_INPUT));
        assertFalse(AgentRunService.transitionAllowed(RunState.QUEUED, RunState.WAITING_USER_INPUT));
        assertFalse(AgentRunService.transitionAllowed(RunState.WAITING_APPROVAL, RunState.WAITING_USER_INPUT));
        // 退出：RUNNING / CANCELLED / FAILED，不能直接成功
        assertTrue(AgentRunService.transitionAllowed(RunState.WAITING_USER_INPUT, RunState.RUNNING));
        assertTrue(AgentRunService.transitionAllowed(RunState.WAITING_USER_INPUT, RunState.CANCELLED));
        assertTrue(AgentRunService.transitionAllowed(RunState.WAITING_USER_INPUT, RunState.FAILED));
        assertFalse(AgentRunService.transitionAllowed(RunState.WAITING_USER_INPUT, RunState.SUCCEEDED));
    }

    @Test
    void 重启把等待用户输入的Run一并终止为FAILED() {
        AgentRun waiting = run(17L, RunState.WAITING_USER_INPUT);
        when(runRepo.findByStateIn(argThat((List<RunState> states) -> states != null
                && states.contains(RunState.WAITING_USER_INPUT)))).thenReturn(List.of(waiting));

        service.recoverAfterRestart();

        assertEquals(RunState.FAILED, waiting.getState(), "等待用户输入的 Run 重启后无法恢复执行");
        assertEquals("PROCESS_RESTARTED", waiting.getErrorCode());
    }

    @Test
    void 非法迁移抛IllegalStateException() {        AgentRun[] holder = new AgentRun[]{ run(9L, RunState.SUCCEEDED) };
        when(runRepo.findById(9L)).thenAnswer(inv -> Optional.of(holder[0]));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.start(9L));
        assertTrue(ex.getMessage().contains("非法状态迁移"));
    }

    // ------------------------------------------------------------------
    // R7b：显式续跑
    // ------------------------------------------------------------------

    @Test
    void 只有重启中断且带断点的Run允许续跑() {
        AgentRun resumable = run(21L, RunState.FAILED);
        resumable.setErrorCode("PROCESS_RESTARTED");
        resumable.setCheckpointJson("{\"round\":2}");
        assertTrue(AgentRunService.isResumable(resumable));

        AgentRun normalFailure = run(22L, RunState.FAILED);
        normalFailure.setErrorCode("AGENT_EXECUTION_FAILED");
        normalFailure.setCheckpointJson("{\"round\":2}");
        assertFalse(AgentRunService.isResumable(normalFailure), "普通失败保持终态，不能借断点复活");

        AgentRun noCheckpoint = run(23L, RunState.FAILED);
        noCheckpoint.setErrorCode("PROCESS_RESTARTED");
        assertFalse(AgentRunService.isResumable(noCheckpoint));

        AgentRun waiting = run(24L, RunState.WAITING_APPROVAL);
        waiting.setCheckpointJson("{\"round\":1}");
        assertFalse(AgentRunService.isResumable(waiting));
    }

    @Test
    void 续跑把重启中断的Run拉回RUNNING并清掉错误码() {
        AgentRun[] holder = new AgentRun[]{ run(31L, RunState.FAILED) };
        holder[0].setErrorCode("PROCESS_RESTARTED");
        holder[0].setErrorMessage("应用重启，原执行线程已终止");
        holder[0].setEndTime(LocalDateTime.now());
        holder[0].setCheckpointJson("{\"round\":2}");
        when(runRepo.findById(31L)).thenAnswer(inv -> Optional.of(holder[0]));
        when(runRepo.save(any(AgentRun.class))).thenAnswer(inv -> {
            holder[0] = inv.getArgument(0);
            return holder[0];
        });

        AgentRun resumed = service.resumeInterrupted(31L);

        assertEquals(RunState.RUNNING, resumed.getState());
        assertNull(resumed.getErrorCode());
        assertNull(resumed.getErrorMessage());
        assertNull(resumed.getEndTime());
        assertNotNull(holder[0].getStartTime());

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRepo).save(captor.capture());
        assertEquals(EventType.STATE_CHANGED, captor.getValue().getEventType());
        assertTrue(captor.getValue().getPayload().contains("\"resumed\":true"));
        assertTrue(captor.getValue().getPayload().contains("FAILED"));
    }

    @Test
    void 普通失败或没有断点时拒绝续跑() {
        AgentRun normalFailure = run(32L, RunState.FAILED);
        normalFailure.setErrorCode("AGENT_EXECUTION_FAILED");
        normalFailure.setCheckpointJson("{\"round\":2}");
        when(runRepo.findById(32L)).thenReturn(Optional.of(normalFailure));

        assertThrows(IllegalStateException.class, () -> service.resumeInterrupted(32L));
        assertEquals(RunState.FAILED, normalFailure.getState());
        verify(runRepo, never()).save(any());

        AgentRun noCheckpoint = run(33L, RunState.FAILED);
        noCheckpoint.setErrorCode("PROCESS_RESTARTED");
        when(runRepo.findById(33L)).thenReturn(Optional.of(noCheckpoint));
        assertThrows(IllegalStateException.class, () -> service.resumeInterrupted(33L));
    }

    @Test
    void 幂等命中返回已存在的run() {
        AgentRun existing = run(100L, RunState.RUNNING);
        when(runRepo.findByUserIdAndClientRequestId(1L, "req-abc")).thenReturn(Optional.of(existing));

        AgentRunService.CreateResult result = service.create(1L, 10L, 20L, "req-abc", "{\"prompt\":\"repeat\"}");

        assertFalse(result.created());
        assertEquals(100L, result.run().getId());
        verify(runRepo, never()).save(any()); // 不应新建
        verify(eventRepo, never()).save(any()); // 不应写事件
    }

    @Test
    void cancel和fail都写终态时间戳和对应事件() {
        // fail
        AgentRun[] holder1 = new AgentRun[]{ run(11L, RunState.RUNNING) };
        when(runRepo.findById(11L)).thenAnswer(inv -> Optional.of(holder1[0]));
        when(runRepo.save(any(AgentRun.class))).thenAnswer(inv -> {
            holder1[0] = inv.getArgument(0);
            holder1[0].setId(11L);
            return holder1[0];
        });
        AgentRun failed = service.fail(11L, "ELECTRON_MAIN_START_FAILED", "sidecar jar missing");
        assertEquals(RunState.FAILED, failed.getState());
        assertEquals("ELECTRON_MAIN_START_FAILED", failed.getErrorCode());
        assertNotNull(failed.getEndTime());

        // cancel
        AgentRun[] holder2 = new AgentRun[]{ run(12L, RunState.WAITING_APPROVAL) };
        when(runRepo.findById(12L)).thenAnswer(inv -> Optional.of(holder2[0]));
        when(runRepo.save(any(AgentRun.class))).thenAnswer(inv -> {
            holder2[0] = inv.getArgument(0);
            holder2[0].setId(12L);
            return holder2[0];
        });
        AgentRun cancelled = service.cancel(12L, "user stopped");
        assertEquals(RunState.CANCELLED, cancelled.getState());
        assertNotNull(cancelled.getEndTime());

        // cancel 必须额外写一条 CANCELED 事件
        ArgumentCaptor<AgentRunEvent> cap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRepo, atLeastOnce()).save(cap.capture());
        assertTrue(cap.getAllValues().stream()
                .anyMatch(e -> e.getEventType() == EventType.CANCELED));
    }
}
