package com.capsule.agentkey.eval.longsession;

import com.capsule.agentkey.client.AgentClient;
import com.capsule.agentkey.client.AgentStreamCallback;
import com.capsule.agentkey.client.ProviderCircuitBreaker;
import com.capsule.agentkey.client.SpringAiAgentClient;
import com.capsule.agentkey.config.AgentDiagnosticsProperties;
import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.config.SpringAiChatModelConfig;
import com.capsule.agentkey.diagnostics.DiagnosticsService;
import com.capsule.agentkey.diagnostics.JavacDiagnosticsProvider;
import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.CapabilityService;
import com.capsule.agentkey.service.CommandService;
import com.capsule.agentkey.service.DocumentService;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.PromptCatalog;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.service.RepoMapService;
import com.capsule.agentkey.service.ScopeConstraintService;
import com.capsule.agentkey.service.SessionPermissionTracker;
import com.capsule.agentkey.service.ToolApprovalRegistry;
import com.capsule.agentkey.service.WorkspaceFileService;
import com.capsule.agentkey.service.index.CodeIndexService;
import com.capsule.agentkey.service.index.LightweightCodeIndexer;
import com.capsule.agentkey.tool.AskUserQuestionSchemaTool;
import com.capsule.agentkey.tool.AskUserQuestionTool;
import com.capsule.agentkey.tool.EditFileTool;
import com.capsule.agentkey.tool.FileChange;
import com.capsule.agentkey.tool.ListDirTool;
import com.capsule.agentkey.tool.ReadFileTool;
import com.capsule.agentkey.tool.SearchFilesTool;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.UpdatePlanTool;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.capsule.agentkey.tool.WriteFileTool;
import com.capsule.agentkey.verify.VerifyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P7-02 真实模型长会话基线。缺 Key 默认跳过；每次真实执行都必须显式设置环境变量。
 * 数据集、usage、工具覆盖和失败归因均落盘，不用脚本模型或字符估算冒充结果。
 */
public class LongSessionLiveRunner {

    static final String KEY_ENV = "LUNARGOLD_LIVE_API_KEY";
    static final String BASE_URL_ENV = "LUNARGOLD_LIVE_BASE_URL";
    static final String MODEL_ENV = "LUNARGOLD_LIVE_MODEL";
    static final String LEVELS_ENV = "P7_02_LEVELS";
    static final String REPEATS_ENV = "P7_02_REPEATS";
    static final String ASK_USER_ENV = "P7_02_ASK_USER";
    static final String MAX_SECONDS_ENV = "P7_02_MAX_SECONDS_PER_TURN";
    static final Path REPORT_DIR = Paths.get("eval", "runtime-reports");

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;

    @Test
    void runLiveBaseline() throws Exception {
        assumeTrue(System.getenv(KEY_ENV) != null && !System.getenv(KEY_ENV).isBlank(),
                "未设置 LUNARGOLD_LIVE_API_KEY，跳过 P7-02 真实模型基线（避免默认产生费用）");
        execute();
    }

