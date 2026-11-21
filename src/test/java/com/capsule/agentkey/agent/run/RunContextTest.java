package com.capsule.agentkey.agent.run;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R0：RunContext 状态契约。
 *
 * <p>这些断言是 R1 重构的安全网：计划快照必须不可变、drain 语义读一次即清空、
 * 持久化回调失败不能打断主循环。
 */
class RunContextTest {

    private RunContext context(List<PlanStep> initial, List<List<PlanStep>> persisted) {
        return RunContext.create(1L, 2L, 3L, 4L, "turn-1", new AtomicBoolean(false),
                initial, persisted::add);
    }

    @Test
    void 新Run的计划为空且没有待推送变化() {
        RunContext ctx = context(null, new ArrayList<>());

        assertTrue(ctx.plan().isEmpty());
        assertFalse(ctx.hasPlan());
        assertFalse(ctx.drainPlanChanged());
        assertEquals(1L, ctx.runId());
        assertEquals(4L, ctx.sessionId());
    }

    @Test
    void 恢复的快照原样可见() {
        List<PlanStep> restored = List.of(
                new PlanStep("读取项目", "completed"),
                new PlanStep("修改接口", "in_progress"));

        RunContext ctx = context(restored, new ArrayList<>());

        assertEquals(restored, ctx.plan());
        assertTrue(ctx.hasPlan());
    }

    @Test
    void 计划更新后drain只返回一次true() {
        List<List<PlanStep>> persisted = new ArrayList<>();
        RunContext ctx = context(null, persisted);

        ctx.updatePlan(List.of(new PlanStep("a", "in_progress")));

        assertTrue(ctx.drainPlanChanged(), "更新后应标记待推送");
        assertFalse(ctx.drainPlanChanged(), "读一次后标记必须清空");
        assertEquals(1, persisted.size(), "每次更新都应触发一次持久化");
        assertEquals("a", persisted.get(0).get(0).title());
    }

    @Test
    void 计划快照不可被调用方后续修改() {
        RunContext ctx = context(null, new ArrayList<>());
        List<PlanStep> mutable = new ArrayList<>();
        mutable.add(new PlanStep("a", "pending"));

        ctx.updatePlan(mutable);
        mutable.add(new PlanStep("b", "pending"));

        assertEquals(1, ctx.plan().size(), "RunContext 必须持有不可变副本");
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.plan().add(new PlanStep("c", "pending")));
    }

    @Test
    void 持久化回调失败不影响内存快照() {
        RunContext ctx = RunContext.create(1L, 2L, 3L, 4L, "turn-1", new AtomicBoolean(false),
                List.of(), steps -> {
                    throw new IllegalStateException("db down");
                });

        assertDoesNotThrow(() -> ctx.updatePlan(List.of(new PlanStep("a", "in_progress"))));
        assertEquals(1, ctx.plan().size());
        assertTrue(ctx.drainPlanChanged());
    }

    @Test
    void scope与诊断状态归属RunContext() {
        RunContext ctx = context(null, new ArrayList<>());
        assertEquals(List.of(), ctx.scope());

        ctx.setScope(List.of("src/"));
        ctx.setDiagnosticsFingerprint(null);
        ctx.setDiagnosticsFileVersion("mtime:size");

        assertEquals(List.of("src/"), ctx.scope());
        assertEquals("", ctx.diagnosticsFingerprint(), "null 必须归一化为空串");
        assertEquals("mtime:size", ctx.diagnosticsFileVersion());
        assertNull(ctx.verify());
    }

    @Test
    void runId与会话信息可在执行中补齐() {
        RunContext ctx = RunContext.create(null, 1L, 2L, null, null,
                new AtomicBoolean(false), List.of(), null);

        ctx.setRunId(88L);
        ctx.setSessionId(9L);
        ctx.setTurnId("turn-9");

        assertEquals(88L, ctx.runId());
        assertEquals(9L, ctx.sessionId());
        assertEquals("turn-9", ctx.turnId());
    }

    // —— R7c：文件变更版本与验证覆盖版本 ——

    @Test
    void 初始状态没有覆盖任何变更() {
        RunContext ctx = context(null, new ArrayList<>());

        assertEquals(0, ctx.changeEpoch());
        assertEquals(-1, ctx.verifiedEpoch());
        assertFalse(ctx.verifyCoversLatestChange());
    }

    @Test
    void 验证追平最新变更后才算覆盖() {
        RunContext ctx = context(null, new ArrayList<>());

        ctx.bumpChangeEpoch();
        assertFalse(ctx.verifyCoversLatestChange(), "只变更未验证时不能算覆盖");

        ctx.markVerified();
        assertEquals(ctx.changeEpoch(), ctx.verifiedEpoch());
        assertTrue(ctx.verifyCoversLatestChange());

        ctx.bumpChangeEpoch();
        assertFalse(ctx.verifyCoversLatestChange(), "验证后又有变更时必须重新验证");
    }

    @Test
    void 续跑恢复两个版本() {
        RunContext ctx = context(null, new ArrayList<>());

        ctx.restoreEpochs(4, 3);

        assertEquals(4, ctx.changeEpoch());
        assertEquals(3, ctx.verifiedEpoch());
        assertFalse(ctx.verifyCoversLatestChange());

        ctx.restoreEpochs(4, 4);
        assertTrue(ctx.verifyCoversLatestChange());
    }
}
