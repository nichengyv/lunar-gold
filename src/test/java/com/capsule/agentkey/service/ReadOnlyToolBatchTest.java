package com.capsule.agentkey.service;

import com.capsule.agentkey.config.AgentDiagnosticsProperties;
import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.diagnostics.JavacDiagnosticsProvider;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.eval.ScriptedAgentClient;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.tool.BaseTool;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.verify.VerifyService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P5-08：单轮连续只读工具并行执行。
 *
 * <p>用带延时的模拟工具驱动真实 ReAct 循环，验证：并发确实发生、事件/结果按原顺序回放、
 * 写工具仍串行、停止生成会取消未开始的只读任务，以及同类用例的 p95 收益。</p>
 */
class ReadOnlyToolBatchTest {

    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;

    @Test
    void 三个只读调用并发执行且结果顺序稳定() throws Exception {
        Tracker tracker = new Tracker();
        List<BaseTool> tools = List.of(
                readTool("read_a", 120, tracker),
                readTool("read_b", 120, tracker),
                readTool("read_c", 120, tracker));

        Outcome outcome = run(tracker, tools, 3, 0);

        assertEquals(List.of("read_a", "read_b", "read_c"), outcome.resultOrder());
        assertTrue(outcome.maxInFlight() >= 2,
                "3 个只读调用应并发执行，实际最大并发=" + outcome.maxInFlight());
        assertEquals(1, tracker.executions("read_a"));
        assertEquals(1, tracker.executions("read_b"));
        assertEquals(1, tracker.executions("read_c"));
        assertEquals(ReActAgentService.CompletionStatus.COMPLETED, outcome.completion().status());
    }

    @Test
    void 并发上限为一时的只读批保持串行() throws Exception {
        Tracker tracker = new Tracker();
        List<BaseTool> tools = List.of(
                readTool("read_a", 40, tracker),
                readTool("read_b", 40, tracker),
                readTool("read_c", 40, tracker));

        Outcome outcome = run(tracker, tools, 1, 0);

        assertEquals(1, outcome.maxInFlight(), "并发上限=1 时不允许重叠执行");
        assertEquals(List.of("read_a", "read_b", "read_c"), outcome.resultOrder());
    }

    @Test
    void 写工具不入只读批且混合轮次顺序正确() throws Exception {
        Tracker tracker = new Tracker();
        List<BaseTool> tools = List.of(
                readTool("read_a", 30, tracker),
                writeTool("write_b", 30, tracker),
                readTool("read_c", 30, tracker));

        Outcome outcome = run(tracker, tools, 3, 0);

        // 读 A → 写 B → 读 C 必须严格按模型给出的顺序执行，后置读取不能跑到写入之前
        assertEquals(List.of("read_a", "write_b", "read_c"), tracker.startOrder());
        assertEquals(List.of("read_a", "write_b", "read_c"), outcome.resultOrder());
        assertEquals(1, outcome.maxInFlight(), "中间有写操作时不允许与只读调用重叠");
    }

    @Test
    void 停止生成会取消未开始的只读任务() throws Exception {
        Tracker tracker = new Tracker();
        List<BaseTool> tools = List.of(
                readTool("read_a", 300, tracker),
                readTool("read_b", 300, tracker),
                readTool("read_c", 300, tracker));

        // 并发上限 2：A、B 已开始，C 仍在队列；60ms 后取消，C 不应开始执行
        Outcome outcome = run(tracker, tools, 2, 60);

        assertEquals(0, tracker.executions("read_c"), "停止后排队中的只读任务不应执行");
        assertNotNull(outcome.completion());
        assertEquals(ReActAgentService.CompletionStatus.INCOMPLETE, outcome.completion().status());
        assertEquals("CANCELLED", outcome.completion().reason());
    }

    @Test
    void 同类只读批的p95有收益() throws Exception {
        long serialP95 = p95OfRuns(1, 5);
        long parallelP95 = p95OfRuns(3, 5);

        System.out.printf("P5-08 只读批耗时: serial_p95=%dms parallel_p95=%dms%n", serialP95, parallelP95);
        assertTrue(parallelP95 < serialP95 * 0.8,
                "并行只读批应有明显 p95 收益: serial=" + serialP95 + "ms, parallel=" + parallelP95 + "ms");
    }

    // ========== 测试驱动 ==========