    public static ObjectNode execute() throws Exception {
        String apiKey = System.getenv(KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("缺少 " + KEY_ENV);
        String baseUrl = envOr(BASE_URL_ENV, "https://api.deepseek.com");
        String model = envOr(MODEL_ENV, "deepseek-flash");
        int repeats = Math.max(1, intEnv(REPEATS_ENV, 1));
        boolean askUser = "on".equalsIgnoreCase(envOr(ASK_USER_ENV, "off"));
        long maxSeconds = Math.max(30, intEnv(MAX_SECONDS_ENV, 300));
        Set<String> levels = parseLevels(envOr(LEVELS_ENV, "L1"));

        List<LongSessionDataset.Spec> specs = new ArrayList<>(LongSessionDataset.loadAll());
        List<String> validation = LongSessionDataset.validate(specs);
        if (!validation.isEmpty()) throw new IllegalStateException("数据集校验失败: " + validation);
        specs.removeIf(spec -> !levels.contains(spec.level()));
        if (specs.isEmpty()) throw new IllegalArgumentException("没有匹配的数据集: " + levels);

        // 付费调用前的前置检查：渲染后的 prompt 不允许残留 {xxx} 占位符。
        // 上一轮基线就是因为 {first}/{last} 未替换，模型只能猜区间，导致整个结论作废。
        assertNoPlaceholders(specs);

        List<RunOutcome> outcomes = new ArrayList<>();
        for (int repeat = 1; repeat <= repeats; repeat++) {
            for (LongSessionDataset.Spec spec : specs) {
                System.out.printf("[P7-02] start level=%s repeat=%d/%d model=%s askUser=%s%n",
                        spec.level(), repeat, repeats, model, askUser ? "on" : "off");
                RunOutcome outcome = runOne(apiKey, baseUrl, model, spec, repeat, askUser, maxSeconds);
                outcomes.add(outcome);
                System.out.printf("[P7-02] done level=%s repeat=%d success=%s attribution=%s calls=%d tokens=%d wall=%dms%n",
                        spec.level(), repeat, outcome.success(), outcome.attribution(),
                        outcome.data().path("modelCalls").asInt(), outcome.data().path("totalTokens").asLong(),
                        outcome.data().path("wallMs").asLong());
            }
        }

        ObjectNode report = buildReport(model, baseUrl, repeats, levels, askUser, maxSeconds, outcomes);
        Path path = writeReport(report);
        System.out.println("P7-02 报告已写入: " + path.toAbsolutePath());
        System.out.println("结论: " + report.path("decision").path("summary").asText());
        return report;
    }

    private static RunOutcome runOne(String apiKey, String baseUrl, String model,
                                     LongSessionDataset.Spec spec, int repeat,
                                     boolean askUserEnabled, long maxSeconds) {
        long started = System.nanoTime();
        Metrics metrics = new Metrics();
        metrics.firstChunkMs = -1;
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("p7-02-" + spec.level().toLowerCase(Locale.ROOT) + "-");
            LongSessionDataset.materialize(spec, workspace);
            WorkspaceFileService fileService = workspaceFileService();
            Project project = new Project();
            project.setId(PROJECT_ID);
            project.setUserId(USER_ID);
            project.setWorkspacePath(workspace.toString());
            ProjectService projectService = mock(ProjectService.class);
            when(projectService.resolveProjectId(USER_ID, PROJECT_ID)).thenReturn(PROJECT_ID);
            when(projectService.getOwned(USER_ID, PROJECT_ID)).thenReturn(project);
            AgentRuleRepository ruleRepo = mock(AgentRuleRepository.class);
            when(ruleRepo.findEffectiveRules(USER_ID, PROJECT_ID, SESSION_ID)).thenReturn(List.of());
            CapabilityService capabilities = mock(CapabilityService.class);
            when(capabilities.has(any())).thenReturn(true);
            ToolRegistry tools = new ToolRegistry(mock(ApplicationContext.class), new SimpleMeterRegistry(), capabilities);
            tools.register(new ReadFileTool(projectService, fileService));
            tools.register(new ListDirTool(projectService, fileService));
            tools.register(new SearchFilesTool(projectService, fileService));
            PendingFileChangeRepository pendingRepo = mock(PendingFileChangeRepository.class);
            when(pendingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            tools.register(new WriteFileTool(projectService, fileService, pendingRepo));
            tools.register(new EditFileTool(projectService, fileService, pendingRepo));
            tools.register(new UpdatePlanTool());

            AskUserQuestionTool askTool = askUserEnabled ? new AskUserQuestionTool() : null;
            if (askUserEnabled) tools.register(new AskUserQuestionSchemaTool());
            CountingAgentClient client = new CountingAgentClient(new SpringAiAgentClient(
                    new EvalChatModelConfig(baseUrl), new ProviderCircuitBreaker(5, 30_000)), metrics);
            ToolApprovalRegistry approvals = new ToolApprovalRegistry();
            ReActAgentService agent = new ReActAgentService(client, tools, mock(DocumentService.class),
                    projectService, fileService, mock(McpToolBridge.class), ruleRepo, approvals,
                    new ScopeConstraintService(), new SessionPermissionTracker(),
                    new PromptCatalog(new MockEnvironment()),
                    new DiagnosticsService(new AgentDiagnosticsProperties(), List.of(new JavacDiagnosticsProvider())),
                    new VerifyService(new AgentVerifyProperties(), mock(CommandService.class)),
                    new RepoMapService(new AgentRepoMapProperties(),
                            new CodeIndexService(new LightweightCodeIndexer())));
            ReflectionTestUtils.setField(agent, "maxRepeatedToolCalls", 3);
            ReflectionTestUtils.setField(agent, "maxStagnantRounds", 4);
            ReflectionTestUtils.setField(agent, "maxSecondsPerRun", maxSeconds);
            if (askTool != null) ReflectionTestUtils.setField(agent, "askUserQuestionTool", askTool);

            AgentPolicy policy = new AgentPolicy(model, AgentPolicy.Reasoning.from(spec.reasoning()),
                    AgentPolicy.Permission.from(spec.permission()));
            List<ChatMessage> history = new ArrayList<>();
            String contextSummary = "";
            String previousScope = "";
            // P7-04：累积已完成文件区间，用于检查点摘要里告诉模型哪些文件已经处理过
            List<String> completedRanges = new ArrayList<>();
            try {
                for (int turnIndex = 0; turnIndex < spec.turns().size(); turnIndex++) {
                    LongSessionDataset.Turn turn = spec.turns().get(turnIndex);
                    String prompt = LongSessionDataset.render(turn);
                    if (!turn.scope().isBlank() && !turn.scope().equals(previousScope)
                            && !previousScope.isBlank()) {
                        metrics.scopeChanges++;
                    }
                    if (!turn.scope().isBlank()) previousScope = turn.scope();
                    metrics.executeTurns++;
                    history.add(message(ChatMessage.MessageRole.USER, prompt));

                    RunHarness runs = new RunHarness();
                    AgentRun run = runs.service.create(USER_ID, SESSION_ID, PROJECT_ID, null,
                            MAPPER.writeValueAsString(Map.of("prompt", prompt))).run();
                    runs.service.start(run.getId());
                    Capture capture = new Capture(metrics, runs.service, approvals, askTool, run.getId(), started);
                    agent.execute(apiKey, policy, prompt, List.copyOf(history.subList(0, history.size() - 1)),
                            USER_ID, PROJECT_ID, run.getId(), SESSION_ID,
                            "p7-02-" + spec.level() + "-" + repeat + "-" + (turnIndex + 1),
                            false, "", "", contextSummary, "", new AtomicBoolean(false), capture);
                    history.add(message(ChatMessage.MessageRole.ASSISTANT, capture.answer.toString()));
                    metrics.answers.add(capture.answer.toString());
                    // P7-04：turn 成功执行后，记录本 turn 的文件区间
                    if (capture.failure == null && turn.first() > 0 && turn.last() >= turn.first()) {
                        completedRanges.add("Unit " + turn.first() + "-" + turn.last());
                    }
                    if (capture.failure != null) {
                        break;
                    }
                    if (spec.memory() != null && turnIndex + 1 == spec.memory().summaryAfterTurn()) {
                        contextSummary = checkpointSummary(spec, metrics, turnIndex + 1, completedRanges);
                        metrics.checkpointSummaries++;
                    }
                }
            } finally {
                ReflectionTestUtils.invokeMethod(agent, "shutdownReadOnlyToolPool");
            }
            metrics.modelCalls = client.modelCalls.get();
            metrics.summaryInjectedCalls = client.summaryInjectedCalls.get();
            metrics.maxSummaryChars = client.maxSummaryChars.get();
            metrics.wallMs = (System.nanoTime() - started) / 1_000_000;
            return evaluate(spec, repeat, workspace, metrics);
        } catch (Exception e) {
            metrics.wallMs = (System.nanoTime() - started) / 1_000_000;
            metrics.errors.add(String.valueOf(e.getMessage()));
            ObjectNode data = metrics.toJson(spec, repeat);
            data.put("success", false);
            data.put("failureAttribution", attribution(spec, data));
            data.putArray("missingArtifacts");
            data.putArray("coverageFailures");
            return new RunOutcome(data, false, data.path("failureAttribution").asText());
        } finally {
            if (workspace != null) {
                try { LongSessionDataset.deleteRecursively(workspace); } catch (IOException ignored) { }
            }
        }
    }

    private static RunOutcome evaluate(LongSessionDataset.Spec spec, int repeat,
                                       Path workspace, Metrics metrics) throws IOException {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> artifact : LongSessionDataset.expectedArtifacts(spec).entrySet()) {
            Path path = workspace.resolve(artifact.getKey()).normalize();
            if (!Files.exists(path)) {
                missing.add(artifact.getKey() + " (missing)");
                continue;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (!content.contains(artifact.getValue())) missing.add(artifact.getKey());
        }
        metrics.missingArtifacts.addAll(missing);
        // 评测用户在工件核对后接受统一变更；未交付完整时不接受，不用工具审批冒充审查接受。
        metrics.reviewAccepted = missing.isEmpty() ? metrics.fileChanges : 0;

        LongSessionDataset.RequiredCoverage required = spec.requiredCoverage();
        checkCoverage(metrics, metrics.updatePlanCalls >= required.minUpdatePlanCalls(),
                "updatePlan", metrics.updatePlanCalls, required.minUpdatePlanCalls());
        checkCoverage(metrics, metrics.toolFailures >= required.minToolFailures(),
                "toolFailures", metrics.toolFailures, required.minToolFailures());
        checkCoverage(metrics, metrics.recoveredTools >= required.minRecoveredTools(),
                "recoveredTools", metrics.recoveredTools, required.minRecoveredTools());
        checkCoverage(metrics, metrics.approvals >= required.minApprovals(),
                "approvals", metrics.approvals, required.minApprovals());
        checkCoverage(metrics, metrics.scopeChanges >= required.minScopeChanges(),
                "scopeChanges", metrics.scopeChanges, required.minScopeChanges());
        checkCoverage(metrics, metrics.scopeRejections >= required.minScopeRejections(),
                "scopeRejections", metrics.scopeRejections, required.minScopeRejections());
        checkCoverage(metrics, metrics.reviewAccepted >= required.minReviewAccepted(),
                "reviewAccepted", metrics.reviewAccepted, required.minReviewAccepted());
        checkCoverage(metrics, metrics.executeTurns >= required.minExecuteTurns(),
                "executeTurns", metrics.executeTurns, required.minExecuteTurns());
        checkCoverage(metrics, metrics.summaryInjectedCalls >= required.minSummaryInjectedCalls(),
                "summaryInjectedCalls", metrics.summaryInjectedCalls, required.minSummaryInjectedCalls());
        checkCoverage(metrics, metrics.modelCalls >= spec.minModelCalls(),
                "modelCalls", metrics.modelCalls, spec.minModelCalls());
        if (required.minUpdatePlanCalls() > 0) {
            checkCoverage(metrics, !metrics.finalPlan.isEmpty() && metrics.planCompletionRate() >= 1.0,
                    "planCompletion", (long) (metrics.planCompletionRate() * 100), 100);
        }
        if (required.requireMemoryProbe()) {
            String finalAnswer = metrics.answers.isEmpty() ? "" : metrics.answers.get(metrics.answers.size() - 1);
            metrics.memoryProbePassed = finalAnswer.contains(spec.memory().anchor());
            checkCoverage(metrics, metrics.memoryProbePassed, "memoryProbe", metrics.memoryProbePassed ? 1 : 0, 1);
        }
        checkCoverage(metrics, metrics.incompleteTurns == 0, "incompleteTurns", metrics.incompleteTurns, 0);
        checkCoverage(metrics, metrics.errors.isEmpty(), "executionErrors", metrics.errors.size(), 0);

        ObjectNode data = metrics.toJson(spec, repeat);
        boolean success = missing.isEmpty() && metrics.coverageFailures.isEmpty();
        data.put("success", success);
        data.put("failureAttribution", success ? "NONE" : attribution(spec, data));
        return new RunOutcome(data, success, data.path("failureAttribution").asText());
    }

    private static void checkCoverage(Metrics metrics, boolean condition, String name, long actual, long minimum) {
        if (!condition) metrics.coverageFailures.add(name + "=" + actual + ", required=" + minimum);
    }

    /** 单一主归因，顺序与细化规格一致；只根据已发生指标，不推测上下文截断。 */
    static String attribution(LongSessionDataset.Spec spec, ObjectNode data) {
        Map<String, String> hints = spec.failureAttribution();
        List<String> errors = new ArrayList<>();
        data.path("errors").forEach(n -> errors.add(n.asText("").toLowerCase(Locale.ROOT)));
        boolean contextError = errors.stream().anyMatch(e -> e.contains("context length")
                || e.contains("context_length") || e.contains("maximum context") || e.contains("too many tokens"));
        if (contextError) return hints.getOrDefault("context", "CONTEXT_TRUNCATION");
        if (!errors.isEmpty()) return hints.getOrDefault("provider", "MODEL_OR_PROVIDER");
        if (data.path("noProgress").asInt() > 0) return hints.getOrDefault("noProgress", "NO_PROGRESS");
        if (data.path("maxRounds").asInt() > 0) return hints.getOrDefault("maxRounds", "PLAN_DRIFT");
        if (spec.requiredCoverage().requireMemoryProbe() && !data.path("memoryProbePassed").asBoolean()) {
            return hints.getOrDefault("memory", "MEMORY_MISSING");
        }
        if (data.path("repeatedCalls").asInt() >= 3 && data.path("missingArtifacts").size() > 0) {
            return hints.getOrDefault("repetition", "HISTORY_REPETITION");
        }
        String coverage = data.path("coverageFailures").toString();
        if (coverage.contains("approvals") || coverage.contains("scope") || coverage.contains("reviewAccepted")) {
            return hints.getOrDefault("approvalScope", "APPROVAL_OR_SCOPE");
        }
        if (data.path("planCompletionRate").asDouble() < 1.0
                && spec.requiredCoverage().minUpdatePlanCalls() > 0) {
            return hints.getOrDefault("planIncomplete", "STEP_OMISSION");
        }
        if (data.path("toolFailures").asInt() > 0
                && data.path("recoveredTools").asInt() < spec.requiredCoverage().minRecoveredTools()) {
            return hints.getOrDefault("toolRecovery", "TOOL_RECOVERY");
        }
        return hints.getOrDefault("missingArtifact", "STEP_OMISSION");
    }

    static ObjectNode buildReport(String model, String baseUrl, int repeats, Set<String> levels,
                                  boolean askUser, long maxSeconds, List<RunOutcome> outcomes) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("generated_at", LocalDateTime.now().toString());
        root.put("stage", "p7-02-long-session-live");
        root.put("model", model);
        root.put("base_url", baseUrl);
        root.put("repeats", repeats);
        root.put("levels", String.join(",", levels));
        root.put("ask_user", askUser ? "on" : "off");
        root.put("max_seconds_per_turn", maxSeconds);
        root.put("prompt_version", "coding-system@v1");
        root.put("api_key_persisted", false);
        ArrayNode runs = root.putArray("runs");
        outcomes.forEach(o -> runs.add(o.data()));

        ObjectNode byLevel = root.putObject("byLevel");
        for (String level : List.of("L1", "L2", "L3")) {
            List<RunOutcome> selected = outcomes.stream()
                    .filter(o -> level.equals(o.data().path("level").asText())).toList();
            if (selected.isEmpty()) continue;
            ObjectNode node = byLevel.putObject(level);
            int successes = (int) selected.stream().filter(RunOutcome::success).count();
            node.put("runs", selected.size());
            node.put("successes", successes);
            node.put("successRate", rate(successes, selected.size()));
            node.put("modelCallsMedian", median(selected.stream().map(o -> o.data().path("modelCalls").asLong()).toList()));
            node.put("totalTokensMedian", median(selected.stream().map(o -> o.data().path("totalTokens").asLong()).toList()));
            node.put("wallMsMedian", median(selected.stream().map(o -> o.data().path("wallMs").asLong()).toList()));
            ObjectNode failures = node.putObject("failureAttributions");
            selected.stream().filter(o -> !o.success()).forEach(o -> {
                String key = o.attribution();
                failures.put(key, failures.path(key).asInt() + 1);
            });
        }

        List<RunOutcome> failed = outcomes.stream().filter(o -> !o.success()).toList();
        long planFailures = failed.stream().filter(o -> Set.of("PLAN_DRIFT", "NO_PROGRESS", "STEP_OMISSION")
                .contains(o.attribution())).count();
        long contextFailures = failed.stream().filter(o -> Set.of("CONTEXT_TRUNCATION", "MEMORY_MISSING", "HISTORY_REPETITION")
                .contains(o.attribution())).count();
        long providerFailures = outcomes.stream().filter(o -> "MODEL_OR_PROVIDER".equals(o.attribution())).count();
        boolean shapeComplete = levels.equals(Set.of("L1", "L2", "L3")) && repeats >= 3
                && outcomes.size() == repeats * 3;
        double providerFailureRate = outcomes.isEmpty() ? 1.0 : (double) providerFailures / outcomes.size();
        boolean eligible = shapeComplete && providerFailureRate <= 0.05;
        boolean rule2 = !failed.isEmpty() && planFailures * 2 >= failed.size();
        boolean rule3 = !failed.isEmpty() && contextFailures * 2 >= failed.size();

        ObjectNode decision = root.putObject("decision");
        decision.put("eligible", eligible);
        decision.put("shapeComplete", shapeComplete);
        decision.put("providerFailureRate", providerFailureRate);
        decision.put("failedRuns", failed.size());
        decision.put("planRelatedFailures", planFailures);
        decision.put("contextRelatedFailures", contextFailures);
        decision.put("rule2PlanFirst", eligible && rule2);
        decision.put("rule3ContextMemory", eligible && rule3);
        String summary;
        if (!eligible) summary = "数据不足或 provider 失败率过高，只作冒烟/诊断，不触发 P7-03/P7-04";
        else if (rule2) summary = "规则 2 命中：评估 P7-03，且仍需证明 P7-06 不足以覆盖";
        else if (rule3) summary = "规则 3 命中：优先评估 P7-04 上下文与记忆";
        else summary = "规则 2/3 均未命中：Phase 7 可收口";
        decision.put("summary", summary);
        return root;
    }

