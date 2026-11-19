package com.capsule.agentkey.eval;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.service.*;
import com.capsule.agentkey.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * P5-13：真实 ReAct 闭环评测。模型输出由脚本确定，其余消息组装、工具分发、审批、
 * 文件副作用、计划收口和 Run/Event 状态迁移均走生产实现。
 *
 * <p>数据集、报告与基线独立于 P5-02 组件评测；组件通过率不能当作真实任务成功率。</p>
 */
public class RuntimeEvalRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DATASETS_DIR = Paths.get("eval", "runtime-datasets");
    private static final Path FIXTURES_DIR = Paths.get("eval", "fixtures");
    private static final Path REPORTS_DIR = Paths.get("eval", "runtime-reports");
    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;

    @Test
    public void runEval() throws IOException {
        execute();
    }

    public static EvalRunner.RunResult execute() throws IOException {
        List<EvalCase> cases = loadCases();
        List<EvalRunner.CaseResult> results = new ArrayList<>();
        List<Outcome> outcomes = new ArrayList<>();
        for (EvalCase c : cases) {
            Path workspace = null;
            long started = System.nanoTime();
            try {
                workspace = copyFixture(c.fixture());
                Outcome outcome = runCase(c, workspace);
                long duration = (System.nanoTime() - started) / 1_000_000;
                outcome = outcome.withDuration(duration);
                outcomes.add(outcome);
                results.add(outcome.result());
            } catch (Exception e) {
                EvalRunner.CaseResult result = new EvalRunner.CaseResult(c.id(), c.category(), false,
                        Map.of("error", String.valueOf(e.getMessage())), 0, 0,
                        (System.nanoTime() - started) / 1_000_000);
                results.add(result);
                outcomes.add(new Outcome(result, false, false, 0, 0, 0));
            } finally {
                if (workspace != null) deleteRecursively(workspace);
            }
        }

        ObjectNode report = EvalRunner.buildReport(results);
        addRuntimeMetrics(report, outcomes);
        Path reportPath = writeReport(report);
        System.out.println("\n========== 真实 ReAct 评测 ==========");
        for (EvalRunner.CaseResult r : results) {
            System.out.printf("%-34s %-22s %s rounds=%d tokens=%d %s%n",
                    r.id(), r.category(), r.passed() ? "PASS" : "FAIL",
                    r.rounds(), r.tokens(), r.details());
        }
        System.out.printf("case_pass_rate=%.1f%% task_success_rate=%.1f%% verification_coverage=%.1f%% "
                        + "no_progress_stops=%d repeated_call_rate=%.1f%%%n",
                report.path("case_pass_rate").asDouble(), report.path("task_success_rate").asDouble(),
                report.path("verification_coverage").asDouble(), report.path("no_progress_stops").asInt(),
                report.path("repeated_call_rate").asDouble());
        System.out.println("报告已写入: " + reportPath);
        return new EvalRunner.RunResult(results, report, reportPath);
    }

    private static List<EvalCase> loadCases() throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        if (!Files.exists(DATASETS_DIR)) return cases;
        try (Stream<Path> files = Files.list(DATASETS_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.isBlank()) cases.add(EvalCase.fromJson(MAPPER.readTree(line)));
                }
            }
        }
        return cases;
    }

    private static Outcome runCase(EvalCase c, Path workspace) throws Exception {
        ScriptedAgentClient client = new ScriptedAgentClient(c.script());
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
        PendingFileChangeRepository pendingRepo = mock(PendingFileChangeRepository.class);
        when(pendingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        tools.register(new WriteFileTool(projectService, fileService, pendingRepo));
        tools.register(new UpdatePlanTool());

        ToolApprovalRegistry approvals = new ToolApprovalRegistry();
        com.capsule.agentkey.diagnostics.DiagnosticsService diagnostics =
                new com.capsule.agentkey.diagnostics.DiagnosticsService(
                        new com.capsule.agentkey.config.AgentDiagnosticsProperties(),
                        List.of(new com.capsule.agentkey.diagnostics.JavacDiagnosticsProvider()));
        ReActAgentService agent = new ReActAgentService(client, tools, mock(DocumentService.class), projectService,
                fileService, mock(McpToolBridge.class), ruleRepo, approvals, new ScopeConstraintService(),
                new SessionPermissionTracker(), new PromptCatalog(new MockEnvironment()), diagnostics,
                new com.capsule.agentkey.verify.VerifyService(
                        new com.capsule.agentkey.config.AgentVerifyProperties(),
                        mock(CommandService.class)),
                new com.capsule.agentkey.service.RepoMapService(
                        new com.capsule.agentkey.config.AgentRepoMapProperties(),
                        new com.capsule.agentkey.service.index.CodeIndexService(
                                new com.capsule.agentkey.service.index.LightweightCodeIndexer())));
        ReflectionTestUtils.setField(agent, "maxRepeatedToolCalls", c.expect().path("maxRepeats").asInt(3));
        ReflectionTestUtils.setField(agent, "maxStagnantRounds", c.expect().path("maxStagnantRounds").asInt(4));

        RunHarness runs = new RunHarness();
        AgentRunService runService = runs.service;
        AgentRun run = runService.create(USER_ID, SESSION_ID, PROJECT_ID, null,
                MAPPER.writeValueAsString(Map.of("prompt", c.prompt()))).run();
        runService.start(run.getId());

        AtomicReference<ReActAgentService.Completion> completion = new AtomicReference<>();
        AtomicReference<ReActAgentService.NoProgress> noProgress = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<List<WorkspaceContext.PlanStep>> finalPlan = new AtomicReference<>(List.of());
        AtomicInteger approvalCount = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<FileChange> fileChanges = new ArrayList<>();
        List<String> toolSignatures = new ArrayList<>();
        List<String> toolResults = new ArrayList<>();
        Set<String> seenSignatures = new HashSet<>();
        AtomicInteger repeatedCalls = new AtomicInteger();
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();
        String approvalDecision = c.expect().path("approvalDecision").asText("APPROVE");
        boolean cancelAfterFirstTool = c.expect().path("cancelAfterFirstTool").asBoolean(false);
        AgentPolicy policy = new AgentPolicy("scripted",
                AgentPolicy.Reasoning.from(c.expect().path("reasoning").asText("FAST")),
                AgentPolicy.Permission.from(c.expect().path("permission").asText("AUTO")));

        agent.execute("test-key", policy, c.prompt(), List.of(), USER_ID, PROJECT_ID, SESSION_ID,
                "turn-runtime-" + c.id(), false, "", "", "", "", cancelled,
                new ReActAgentService.StreamCallback() {
                    @Override public void onThinking(String thought) {}
                    @Override public void onChunk(String chunk) {
                        runService.appendEvent(run.getId(), AgentRunEvent.EventType.MODEL_CHUNK,
                                Map.of("text", chunk, "length", chunk.length()));
                    }
                    @Override public void onToolCall(String toolName, String arguments) {
                        String signature = canonical(toolName, arguments);
                        if (!seenSignatures.add(signature)) repeatedCalls.incrementAndGet();
                        toolSignatures.add(signature);
                        runService.appendEvent(run.getId(), AgentRunEvent.EventType.TOOL_CALL,
                                Map.of("tool", toolName, "args", arguments == null ? "{}" : arguments));
                        if (cancelAfterFirstTool) cancelled.set(true);
                    }
                    @Override public void onToolResult(String toolName, ToolResult result) {
                        toolResults.add((result.isSuccess() ? result.getContent() : result.getError()) + "");
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("tool", toolName);
                        payload.put("success", result.isSuccess());
                        payload.put("content", result.getContent());
                        payload.put("error", result.getError());
                        runService.appendEvent(run.getId(), AgentRunEvent.EventType.TOOL_RESULT, payload);
                    }
                    @Override public void onFileChange(FileChange change) { fileChanges.add(change); }
                    @Override public void onPlan(List<WorkspaceContext.PlanStep> steps) {
                        finalPlan.set(List.copyOf(steps));
                    }
                    @Override public void onUsage(long prompt, long completion) {
                        promptTokens.addAndGet(prompt);
                        completionTokens.addAndGet(completion);
                    }
                    @Override public void onApprovalRequired(String approvalId, String tool,
                                                             com.capsule.agentkey.dto.ApprovalPreview preview,
                                                             String risk, long expiresAt) {
                        approvalCount.incrementAndGet();
                        runService.pauseForApproval(run.getId(), approvalId, tool);
                        approvals.resolve(approvalId, USER_ID, approvalDecision, false, null);
                    }
                    @Override public void onApprovalResolved(String approvalId, String decision,
                                                             String source, String batchId) {
                        runService.resumeAfterApproval(run.getId(), decision, source, batchId);
                    }
                    @Override public void onNoProgress(ReActAgentService.NoProgress event) {
                        noProgress.set(event);
                        runService.appendEvent(run.getId(), AgentRunEvent.EventType.NO_PROGRESS,
                                MAPPER.convertValue(event, Map.class));
                    }
                    @Override public void onCompletion(ReActAgentService.Completion value) {
                        completion.set(value);
                        runService.appendEvent(run.getId(), AgentRunEvent.EventType.COMPLETION,
                                MAPPER.convertValue(value, Map.class));
                    }
                    @Override public void onComplete() {
                        try {
                            runService.recordUsage(run.getId(), promptTokens.get(), completionTokens.get());
                            AgentRun current = runService.findById(run.getId()).orElseThrow();
                            if (AgentRun.isTerminal(current.getState())) return;
                            ReActAgentService.Completion value = completion.get();
                            if (value != null && "CANCELLED".equals(value.reason())) {
                                runService.cancel(run.getId(), "runtime cancellation");
                            } else {
                                runService.succeed(run.getId(), MAPPER.writeValueAsString(value));
                            }
                        } catch (Exception e) {
                            failure.set(e);
                        }
                    }
                    @Override public void onError(Throwable error) {
                        failure.set(error);
                        AgentRun current = runService.findById(run.getId()).orElseThrow();
                        if (!AgentRun.isTerminal(current.getState())) {
                            runService.fail(run.getId(), "RUNTIME_EVAL_ERROR", error.getMessage());
                        }
                    }
                });

        AgentRun finalRun = runService.findById(run.getId()).orElseThrow();
        List<AgentRunEvent> events = runService.findEvents(run.getId(), null);
        List<String> mismatches = new ArrayList<>();
        JsonNode expect = c.expect();
        ReActAgentService.Completion actualCompletion = completion.get();
        if (failure.get() != null) mismatches.add("execution=" + failure.get());
        if (actualCompletion == null) {
            mismatches.add("missing completion");
        } else {
            String expectedStatus = expect.path("status").asText("COMPLETED");
            if (!expectedStatus.equals(actualCompletion.status().name())) {
                mismatches.add("completion=" + actualCompletion.status() + ", expected=" + expectedStatus);
            }
            if (expect.has("reason") && !expect.path("reason").asText().equals(actualCompletion.reason())) {
                mismatches.add("reason=" + actualCompletion.reason() + ", expected=" + expect.path("reason").asText());
            }
            if (expect.has("filesChanged")
                    && expect.path("filesChanged").asBoolean() != actualCompletion.filesChanged()) {
                mismatches.add("filesChanged=" + actualCompletion.filesChanged());
            }
        }
        String expectedRunState = expect.path("runState").asText("SUCCEEDED");
        if (!expectedRunState.equals(finalRun.getState().name())) {
            mismatches.add("runState=" + finalRun.getState() + ", expected=" + expectedRunState);
        }
        if (expect.has("noProgress")) {
            if (noProgress.get() == null || !expect.path("noProgress").asText().equals(noProgress.get().reason())) {
                mismatches.add("noProgress=" + (noProgress.get() == null ? "missing" : noProgress.get().reason()));
            }
        } else if (noProgress.get() != null) {
            mismatches.add("unexpected noProgress=" + noProgress.get().reason());
        }
        if (expect.has("approvals") && approvalCount.get() != expect.path("approvals").asInt()) {
            mismatches.add("approvals=" + approvalCount.get() + ", expected=" + expect.path("approvals").asInt());
        }
        if (expect.has("toolCalls") && toolSignatures.size() != expect.path("toolCalls").asInt()) {
            mismatches.add("toolCalls=" + toolSignatures.size() + ", expected=" + expect.path("toolCalls").asInt());
        }
        if (expect.has("modelCalls") && client.modelCalls() != expect.path("modelCalls").asInt()) {
            mismatches.add("modelCalls=" + client.modelCalls() + ", expected=" + expect.path("modelCalls").asInt());
        }
        if (expect.has("toolRounds") && client.toolRounds() != expect.path("toolRounds").asInt()) {
            mismatches.add("toolRounds=" + client.toolRounds() + ", expected=" + expect.path("toolRounds").asInt());
        }
        if (expect.has("attemptedToolCalls")
                && client.attemptedToolCalls() != expect.path("attemptedToolCalls").asInt()) {
            mismatches.add("attemptedToolCalls=" + client.attemptedToolCalls()
                    + ", expected=" + expect.path("attemptedToolCalls").asInt());
        }
        assertFiles(expect, workspace, mismatches);
        assertPlan(expect, finalPlan.get(), mismatches);
        if (expect.has("systemPromptContains")) {
            String systemPrompt = client.messageSnapshots().isEmpty() ? ""
                    : client.messageSnapshots().get(0).get(0).path("content").asText();
            if (!systemPrompt.contains(expect.path("systemPromptContains").asText())) {
                mismatches.add("system prompt missing: " + expect.path("systemPromptContains").asText());
            }
        }
        if (expect.has("messageContains")) {
            String messages = client.messageSnapshots().toString();
            if (!messages.contains(expect.path("messageContains").asText())) {
                mismatches.add("model messages missing: " + expect.path("messageContains").asText());
            }
        }
        if (expect.has("toolResultContains")
                && !toolResults.toString().contains(expect.path("toolResultContains").asText())) {
            mismatches.add("tool results missing: " + expect.path("toolResultContains").asText());
        }
        List<AgentRunEvent.EventType> eventTypes = events.stream().map(AgentRunEvent::getEventType).toList();
        if (!eventTypes.contains(AgentRunEvent.EventType.COMPLETION)) mismatches.add("missing COMPLETION event");
        if (expect.has("noProgress") && !eventTypes.contains(AgentRunEvent.EventType.NO_PROGRESS)) {
            mismatches.add("missing NO_PROGRESS event");
        }
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getSeq() != i + 1) mismatches.add("event sequence is not contiguous");
        }
        if (finalRun.getTotalTokens() != client.totalTokens()) {
            mismatches.add("run tokens=" + finalRun.getTotalTokens() + ", client=" + client.totalTokens());
        }

        Map<String, String> details = new LinkedHashMap<>();
        details.put("completion", actualCompletion == null ? "missing" : actualCompletion.status().name());
        details.put("run", finalRun.getState().name());
        details.put("tools", String.valueOf(toolSignatures.size()));
        details.put("file_changes", String.valueOf(fileChanges.size()));
        details.put("no_progress", noProgress.get() == null ? "none" : noProgress.get().reason());
        if (!mismatches.isEmpty()) details.put("mismatches", String.join("; ", mismatches));
        EvalRunner.CaseResult result = new EvalRunner.CaseResult(c.id(), c.category(), mismatches.isEmpty(),
                details, client.modelCalls(), client.totalTokens());
        boolean changed = actualCompletion != null && actualCompletion.filesChanged();
        boolean verified = changed && actualCompletion.status() == ReActAgentService.CompletionStatus.COMPLETED;
        return new Outcome(result, actualCompletion != null
                && actualCompletion.status() == ReActAgentService.CompletionStatus.COMPLETED,
                verified, noProgress.get() == null ? 0 : 1, toolSignatures.size(), repeatedCalls.get());
    }

    private static void assertFiles(JsonNode expect, Path workspace, List<String> mismatches) {
        expect.path("files").fields().forEachRemaining(entry -> {
            try {
                String actual = Files.readString(workspace.resolve(entry.getKey()));
                if (!entry.getValue().asText().equals(actual)) {
                    mismatches.add("file content mismatch: " + entry.getKey());
                }
            } catch (Exception e) {
                mismatches.add("file unreadable: " + entry.getKey());
            }
        });
        expect.path("fileContains").fields().forEachRemaining(entry -> {
            try {
                String actual = Files.readString(workspace.resolve(entry.getKey()));
                if (!actual.contains(entry.getValue().asText())) {
                    mismatches.add("file missing text: " + entry.getKey());
                }
            } catch (Exception e) {
                mismatches.add("file unreadable: " + entry.getKey());
            }
        });
        for (JsonNode path : expect.path("missingFiles")) {
            if (Files.exists(workspace.resolve(path.asText()))) mismatches.add("unexpected file: " + path.asText());
        }
    }

    private static void assertPlan(JsonNode expect, List<WorkspaceContext.PlanStep> plan, List<String> mismatches) {
        if (!expect.has("planStatuses")) return;
        List<String> expected = new ArrayList<>();
        expect.path("planStatuses").forEach(node -> expected.add(node.asText()));
        List<String> actual = plan.stream().map(WorkspaceContext.PlanStep::status).toList();
        if (!expected.equals(actual)) mismatches.add("plan=" + actual + ", expected=" + expected);
    }

    private static String canonical(String tool, String arguments) {
        try {
            Object value = MAPPER.readValue(arguments == null || arguments.isBlank() ? "{}" : arguments, Object.class);
            return tool + ":" + MAPPER.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (Exception e) {
            return tool + ":" + arguments;
        }
    }

    private static WorkspaceFileService workspaceFileService() {
        WorkspaceFileService service = new WorkspaceFileService();
        ReflectionTestUtils.setField(service, "maxReadBytes", 102400);
        ReflectionTestUtils.setField(service, "maxWriteBytes", 524288);
        ReflectionTestUtils.setField(service, "maxSearchResults", 50);
        ReflectionTestUtils.setField(service, "maxListDepth", 3);
        return service;
    }

    private static Path copyFixture(String fixtureName) throws IOException {
        Path src = FIXTURES_DIR.resolve(fixtureName);
        Path dst = Files.createTempDirectory("runtime-eval-");
        if (!Files.exists(src)) return dst;
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        return dst;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (IOException ignored) {}
            });
        }
    }

    private static void addRuntimeMetrics(ObjectNode report, List<Outcome> outcomes) {
        int total = outcomes.size();
        int tasks = (int) outcomes.stream().filter(Outcome::taskSucceeded).count();
        int noProgress = outcomes.stream().mapToInt(Outcome::noProgressStops).sum();
        int calls = outcomes.stream().mapToInt(Outcome::toolCalls).sum();
        int repeated = outcomes.stream().mapToInt(Outcome::repeatedCalls).sum();
        int changed = (int) outcomes.stream().filter(o -> o.result().details().get("file_changes") != null
                && !"0".equals(o.result().details().get("file_changes"))).count();
        int verified = (int) outcomes.stream().filter(Outcome::verified).count();
        report.put("case_pass_rate", rate((int) outcomes.stream().filter(o -> o.result().passed()).count(), total));
        report.put("task_succeeded", tasks);
        report.put("task_success_rate", rate(tasks, total));
        report.put("file_change_cases", changed);
        report.put("verified_file_cases", verified);
        report.put("verification_coverage", rate(verified, changed));
        report.put("no_progress_stops", noProgress);
        report.put("attempted_tool_calls", calls);
        report.put("repeated_tool_calls", repeated);
        report.put("repeated_call_rate", rate(repeated, calls));
        int modelCalls = outcomes.stream().mapToInt(o -> o.result().rounds()).sum();
        long totalTokens = outcomes.stream().mapToLong(o -> o.result().tokens()).sum();
        report.put("model_calls", modelCalls);
        report.put("avg_model_calls", total == 0 ? 0 : Math.round(modelCalls * 10.0 / total) / 10.0);
        report.put("total_tokens", totalTokens);
        report.put("avg_tokens", total == 0 ? 0 : Math.round((double) totalTokens / total));
    }

    private static double rate(int value, int total) {
        return total == 0 ? 0 : Math.round(value * 10000.0 / total) / 100.0;
    }

    private static Path writeReport(ObjectNode report) throws IOException {
        Files.createDirectories(REPORTS_DIR);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now());
        Path path = REPORTS_DIR.resolve(timestamp + ".json");
        MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(path, MAPPER.writeValueAsString(report));
        return path;
    }

    private record Outcome(EvalRunner.CaseResult result, boolean taskSucceeded, boolean verified,
                           int noProgressStops, int toolCalls, int repeatedCalls) {
        Outcome withDuration(long duration) {
            return new Outcome(result.withDuration(duration), taskSucceeded, verified,
                    noProgressStops, toolCalls, repeatedCalls);
        }
    }

    /** 内存 repository + 真实 AgentRunService；不启动数据库，也不 mock 状态迁移。 */
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