    private long p95OfRuns(int concurrency, int runs) throws Exception {
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            Tracker tracker = new Tracker();
            List<BaseTool> tools = List.of(
                    readTool("read_a", 80, tracker),
                    readTool("read_b", 80, tracker),
                    readTool("read_c", 80, tracker));
            Outcome outcome = run(tracker, tools, concurrency, 0);
            assertEquals(ReActAgentService.CompletionStatus.COMPLETED, outcome.completion().status());
            durations.add(outcome.durationMs());
        }
        durations.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(durations.size() * 0.95) - 1;
        return durations.get(Math.max(0, index));
    }

    private Outcome run(Tracker tracker, List<BaseTool> tools, int concurrency, long cancelAfterMs) throws Exception {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setUserId(USER_ID);
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.resolveProjectId(USER_ID, PROJECT_ID)).thenReturn(PROJECT_ID);
        when(projectService.getOwned(USER_ID, PROJECT_ID)).thenReturn(project);

        AgentRuleRepository ruleRepo = mock(AgentRuleRepository.class);
        when(ruleRepo.findEffectiveRules(USER_ID, PROJECT_ID, SESSION_ID)).thenReturn(List.of());
        CapabilityService capabilities = mock(CapabilityService.class);
        when(capabilities.has(any())).thenReturn(true);
        ToolRegistry registry = new ToolRegistry(mock(ApplicationContext.class),
                new SimpleMeterRegistry(), capabilities);
        tools.forEach(registry::register);

        ScriptedAgentClient client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofTools(scriptFor(tools)),
                ScriptedAgentClient.ScriptedTurn.ofText("完成。")));
        ReActAgentService agent = new ReActAgentService(client, registry, mock(DocumentService.class),
                projectService, mock(WorkspaceFileService.class), mock(McpToolBridge.class), ruleRepo,
                new ToolApprovalRegistry(), new ScopeConstraintService(), new SessionPermissionTracker(),
                new PromptCatalog(new MockEnvironment()),
                new com.capsule.agentkey.diagnostics.DiagnosticsService(
                        new AgentDiagnosticsProperties(), List.of(new JavacDiagnosticsProvider())),
                new VerifyService(new AgentVerifyProperties(), mock(CommandService.class)),
                new RepoMapService(new AgentRepoMapProperties(),
                        new com.capsule.agentkey.service.index.CodeIndexService(
                                new com.capsule.agentkey.service.index.LightweightCodeIndexer())));
        ReflectionTestUtils.setField(agent, "readonlyMaxConcurrency", concurrency);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<String> resultOrder = new CopyOnWriteArrayList<>();
        AtomicReference<ReActAgentService.Completion> completion = new AtomicReference<>();
        long started = System.nanoTime();
        try {
            if (cancelAfterMs > 0) {
                Thread canceller = new Thread(() -> {
                    try {
                        Thread.sleep(cancelAfterMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    cancelled.set(true);
                }, "test-cancel");
                canceller.setDaemon(true);
                canceller.start();
            }
            agent.execute("test-key",
                    new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO),
                    "并发读取测试", List.of(), USER_ID, PROJECT_ID, SESSION_ID,
                    "turn-parallel", false, "", "", "", "", cancelled,
                    new ReActAgentService.StreamCallback() {
                        @Override public void onThinking(String thought) {}
                        @Override public void onToolCall(String toolName, String arguments) {}
                        @Override public void onToolResult(String toolName, ToolResult result) {
                            resultOrder.add(toolName);
                        }
                        @Override public void onChunk(String chunk) {}
                        @Override public void onCompletion(ReActAgentService.Completion value) {
                            completion.set(value);
                        }
                        @Override public void onComplete() {}
                        @Override public void onError(Throwable t) {
                            throw new IllegalStateException(t);
                        }
                    });
        } finally {
            agent.shutdownReadOnlyToolPool();
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        return new Outcome(resultOrder, completion.get(), durationMs, tracker.maxInFlight());
    }

    private List<ScriptedAgentClient.ToolCallSpec> scriptFor(List<BaseTool> tools) {
        List<ScriptedAgentClient.ToolCallSpec> calls = new ArrayList<>();
        for (BaseTool tool : tools) {
            calls.add(new ScriptedAgentClient.ToolCallSpec(tool.getName(), "{}"));
        }
        return calls;
    }

    private record Outcome(List<String> resultOrder,
                           ReActAgentService.Completion completion,
                           long durationMs,
                           int maxInFlight) {
    }

    // ========== 模拟工具 ==========

    private static BaseTool readTool(String name, long latencyMs, Tracker tracker) {
        return tool(name, true, latencyMs, tracker);
    }

    private static BaseTool writeTool(String name, long latencyMs, Tracker tracker) {
        return tool(name, false, latencyMs, tracker);
    }

    private static BaseTool tool(String name, boolean readOnly, long latencyMs, Tracker tracker) {
        return new BaseTool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of("type", "object"); }
            @Override public boolean readOnly() { return readOnly; }
            @Override public ToolResult execute(JsonNode arguments) {
                tracker.enter(name, latencyMs);
                return ToolResult.ok(name);
            }
        };
    }

    /** 记录并发度与执行顺序 */
    private static final class Tracker {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final List<String> startOrder = new CopyOnWriteArrayList<>();
        private final Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();

        void enter(String name, long latencyMs) {
            startOrder.add(name);
            executions.computeIfAbsent(name, key -> new AtomicInteger()).incrementAndGet();
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        }

        int maxInFlight() {
            return maxInFlight.get();
        }

        int executions(String name) {
            AtomicInteger count = executions.get(name);
            return count == null ? 0 : count.get();
        }

        List<String> startOrder() {
            return List.copyOf(startOrder);
        }
    }
}