    /**
     * P7-04：升级为记忆摘要，包含三层信息——
     * 1. 记忆锚点（来自数据集 memory.anchor）：让模型在长会话后仍能回答早期决策
     * 2. 已完成文件区间（累积 Turn.first/last）：让模型知道哪些文件已经处理过
     * 3. 进度计数 + 约束：保留原有进度和防重复约束
     */
    static String checkpointSummary(LongSessionDataset.Spec spec, Metrics metrics,
                                    int completedTurns, List<String> completedRanges) {
        StringBuilder sb = new StringBuilder("检查点（结构化执行摘要）：");
        sb.append("\n进度：").append(completedTurns).append("/").append(spec.turns().size())
                .append(" turns，工具调用 ").append(metrics.toolCalls)
                .append(" 次，文件变更 ").append(metrics.fileChanges).append(" 次");

        // P7-04：记忆锚点（L3 数据集强制有；非 L3 数据集降级跳过）
        LongSessionDataset.Memory memory = spec.memory();
        if (memory != null && memory.anchor() != null && !memory.anchor().isBlank()) {
            sb.append("\n记忆锚点：").append(memory.anchor())
                    .append("（会话开始时给出，必须保留到会话末尾；不要在非必要时复述）");
        }

        // P7-04：已完成文件区间
        if (!completedRanges.isEmpty()) {
            sb.append("\n已完成文件区间：").append(String.join(", ", completedRanges));
        }

        sb.append("\n约束：后续只处理当前用户指定的新区间，不要重复已完成文件");
        return sb.toString();
    }

