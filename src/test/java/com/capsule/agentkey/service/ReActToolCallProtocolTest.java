package com.capsule.agentkey.service;

import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.diagnostics.DiagnosticsResult;
import com.capsule.agentkey.diagnostics.DiagnosticsService;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.eval.ScriptedAgentClient;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.tool.BaseTool;
import com.capsule.agentkey.tool.FileChange;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.capsule.agentkey.verify.VerifyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 协议回归：assistant(tool_calls) 之后必须紧跟覆盖全部 tool_call_id 的 tool 响应，
 * 中间不允许插入 user 消息，否则 DeepSeek 会以 400「insufficient tool messages」拒绝整段历史。
 *
 * <p>P7-02 真实基线暴露：一次响应并行发起多个写工具时，诊断 user 消息在第一个写工具后
 * 就被插进消息列表，把同组剩余的 tool 响应隔到了 user 之后。</p>
 */
class ReActToolCallProtocolTest {

    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;

    @Test
    void 并行写调用后诊断消息不得插入本组tool响应之间() {
        ScriptedAgentClient client = runTwoParallelWrites();

        assertTrue(client.messageSnapshots().size() >= 2, "至少应有工具轮与收尾轮两次模型请求");
        for (int round = 0; round < client.messageSnapshots().size(); round++) {
            final int roundNo = round + 1;
            List<String> violations = protocolViolations(client.messageSnapshots().get(round));
            assertTrue(violations.isEmpty(),
                    () -> "第 " + roundNo + " 次请求违反 tool_calls 协议：" + violations);
        }
    }

    private ScriptedAgentClient runTwoParallelWrites() {
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
        registry.register(writeTool("write_a"));
        registry.register(writeTool("write_b"));

        // 诊断结果模拟「分析了一个变更文件」，与 L3 连续改 Java 文件时的行为一致
        DiagnosticsResult diagnostics = mock(DiagnosticsResult.class);
        when(diagnostics.analyzedFiles()).thenReturn(1);
        when(diagnostics.available()).thenReturn(true);
        when(diagnostics.hasDiagnostics()).thenReturn(false);
        when(diagnostics.diagnostics()).thenReturn(List.of());
        when(diagnostics.formatForPrompt()).thenReturn("诊断：示例");
        DiagnosticsService diagnosticsService = mock(DiagnosticsService.class);
        when(diagnosticsService.collect(any(), any())).thenReturn(diagnostics);

        ScriptedAgentClient client = new ScriptedAgentClient(List.of(
                ScriptedAgentClient.ScriptedTurn.ofTools(List.of(
                        new ScriptedAgentClient.ToolCallSpec("write_a", "{}"),
                        new ScriptedAgentClient.ToolCallSpec("write_b", "{}"))),
                ScriptedAgentClient.ScriptedTurn.ofText("完成。")));

        ReActAgentService agent = new ReActAgentService(client, registry, mock(DocumentService.class),
                projectService, mock(WorkspaceFileService.class), mock(McpToolBridge.class), ruleRepo,
                new ToolApprovalRegistry(), new ScopeConstraintService(), new SessionPermissionTracker(),
                new PromptCatalog(new MockEnvironment()), diagnosticsService,
                new VerifyService(new AgentVerifyProperties(), mock(CommandService.class)),
                new RepoMapService(new AgentRepoMapProperties(),
                        new com.capsule.agentkey.service.index.CodeIndexService(
                                new com.capsule.agentkey.service.index.LightweightCodeIndexer())));
        try {
            agent.execute("test-key",
                    new AgentPolicy("scripted", AgentPolicy.Reasoning.FAST, AgentPolicy.Permission.AUTO),
                    "并行写入测试", List.of(), USER_ID, PROJECT_ID, SESSION_ID,
                    "turn-protocol", false, "", "", "", "", new AtomicBoolean(false),
                    new ReActAgentService.StreamCallback() {
                        @Override public void onThinking(String thought) {}
                        @Override public void onToolCall(String toolName, String arguments) {}
                        @Override public void onToolResult(String toolName, ToolResult result) {}
                        @Override public void onChunk(String chunk) {}
                        @Override public void onComplete() {}
                        @Override public void onError(Throwable t) {
                            throw new IllegalStateException(t);
                        }
                    });
        } finally {
            agent.shutdownReadOnlyToolPool();
        }
        return client;
    }

    /** 模拟写工具：每次执行都登记一次文件变更，触发诊断收集 */
    private static BaseTool writeTool(String name) {
        return new BaseTool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of("type", "object"); }
            @Override public ToolResult execute(JsonNode arguments) {
                WorkspaceContext.recordFileChange(new FileChange(
                        name + ".java", "modify", name, 1, 1, "diff"));
                return ToolResult.ok(name);
            }
        };
    }

    /** 校验每条 assistant(tool_calls) 之后紧跟的 tool 消息覆盖全部 id */
    static List<String> protocolViolations(List<ObjectNode> messages) {
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ObjectNode msg = messages.get(i);
            if (!"assistant".equals(msg.path("role").asText())) continue;
            JsonNode calls = msg.get("tool_calls");
            if (calls == null || !calls.isArray() || calls.isEmpty()) continue;

            Set<String> expected = new LinkedHashSet<>();
            calls.forEach(call -> expected.add(call.path("id").asText()));
            Set<String> answered = new LinkedHashSet<>();
            int j = i + 1;
            while (j < messages.size() && "tool".equals(messages.get(j).path("role").asText())) {
                answered.add(messages.get(j).path("tool_call_id").asText());
                j++;
            }
            if (!answered.containsAll(expected)) {
                String nextRole = j < messages.size() ? messages.get(j).path("role").asText() : "EOF";
                violations.add("expected=" + expected + ", answered=" + answered + ", nextRole=" + nextRole);
            }
        }
        return violations;
    }

    @Test
    void 协议校验器本身能识别user消息插入() {
        ObjectMapperHolder holder = new ObjectMapperHolder();
        ObjectNode assistant = holder.mapper().createObjectNode();
        assistant.put("role", "assistant");
        assistant.putArray("tool_calls").addObject().put("id", "call_1");
        assistant.withArray("tool_calls").addObject().put("id", "call_2");
        ObjectNode tool1 = holder.mapper().createObjectNode();
        tool1.put("role", "tool");
        tool1.put("tool_call_id", "call_1");
        ObjectNode user = holder.mapper().createObjectNode();
        user.put("role", "user");
        ObjectNode tool2 = holder.mapper().createObjectNode();
        tool2.put("role", "tool");
        tool2.put("tool_call_id", "call_2");

        assertEquals(1, protocolViolations(List.of(assistant, tool1, user, tool2)).size());
        assertTrue(protocolViolations(List.of(assistant, tool1, tool2, user)).isEmpty());
    }

    private static final class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.ObjectMapper mapper() { return mapper; }
    }
}
