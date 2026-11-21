package com.capsule.agentkey.tool;

import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.agent.run.RunContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2：WorkspaceContext 只是 RunContext 的线程视图。
 *
 * <p>没有外部 RunContext 时也必须能工作（单测、MCP、后台任务），
 * 此时使用合成 RunContext，避免出现「有状态 / 无状态」两套分支。
 */
class WorkspaceContextTest {

    @AfterEach
    void clear() {
        WorkspaceContext.clear();
    }

    @Test
    void 无RunContext时使用合成上下文且状态可读写() {
        WorkspaceContext.set(1L, 2L);

        WorkspaceContext ctx = WorkspaceContext.get();
        assertNotNull(ctx.runContext());
        assertNull(ctx.runId());
        assertEquals(1L, ctx.userId());
        assertEquals(2L, ctx.projectId());

        ctx.setPlan(List.of(new PlanStep("步骤一", "in_progress")));
        ctx.setScope(List.of("src/"));
        ctx.setSessionId(23L);
        ctx.setTurnId("turn-1");

        assertEquals(1, ctx.plan().size());
        assertTrue(ctx.drainPlanChanged());
        assertEquals(List.of("src/"), ctx.scope());
        assertEquals(23L, ctx.sessionId());
        assertEquals("turn-1", ctx.turnId());
    }

    @Test
    void 子上下文共享RunContext但文件变更缓冲独立() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        RunContext run = RunContext.create(7L, 1L, 2L, 3L, "turn", cancelled,
                List.of(new PlanStep("a", "pending")), null);
        WorkspaceContext parent = new WorkspaceContext(run);

        carry(() -> {
            WorkspaceContext.recordFileChange(new FileChange("a.txt", "modify", "write_file", 1, 0, ""));
            WorkspaceContext child = WorkspaceContext.get().child();

            assertEquals(run, child.runContext());
            assertEquals(7L, child.runId());
            assertEquals(1, child.plan().size());
            assertSame(cancelled, child.cancelled());
            assertEquals(0, child.fileChanges().size(), "子上下文必须有独立的变更缓冲");

            assertEquals(1, WorkspaceContext.drainFileChanges().size());
            assertTrue(WorkspaceContext.drainFileChanges().isEmpty());
        }, parent);
    }

    /** 测试辅助：在指定上下文里执行，避免直接操作 ThreadLocal */
    private static void carry(Runnable action, WorkspaceContext context) {
        WorkspaceContext.callIn(context, () -> {
            action.run();
            return null;
        });
    }
}