    private static ChatMessage message(ChatMessage.MessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(USER_ID);
        message.setSessionId(SESSION_ID);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        return message;
    }

    private static WorkspaceFileService workspaceFileService() {
        WorkspaceFileService service = new WorkspaceFileService();
        ReflectionTestUtils.setField(service, "maxReadBytes", 102400);
        ReflectionTestUtils.setField(service, "maxWriteBytes", 524288);
        ReflectionTestUtils.setField(service, "maxSearchResults", 50);
        ReflectionTestUtils.setField(service, "maxListDepth", 4);
        return service;
    }

    /** 付费调用前的硬门槛：任一 turn 渲染后仍含 {xxx} 占位符就立刻失败，不产生任何模型调用。 */
    static void assertNoPlaceholders(List<LongSessionDataset.Spec> specs) {
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("\\{[a-zA-Z][a-zA-Z0-9]*}");
        for (LongSessionDataset.Spec spec : specs) {
            for (LongSessionDataset.Turn turn : spec.turns()) {
                java.util.regex.Matcher matcher = placeholder.matcher(LongSessionDataset.render(turn));
                if (matcher.find()) {
                    throw new IllegalStateException("数据集 " + spec.id() + " 的 prompt 残留未替换占位符 "
                            + matcher.group() + "，已拒绝发起付费调用");
                }
            }
        }
    }

    private static Path writeReport(ObjectNode report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Path path = REPORT_DIR.resolve("long-session-" + LocalDateTime.now().format(STAMP) + ".json");
        Files.writeString(path, MAPPER.writeValueAsString(report) + System.lineSeparator(), StandardCharsets.UTF_8);
        return path;
    }

    private static Set<String> parseLevels(String raw) {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String value : raw.split(",")) {
            String level = value.trim().toUpperCase(Locale.ROOT);
            if (!level.isEmpty()) result.add(level);
        }
        if (!Set.of("L1", "L2", "L3").containsAll(result)) throw new IllegalArgumentException("非法 levels=" + result);
        return result;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intEnv(String name, int fallback) {
        try { return Integer.parseInt(envOr(name, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static double rate(int value, int total) {
        return total == 0 ? 0 : Math.round(value * 10000.0 / total) / 10000.0;
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    record RunOutcome(ObjectNode data, boolean success, String attribution) { }

    static final class Metrics {
        int modelCalls;
        int usageCalls;
        long promptTokens;
        long completionTokens;
        long maxPromptTokens;
        int executeTurns;
        int toolCalls;
        int toolFailures;
        int recoveredTools;
        int repeatedCalls;
        int updatePlanCalls;
        int approvals;
        int scopeChanges;
        int scopeRejections;
        int reviewAccepted;
        int summaryInjectedCalls;
        int maxSummaryChars;
        int checkpointSummaries;
        int fileChanges;
        int noProgress;
        int maxRounds;
        int incompleteTurns;
        int askUserQuestions;
        long wallMs;
        long firstChunkMs;
        boolean memoryProbePassed;
        boolean previousToolFailed;
        final Set<String> signatures = new HashSet<>();
        final List<String> errors = new ArrayList<>();
        final List<String> answers = new ArrayList<>();
        final List<String> completionReasons = new ArrayList<>();
        final List<String> runStates = new ArrayList<>();
        final List<String> missingArtifacts = new ArrayList<>();
        final List<String> coverageFailures = new ArrayList<>();
        List<WorkspaceContext.PlanStep> finalPlan = List.of();

        double planCompletionRate() {
            if (finalPlan.isEmpty()) return 0;
            long completed = finalPlan.stream().filter(s -> "completed".equals(s.status())).count();
            return (double) completed / finalPlan.size();
        }

        ObjectNode toJson(LongSessionDataset.Spec spec, int repeat) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", spec.id());
            node.put("level", spec.level());
            node.put("repeat", repeat);
            node.put("modelCalls", modelCalls);
            node.put("usageCalls", usageCalls);
            node.put("usageCoverage", modelCalls == 0 ? 0 : (double) usageCalls / modelCalls);
            node.put("promptTokens", promptTokens);
            node.put("completionTokens", completionTokens);
            node.put("totalTokens", promptTokens + completionTokens);
            node.put("contextPeakPromptTokens", maxPromptTokens);
            node.put("executeTurns", executeTurns);
            node.put("toolCalls", toolCalls);
            node.put("toolFailures", toolFailures);
            node.put("recoveredTools", recoveredTools);
            node.put("repeatedCalls", repeatedCalls);
            node.put("updatePlanCalls", updatePlanCalls);
            node.put("planCompletionRate", planCompletionRate());
            node.put("noProgress", noProgress);
            node.put("maxRounds", maxRounds);
            node.put("incompleteTurns", incompleteTurns);
            node.put("approvals", approvals);
            node.put("scopeChanges", scopeChanges);
            node.put("scopeRejections", scopeRejections);
            node.put("reviewAccepted", reviewAccepted);
            node.put("reviewMode", "EVAL_USER_ACCEPT_AFTER_ARTIFACT_CHECK");
            node.put("summaryInjectedCalls", summaryInjectedCalls);
            node.put("checkpointSummaries", checkpointSummaries);
            node.put("maxSummaryChars", maxSummaryChars);
            node.put("memoryProbePassed", memoryProbePassed);
            node.put("askUserQuestions", askUserQuestions);
            node.put("fileChanges", fileChanges);
            node.put("wallMs", wallMs);
            if (firstChunkMs >= 0) node.put("firstChunkMs", firstChunkMs); else node.putNull("firstChunkMs");
            node.set("completionReasons", MAPPER.valueToTree(completionReasons));
            node.set("runStates", MAPPER.valueToTree(runStates));
            node.set("errors", MAPPER.valueToTree(errors));
            node.set("missingArtifacts", MAPPER.valueToTree(missingArtifacts));
            node.set("coverageFailures", MAPPER.valueToTree(coverageFailures));
            ArrayNode answers = node.putArray("answers");
            this.answers.forEach(a -> answers.add(a.length() <= 1000 ? a : a.substring(0, 1000) + "…"));
            return node;
        }
    }

    private static final class Capture implements ReActAgentService.StreamCallback {
        private final Metrics metrics;
        private final AgentRunService runs;
        private final ToolApprovalRegistry approvals;
        private final AskUserQuestionTool askTool;
        private final long runId;
        private final long runStarted;
        private final StringBuilder answer = new StringBuilder();
        private long turnPromptTokens;
        private long turnCompletionTokens;
        private ReActAgentService.Completion completion;
        private Throwable failure;

        private Capture(Metrics metrics, AgentRunService runs, ToolApprovalRegistry approvals,
                        AskUserQuestionTool askTool, long runId, long runStarted) {
            this.metrics = metrics;
            this.runs = runs;
            this.approvals = approvals;
            this.askTool = askTool;
            this.runId = runId;
            this.runStarted = runStarted;
        }

        @Override public void onThinking(String thought) { }
        @Override public void onChunk(String chunk) {
            answer.append(chunk);
            if (metrics.firstChunkMs < 0) metrics.firstChunkMs = (System.nanoTime() - runStarted) / 1_000_000;
            runs.appendEvent(runId, AgentRunEvent.EventType.MODEL_CHUNK, Map.of("length", chunk.length()));
        }
        @Override public void onToolCall(String toolName, String arguments) {
            metrics.toolCalls++;
            if ("update_plan".equals(toolName)) metrics.updatePlanCalls++;
            String signature = toolName + ":" + (arguments == null ? "{}" : arguments);
            if (!metrics.signatures.add(signature)) metrics.repeatedCalls++;
            runs.appendEvent(runId, AgentRunEvent.EventType.TOOL_CALL,
                    Map.of("tool", toolName, "args", arguments == null ? "{}" : arguments));
        }
        @Override public void onToolResult(String toolName, ToolResult result) {
            if (result.isSuccess()) {
                if (metrics.previousToolFailed) metrics.recoveredTools++;
                metrics.previousToolFailed = false;
            } else {
                metrics.toolFailures++;
                metrics.previousToolFailed = true;
                String error = String.valueOf(result.getError()) + String.valueOf(result.getContent());
                if (error.contains("scope") || error.contains("范围")) metrics.scopeRejections++;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", toolName);
            payload.put("success", result.isSuccess());
            payload.put("content", result.getContent());
            payload.put("error", result.getError());
            runs.appendEvent(runId, AgentRunEvent.EventType.TOOL_RESULT, payload);
        }
        @Override public void onFileChange(FileChange change) { metrics.fileChanges++; }
        @Override public void onPlan(List<WorkspaceContext.PlanStep> steps) { metrics.finalPlan = List.copyOf(steps); }
        @Override public void onUsage(long prompt, long completionTokens) {
            metrics.usageCalls++;
            metrics.promptTokens += prompt;
            metrics.completionTokens += completionTokens;
            metrics.maxPromptTokens = Math.max(metrics.maxPromptTokens, prompt);
            turnPromptTokens += prompt;
            turnCompletionTokens += completionTokens;
        }
        @Override public void onApprovalRequired(String approvalId, String tool,
                                                 com.capsule.agentkey.dto.ApprovalPreview preview,
                                                 String risk, long expiresAt) {
            metrics.approvals++;
            runs.pauseForApproval(runId, approvalId, tool);
            approvals.resolve(approvalId, USER_ID, "APPROVE", false, null);
        }
        @Override public void onApprovalResolved(String approvalId, String decision, String source, String batchId) {
            runs.resumeAfterApproval(runId, decision, source, batchId);
        }
        @Override public void onUserQuestion(String questionId, String question, String header,
                                             List<Map<String, Object>> options, boolean multiSelect) {
            if (askTool == null) return;
            metrics.askUserQuestions++;
            runs.pauseForUserInput(runId, questionId, question, header, options, multiSelect);
            List<String> selected = new ArrayList<>();
            if (options != null && !options.isEmpty()) {
                Object label = options.get(0).get("label");
                if (label != null) selected.add(String.valueOf(label));
            }
            String custom = selected.isEmpty() ? "按你推荐的方案继续" : null;
            runs.resumeAfterUserInput(runId, questionId, selected, custom);
            askTool.resolve(runId, new AskUserQuestionTool.UserAnswer(questionId, selected, custom));
        }
        @Override public void onNoProgress(ReActAgentService.NoProgress event) {
            metrics.noProgress++;
            runs.appendEvent(runId, AgentRunEvent.EventType.NO_PROGRESS, MAPPER.convertValue(event, Map.class));
        }
        @Override public void onCompletion(ReActAgentService.Completion value) {
            completion = value;
            metrics.completionReasons.add(value.status() + ":" + value.reason());
            if (value.status() == ReActAgentService.CompletionStatus.INCOMPLETE) metrics.incompleteTurns++;
            if ("MAX_TOOL_ROUNDS".equals(value.reason())) metrics.maxRounds++;
            runs.appendEvent(runId, AgentRunEvent.EventType.COMPLETION, MAPPER.convertValue(value, Map.class));
        }
        @Override public void onComplete() {
            try {
                runs.recordUsage(runId, turnPromptTokens, turnCompletionTokens);
                AgentRun current = runs.findById(runId).orElseThrow();
                if (completion == null) {
                    metrics.incompleteTurns++;
                    metrics.errors.add("missing completion");
                    if (!AgentRun.isTerminal(current.getState())) runs.fail(runId, "MISSING_COMPLETION", "missing completion");
                } else if (!AgentRun.isTerminal(current.getState())) {
                    runs.succeed(runId, MAPPER.writeValueAsString(completion));
                }
                metrics.runStates.add(runs.findById(runId).orElseThrow().getState().name());
            } catch (Exception e) {
                failure = e;
                metrics.errors.add(String.valueOf(e.getMessage()));
            }
        }
        @Override public void onError(Throwable error) {
            failure = error;
            metrics.errors.add(String.valueOf(error.getMessage()));
            AgentRun current = runs.findById(runId).orElseThrow();
            if (!AgentRun.isTerminal(current.getState())) runs.fail(runId, "P7_02_ERROR", error.getMessage());
            metrics.runStates.add(runs.findById(runId).orElseThrow().getState().name());
        }
    }

    private static final class CountingAgentClient implements AgentClient {
        private final AgentClient delegate;
        private final Metrics metrics;
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicInteger summaryInjectedCalls = new AtomicInteger();
        private final AtomicInteger maxSummaryChars = new AtomicInteger();

        private CountingAgentClient(AgentClient delegate, Metrics metrics) {
            this.delegate = delegate;
            this.metrics = metrics;
        }
        @Override public StreamResult streamChat(String apiKey, String model, List<ObjectNode> messages,
                                                 List<ToolCallback> toolCallbacks, String memorySummary,
                                                 AgentStreamCallback callback) {
            modelCalls.incrementAndGet();
            if (memorySummary != null && !memorySummary.isBlank()) {
                summaryInjectedCalls.incrementAndGet();
                maxSummaryChars.accumulateAndGet(memorySummary.length(), Math::max);
            }
            return delegate.streamChat(apiKey, model, messages, toolCallbacks, memorySummary, callback);
        }
        @Override public String callSync(String apiKey, String model, List<ObjectNode> messages) {
            modelCalls.incrementAndGet();
            return delegate.callSync(apiKey, model, messages);
        }
        @Override public String callVision(String apiKey, String model, String instruction, List<String> imageDataUrls) {
            modelCalls.incrementAndGet();
            return delegate.callVision(apiKey, model, instruction, imageDataUrls);
        }
    }

    private static final class EvalChatModelConfig extends SpringAiChatModelConfig {
        private final String evalBaseUrl;
        private EvalChatModelConfig(String baseUrl) {
            super(baseUrl, "/v1/chat/completions", "", 3, 500, 60, 60, ObservationRegistry.NOOP);
            this.evalBaseUrl = baseUrl.replaceAll("/+$", "") + "/v1";
        }
        @Override public ChatModel create(String apiKey, String model) {
            return OpenAiChatModel.builder().options(OpenAiChatOptions.builder().model(model).apiKey(apiKey)
                            .baseUrl(evalBaseUrl)
                            .streamOptions(OpenAiChatOptions.StreamOptions.builder().includeUsage(true).build()).build())
                    .toolCallingManager(ToolCallingManager.builder().build())
                    .observationRegistry(ObservationRegistry.NOOP).build();
        }
    }

    /** 内存 repository + 真实 AgentRunService；不 mock Run 状态迁移。 */
    private static final class RunHarness {
        private final AgentRunService service;
        private RunHarness() {
            AgentRunRepository runRepo = mock(AgentRunRepository.class);
            AgentRunEventRepository eventRepo = mock(AgentRunEventRepository.class);
            Map<Long, AgentRun> runs = new HashMap<>();
            Map<Long, List<AgentRunEvent>> events = new HashMap<>();
            AtomicLong ids = new AtomicLong(100);
            when(runRepo.save(any())).thenAnswer(inv -> {
                AgentRun run = inv.getArgument(0);
                if (run.getId() == null) {
                    run.setId(ids.incrementAndGet());
                    run.setCreateTime(LocalDateTime.now());
                }
                runs.put(run.getId(), run);
                return run;
            });
            when(runRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(runs.get(inv.getArgument(0))));
            when(eventRepo.findMaxSeqByRunId(anyLong())).thenAnswer(inv -> {
                List<AgentRunEvent> list = events.get(inv.getArgument(0));
                return list == null || list.isEmpty() ? null : list.get(list.size() - 1).getSeq();
            });
            when(eventRepo.save(any())).thenAnswer(inv -> {
                AgentRunEvent event = inv.getArgument(0);
                event.setCreateTime(LocalDateTime.now());
                events.computeIfAbsent(event.getRunId(), ignored -> new ArrayList<>()).add(event);
                return event;
            });
            when(eventRepo.findByRunIdOrderBySeqAsc(anyLong())).thenAnswer(inv ->
                    List.copyOf(events.getOrDefault(inv.getArgument(0), List.of())));
            this.service = new AgentRunService(runRepo, eventRepo, MAPPER);
        }
    }
}
