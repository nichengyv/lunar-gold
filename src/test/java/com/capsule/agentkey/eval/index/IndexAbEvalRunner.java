package com.capsule.agentkey.eval.index;

import com.capsule.agentkey.client.ProviderCircuitBreaker;
import com.capsule.agentkey.client.SpringAiAgentClient;
import com.capsule.agentkey.config.AgentDiagnosticsProperties;
import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.config.SpringAiChatModelConfig;
import com.capsule.agentkey.diagnostics.DiagnosticsService;
import com.capsule.agentkey.diagnostics.JavacDiagnosticsProvider;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.mcp.McpToolBridge;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.service.AgentPolicy;
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
import com.capsule.agentkey.tool.ListDirTool;
import com.capsule.agentkey.tool.ReadFileTool;
import com.capsule.agentkey.tool.SearchFilesTool;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.UpdatePlanTool;
import com.capsule.agentkey.tool.WriteFileTool;
import com.capsule.agentkey.verify.VerifyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P5-04 阶段 B：repo map 开关的真实模型 A/B。
 *
 * <p>两配置只差 {@code agent.repo-map.enabled}；模型、提示词版本、工具集、权限、
 * 轮次与无进展阈值完全一致。每个用例重复 {@code P5_04_REPEATS} 次，两配置交替执行，
 * 聚合取中位数。缺 Key 时门禁跳过，绝不生成收益结论。</p>
 *
 * <p>前置缺口（Key / 模型）不满足时抛错，由门禁标记为跳过。</p>
 */
@EnabledIfEnvironmentVariable(named = "P5_04_MODEL_KEY", matches = ".+")
public class IndexAbEvalRunner {

    static final String MODEL_KEY_ENV = "P5_04_MODEL_KEY";
    static final String MODEL_BASE_URL_ENV = "P5_04_MODEL_BASE_URL";
    static final String MODEL_NAME_ENV = "P5_04_MODEL_NAME";
    static final String REPEATS_ENV = "P5_04_REPEATS";
    static final String MAX_CASES_ENV = "P5_04_MAX_CASES";

    static final Path REPORT_DIR = Paths.get("eval", "index-reports");
    static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    static final String DEFAULT_MODEL = "deepseek-chat";
    static final int DEFAULT_REPEATS = 3;
    static final int MIN_LOCATE_CASES = 12;
    static final int MIN_EDIT_CASES = 4;
    static final int MIN_FIXTURE_FILES = 36;
    static final double MIN_SYMBOL_RECALL = 0.8;
    static final double MAX_MODEL_FAILURE_RATE = 0.05;
    static final double SUCCESS_TOLERANCE_CASES = 1.0;
    static final double TOOL_CALL_REDUCTION = 0.2;
    static final double TOKEN_REDUCTION = 0.1;
    static final double TOKEN_OVERHEAD_LIMIT = 1.1;
    static final double WALL_TOLERANCE_FACTOR = 1.1;
    static final long WALL_TOLERANCE_MS = 200;
    /** B4：每 run 中位数口径下的噪声容差 = max(1, 该比例 × runs) */
    static final double REPEAT_NOISE_RATIO = 0.02;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final long USER_ID = 1L;
    private static final long PROJECT_ID = 2L;
    private static final long SESSION_ID = 3L;

    @Test
    void runEval() throws Exception {
        execute();
    }

    /** 执行完整 A/B 并写出报告；门禁测试复用同一入口。 */
    public static Outcome execute() throws Exception {
        String apiKey = System.getenv(MODEL_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("前置缺口：未设置 " + MODEL_KEY_ENV);
        }
        String baseUrl = envOr(MODEL_BASE_URL_ENV, DEFAULT_BASE_URL);
        String model = envOr(MODEL_NAME_ENV, DEFAULT_MODEL);
        int repeats = Math.max(1, intEnv(REPEATS_ENV, DEFAULT_REPEATS));
        int maxCases = intEnv(MAX_CASES_ENV, 0);
        // D1 判的是设计数据集形态；D2 的 info_gap 会从实际运行集中剔除用例，不能反过来把 D1 判失败
        List<IndexAbFixtures.Case> designedCases = IndexAbFixtures.cases();
        List<IndexAbFixtures.Case> cases = new ArrayList<>(designedCases);
        if (maxCases > 0 && cases.size() > maxCases) {
            cases = new ArrayList<>(cases.subList(0, maxCases));
        }

        Path temp = Files.createTempDirectory("p5-04-index-ab-");
        try {
            Path coverageWorkspace = IndexAbFixtures.create(temp.resolve("coverage"));
            String repoMapText = new RepoMapService(new AgentRepoMapProperties(),
                    new CodeIndexService(new LightweightCodeIndexer()))
                    .build(coverageWorkspace.toString());
            List<String> infoGaps = new ArrayList<>();
            cases.removeIf(c -> {
                if (!"locate".equals(c.category())) {
                    return false;
                }
                boolean covered = repoMapText.contains(c.keyword());
                if (!covered) {
                    infoGaps.add(c.id() + " -> " + c.keyword());
                }
                return !covered;
            });

            Map<String, List<RunOutcome>> outcomes = new LinkedHashMap<>();
            outcomes.put("on", new ArrayList<>());
            outcomes.put("off", new ArrayList<>());
            int sequence = 0;
            for (int repeat = 0; repeat < repeats; repeat++) {
                for (int index = 0; index < cases.size(); index++) {
                    IndexAbFixtures.Case evalCase = cases.get(index);
                    boolean onFirst = (repeat + index) % 2 == 0;
                    sequence++;
                    RunOutcome first = runOne(apiKey, baseUrl, model, evalCase, onFirst, repeat,
                            temp, sequence);
                    outcomes.get(onFirst ? "on" : "off").add(first);
                    sequence++;
                    RunOutcome second = runOne(apiKey, baseUrl, model, evalCase, !onFirst, repeat,
                            temp, sequence);
                    outcomes.get(onFirst ? "off" : "on").add(second);
                }
            }

            ObjectNode report = buildReport(model, baseUrl, repeats, designedCases, cases, infoGaps,
                    outcomes, repoMapText.length());
            Path reportPath = writeReport(report);
            printSummary(report, reportPath);
            return new Outcome(report, reportPath);
        } finally {
            IndexEvalFixtures.deleteRecursively(temp);
        }
    }

    private static RunOutcome runOne(String apiKey, String baseUrl, String model,
                                     IndexAbFixtures.Case evalCase, boolean repoMapEnabled,
                                     int repeat, Path temp, int sequence) {
        Path workspace = null;
        long started = System.nanoTime();
        try {
            workspace = IndexAbFixtures.create(temp.resolve("run-" + sequence));
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
            ToolRegistry tools = new ToolRegistry(mock(ApplicationContext.class),
                    new SimpleMeterRegistry(), capabilities);
            tools.register(new ReadFileTool(projectService, fileService));
            tools.register(new ListDirTool(projectService, fileService));
            tools.register(new SearchFilesTool(projectService, fileService));
            PendingFileChangeRepository pendingRepo = mock(PendingFileChangeRepository.class);
            when(pendingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            tools.register(new WriteFileTool(projectService, fileService, pendingRepo));
            tools.register(new UpdatePlanTool());

            SpringAiChatModelConfig modelConfig = new EvalChatModelConfig(baseUrl);
            SpringAiAgentClient client = new SpringAiAgentClient(modelConfig,
                    new ProviderCircuitBreaker(5, 30_000));

            AgentRepoMapProperties repoMapProperties = new AgentRepoMapProperties();
            repoMapProperties.setEnabled(repoMapEnabled);
            DiagnosticsService diagnostics = new DiagnosticsService(
                    new AgentDiagnosticsProperties(), List.of(new JavacDiagnosticsProvider()));
            ReActAgentService agent = new ReActAgentService(client, tools, mock(DocumentService.class),
                    projectService, fileService, mock(McpToolBridge.class), ruleRepo,
                    new ToolApprovalRegistry(), new ScopeConstraintService(),
                    new SessionPermissionTracker(), new PromptCatalog(new MockEnvironment()), diagnostics,
                    new VerifyService(new AgentVerifyProperties(), mock(CommandService.class)),
                    new RepoMapService(repoMapProperties,
                            new CodeIndexService(new LightweightCodeIndexer())));
            ReflectionTestUtils.setField(agent, "maxRepeatedToolCalls", 3);
            ReflectionTestUtils.setField(agent, "maxStagnantRounds", 4);

            Capture capture = new Capture(evalCase.targetPath());
            AgentPolicy policy = new AgentPolicy(model, AgentPolicy.Reasoning.FAST,
                    AgentPolicy.Permission.AUTO);
            agent.execute(apiKey, policy, evalCase.prompt(), List.of(), USER_ID, PROJECT_ID,
                    SESSION_ID, "turn-" + evalCase.id() + "-" + repeat + "-" + repoMapEnabled,
                    false, "", "", "", "", new AtomicBoolean(false), capture);
            long wallMs = (System.nanoTime() - started) / 1_000_000;
            return evaluate(evalCase, workspace, capture, wallMs, repoMapEnabled);
        } catch (Exception e) {
            long wallMs = (System.nanoTime() - started) / 1_000_000;
            return RunOutcome.failure(evalCase, repoMapEnabled, wallMs,
                    String.valueOf(e.getMessage()));
        } finally {
            try {
                IndexEvalFixtures.deleteRecursively(temp.resolve("run-" + sequence));
            } catch (IOException ignored) {
            }
        }
    }

    private static RunOutcome evaluate(IndexAbFixtures.Case evalCase, Path workspace,
                                       Capture capture, long wallMs, boolean repoMapEnabled)
            throws IOException {
        boolean success;
        if ("locate".equals(evalCase.category())) {
            //extractFirstPath 会把路径小写化，比较前必须同样归一化目标路径，否则大小写差异会误判失败
            success = normalizePath(evalCase.targetPath()).equals(extractFirstPath(capture.answer.toString()));
        } else {
            Path target = workspace.resolve(evalCase.targetPath());
            String content = Files.exists(target)
                    ? Files.readString(target, StandardCharsets.UTF_8) : "";
            String compact = content.replaceAll("\\s+", "");
            boolean contains = compact.contains(evalCase.expectContains().replaceAll("\\s+", ""));
            boolean absent = evalCase.expectAbsent() == null
                    || !compact.contains(evalCase.expectAbsent().replaceAll("\\s+", ""));
            boolean finished = capture.completion != null
                    && capture.completion.status() != ReActAgentService.CompletionStatus.INCOMPLETE;
            success = contains && absent && finished;
        }
        return new RunOutcome(evalCase, repoMapEnabled, success, false, null,
                capture.toolCalls, capture.listSearchCalls, capture.signatures,
                capture.promptTokens, capture.completionTokens, wallMs, capture.noProgress,
                capture.readTarget, capture.answer.toString());
    }

    private static ObjectNode buildReport(String model, String baseUrl, int repeats,
                                          List<IndexAbFixtures.Case> designedCases,
                                          List<IndexAbFixtures.Case> cases, List<String> infoGaps,
                                          Map<String, List<RunOutcome>> outcomes,
                                          int repoMapChars) throws IOException {
        ConfigStats on = ConfigStats.of(outcomes.get("on"));
        ConfigStats off = ConfigStats.of(outcomes.get("off"));

        IndexBenefitEvalRunner.Report phaseA = IndexBenefitEvalRunner.execute();
        boolean d3 = phaseA.report().path("determinism").path("identical").asBoolean();
        double symbolRecall = phaseA.report().path("symbol_accuracy").path("recall").asDouble();

        long locateCases = cases.stream().filter(c -> "locate".equals(c.category())).count();
        long editCases = cases.stream().filter(c -> "edit".equals(c.category())).count();
        long designedLocate = designedCases.stream().filter(c -> "locate".equals(c.category())).count();
        long designedEdit = designedCases.stream().filter(c -> "edit".equals(c.category())).count();
        int fixtureFiles = IndexAbFixtures.files().size();
        //spec D1 的“目标符号唯一”指 locate 用例的 keyword 唯一；edit 用例的 keyword 为空，不能参与去重
        boolean uniqueTargets = designedCases.stream()
                .filter(c -> "locate".equals(c.category()))
                .map(IndexAbFixtures.Case::keyword)
                .distinct().count() == designedLocate;

        boolean d1 = fixtureFiles >= MIN_FIXTURE_FILES && designedLocate >= MIN_LOCATE_CASES
                && designedEdit >= MIN_EDIT_CASES && uniqueTargets;
        boolean d2 = infoGaps.size() < locateCases && designedLocate >= MIN_LOCATE_CASES;
        boolean d4 = symbolRecall >= MIN_SYMBOL_RECALL;
        int totalRuns = on.runs + off.runs;
        double failureRate = totalRuns == 0 ? 1 : (double) (on.errors + off.errors) / totalRuns;
        boolean d5 = failureRate <= MAX_MODEL_FAILURE_RATE;
        boolean d6 = true;

        double tolerance = SUCCESS_TOLERANCE_CASES / Math.max(1, cases.size());
        boolean b1 = on.successRate() >= off.successRate() - tolerance;
        boolean promptWithinOverhead = on.medianPromptTokens() <= off.medianPromptTokens() * TOKEN_OVERHEAD_LIMIT;
        boolean callsReduced = on.medianLocateToolCalls()
                <= off.medianLocateToolCalls() * (1 - TOOL_CALL_REDUCTION);
        boolean tokensReduced = on.medianPromptTokens()
                <= off.medianPromptTokens() * (1 - TOKEN_REDUCTION);
        boolean b2 = promptWithinOverhead && (callsReduced || tokensReduced);
        boolean b3 = on.medianWallMs() <= off.medianWallMs() * WALL_TOLERANCE_FACTOR + WALL_TOLERANCE_MS;
        //B4：每 run 口径——避免调用总量波动影响判据（见 spec 2026-11-16 第 3 次修订记录）
        //  中位数比均值更抗 outlier；容差 = max(1, 2% × runs)
        double noiseFloor = Math.max(1.0, on.runs() * REPEAT_NOISE_RATIO);
        boolean b4 = on.repeatCallsPerRunMedian() <= off.repeatCallsPerRunMedian() + noiseFloor
                && on.noProgressPerRunMedian() <= off.noProgressPerRunMedian() + noiseFloor;
        boolean b5 = d1 && d2 && d3 && d4 && d5 && d6;

        String decision;
        if (!b5) {
            decision = "不判定：数据不足或不合格";
        } else if (b1 && b2 && b3 && b4) {
            decision = "保持默认开启";
        } else if (b1 && b3 && b4) {
            decision = "默认关闭（或改为按需注入）：上下文成本未回本";
        } else {
            decision = "默认关闭：成功率 / 时延 / 重复调用出现回退";
        }

        ObjectNode report = MAPPER.createObjectNode();
        report.put("generated_at", LocalDateTime.now().toString());
        report.put("stage", "index-ab");
        report.put("model", model);
        report.put("base_url", baseUrl);
        report.put("prompt_version", "coding-system@v1");
        report.put("date", LocalDate.now().toString());
        report.put("repeats", repeats);
        report.put("usage_capture", "stream_options.include_usage=true（只影响 usage 字段，不影响生成）");

        ObjectNode dataset = report.putObject("dataset");
        dataset.put("fixture_files", fixtureFiles);
        dataset.put("cases", cases.size());
        dataset.put("locate_cases", locateCases);
        dataset.put("edit_cases", editCases);
        dataset.set("info_gaps", MAPPER.valueToTree(infoGaps));
        dataset.put("repo_map_chars", repoMapChars);
        //离线配置与生产默认一致（workspace.max-list-depth=3）：search_files 看不到深度 >3 的文件，
        //基线只能靠 list_dir 逐层下钻。该限制会放大 repo map 的收益，结论只适用于当前生产配置。
        dataset.put("baseline_note",
                "search_files 受生产默认 workspace.max-list-depth=3 限制，深路径只能靠 list_dir 逐层导航");

        report.set("on", on.toJson());
        report.set("off", off.toJson());
        report.set("cases", caseComparison(cases, outcomes));

        ObjectNode validity = report.putObject("validity");
        validity.put("D1_dataset_shape", d1);
        validity.put("D2_repo_map_coverage", d2);
        validity.put("D3_index_deterministic", d3);
        validity.put("D4_symbol_recall", d4);
        validity.put("D4_symbol_recall_value", symbolRecall);
        validity.put("D5_model_failure_rate", d5);
        validity.put("D5_model_failure_rate_value", failureRate);
        validity.put("D6_same_model_and_prompt", d6);
        validity.put("model_failures", on.errors + off.errors);

        ObjectNode thresholds = report.putObject("thresholds");
        thresholds.put("B1_success_not_regressed", b1);
        thresholds.put("B2_net_benefit", b2);
        thresholds.put("B2_prompt_within_overhead", promptWithinOverhead);
        thresholds.put("B2_tool_calls_reduced", callsReduced);
        thresholds.put("B2_tokens_reduced", tokensReduced);
        thresholds.put("B3_latency_not_regressed", b3);
        thresholds.put("B4_repeat_calls_not_regressed", b4);
        //留痕：B4 现在统一用每 run 中位数口径，不受调用总量波动影响（见 spec 2026-11-16 第 3 次修订）
        thresholds.put("B4_mode", "per_run_median");
        thresholds.put("B4_noise_floor", noiseFloor);
        thresholds.put("B5_data_valid", b5);
        thresholds.put("decision", decision);
        return report;
    }

    private static ArrayNode caseComparison(List<IndexAbFixtures.Case> cases,
                                            Map<String, List<RunOutcome>> outcomes) {
        ArrayNode array = MAPPER.createArrayNode();
        for (IndexAbFixtures.Case evalCase : cases) {
            ObjectNode node = array.addObject();
            node.put("id", evalCase.id());
            node.put("category", evalCase.category());
            node.put("target", evalCase.targetPath());
            node.set("on", runsOf(outcomes.get("on"), evalCase));
            node.set("off", runsOf(outcomes.get("off"), evalCase));
        }
        return array;
    }

    private static ObjectNode runsOf(List<RunOutcome> runs, IndexAbFixtures.Case evalCase) {
        ObjectNode node = MAPPER.createObjectNode();
        List<RunOutcome> matched = runs.stream()
                .filter(r -> r.evalCase.id().equals(evalCase.id())).toList();
        node.put("runs", matched.size());
        node.put("successes", matched.stream().filter(r -> r.success).count());
        node.put("tool_calls_median", medianLong(matched.stream()
                .filter(r -> !r.error).mapToLong(r -> r.toolCalls).boxed().toList()));
        node.put("prompt_tokens_median", medianLong(matched.stream()
                .filter(r -> !r.error).mapToLong(r -> r.promptTokens).boxed().toList()));
        node.put("wall_ms_median", medianLong(matched.stream()
                .filter(r -> !r.error).mapToLong(r -> r.wallMs).boxed().toList()));
        node.put("read_targets", matched.stream().filter(r -> r.readTarget).count());
        //B4 用率值判定，工具调用基数很小时会被单次重复放大；逐用例给绝对次数便于复核
        node.put("repeat_calls", matched.stream()
                .mapToInt(r -> r.toolCalls - (int) new java.util.HashSet<>(r.signatures).size()).sum());
        //D5 要求失败用例单列：把失败原因写进报告，否则只能靠翻构建日志定位
        ArrayNode errors = node.putArray("error_messages");
        matched.stream().filter(r -> r.error)
                .forEach(r -> errors.add(truncate(String.valueOf(r.errorMessage), 200)));
        ArrayNode answers = node.putArray("answers");
        matched.forEach(r -> answers.add(truncate(r.answer, 240)));
        return node;
    }

    private static Path writeReport(ObjectNode report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Path path = REPORT_DIR.resolve("index-ab-" + LocalDateTime.now().format(STAMP) + ".json");
        Files.writeString(path, MAPPER.writeValueAsString(report) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return path;
    }

    private static void printSummary(ObjectNode report, Path reportPath) {
        ObjectNode on = (ObjectNode) report.path("on");
        ObjectNode off = (ObjectNode) report.path("off");
        System.out.println("\n========== P5-04 阶段 B：repo map A/B ==========");
        System.out.printf(Locale.ROOT, "model=%s repeats=%d cases=%d info_gaps=%d%n",
                report.path("model").asText(), report.path("repeats").asInt(),
                report.path("dataset").path("cases").asInt(),
                report.path("dataset").path("info_gaps").size());
        System.out.printf(Locale.ROOT,
                "ON  success=%.1f%% locate=%.1f%% edit=%.1f%% toolCalls=%d promptTokens=%d wall=%dms%n",
                on.path("success_rate").asDouble() * 100, on.path("locate_accuracy").asDouble() * 100,
                on.path("edit_success").asDouble() * 100, on.path("median_locate_tool_calls").asLong(),
                on.path("median_prompt_tokens").asLong(), on.path("median_wall_ms").asLong());
        System.out.printf(Locale.ROOT,
                "OFF success=%.1f%% locate=%.1f%% edit=%.1f%% toolCalls=%d promptTokens=%d wall=%dms%n",
                off.path("success_rate").asDouble() * 100, off.path("locate_accuracy").asDouble() * 100,
                off.path("edit_success").asDouble() * 100, off.path("median_locate_tool_calls").asLong(),
                off.path("median_prompt_tokens").asLong(), off.path("median_wall_ms").asLong());
        System.out.println("结论: " + report.path("thresholds").path("decision").asText());
        System.out.println("报告已写入: " + reportPath);
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String normalizePath(String text) {
        return text.replace('\\', '/').replace("./", "").toLowerCase(Locale.ROOT);
    }

    private static final java.util.regex.Pattern PATH_PATTERN = java.util.regex.Pattern.compile(
            "((?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+\\.(?:java|ts|vue|py|go|rs|cs|md))",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 取回答中出现的第一个文件路径；提示词要求“只回答相对路径”。 */
    static String extractFirstPath(String answer) {
        java.util.regex.Matcher matcher = PATH_PATTERN.matcher(normalizePath(answer));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String truncate(String text, int max) {
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max) + "…";
    }

    private static long medianLong(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    private static int medianInt(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    private static WorkspaceFileService workspaceFileService() {
        WorkspaceFileService service = new WorkspaceFileService();
        ReflectionTestUtils.setField(service, "maxReadBytes", 102400);
        ReflectionTestUtils.setField(service, "maxWriteBytes", 524288);
        ReflectionTestUtils.setField(service, "maxSearchResults", 50);
        ReflectionTestUtils.setField(service, "maxListDepth", 3);
        return service;
    }

    public record Outcome(ObjectNode report, Path reportPath) {
    }

    private record RunOutcome(IndexAbFixtures.Case evalCase, boolean repoMapEnabled,
                              boolean success, boolean error, String errorMessage,
                              int toolCalls, int listSearchCalls, List<String> signatures,
                              long promptTokens, long completionTokens, long wallMs,
                              int noProgress, boolean readTarget, String answer) {

        static RunOutcome failure(IndexAbFixtures.Case evalCase, boolean repoMapEnabled,
                                  long wallMs, String message) {
            return new RunOutcome(evalCase, repoMapEnabled, false, true, message,
                    0, 0, List.of(), 0, 0, wallMs, 0, false, "");
        }
    }

    private static final class Capture implements ReActAgentService.StreamCallback {
        private final StringBuilder answer = new StringBuilder();
        private final List<String> signatures = new ArrayList<>();
        private int toolCalls;
        private int listSearchCalls;
        private long promptTokens;
        private long completionTokens;
        private int noProgress;
        private boolean readTarget;
        private ReActAgentService.Completion completion;
        private final String targetPath;

        private Capture(String targetPath) {
            this.targetPath = targetPath;
        }

        @Override
        public void onThinking(String thought) {
        }

        @Override
        public void onToolResult(String toolName, com.capsule.agentkey.tool.ToolResult result) {
        }

        @Override
        public void onChunk(String chunk) {
            answer.append(chunk);
        }

        @Override
        public void onToolCall(String toolName, String arguments) {
            toolCalls++;
            signatures.add(toolName + "|" + arguments);
            if ("list_dir".equals(toolName) || "search_files".equals(toolName)) {
                listSearchCalls++;
            }
            if ("read_file".equals(toolName)
                    && arguments.replace("\\/", "/").contains(targetPath)) {
                readTarget = true;
            }
        }

        @Override
        public void onUsage(long prompt, long completionTokens) {
            this.promptTokens += prompt;
            this.completionTokens += completionTokens;
        }

        @Override
        public void onNoProgress(ReActAgentService.NoProgress noProgress) {
            this.noProgress++;
        }

        @Override
        public void onCompletion(ReActAgentService.Completion completion) {
            this.completion = completion;
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * 与生产同款模型工厂，唯一差异是显式要求流式响应返回 usage。
     * 该字段只影响统计，不影响生成与工具调用；生产配置未开启时，
     * 负责任的替代是报告标注“token 来自 usage 捕获”，不能拿字符数估算冒充。
     */
    private static final class EvalChatModelConfig extends SpringAiChatModelConfig {
        private final String evalBaseUrl;

        private EvalChatModelConfig(String baseUrl) {
            super(baseUrl, "/v1/chat/completions", "", 3, 500, 60, 60, ObservationRegistry.NOOP);
            this.evalBaseUrl = baseUrl.replaceAll("/+$", "") + "/v1";
        }

        @Override
        public ChatModel create(String apiKey, String model) {
            return OpenAiChatModel.builder()
                    .options(OpenAiChatOptions.builder()
                            .model(model)
                            .apiKey(apiKey)
                            .baseUrl(evalBaseUrl)
                            .streamOptions(OpenAiChatOptions.StreamOptions.builder()
                                    .includeUsage(true)
                                    .build())
                            .build())
                    .toolCallingManager(ToolCallingManager.builder().build())
                    .observationRegistry(ObservationRegistry.NOOP)
                    .build();
        }
    }

    private record ConfigStats(int runs, int errors, int successes, int locateRuns, int locateSuccesses,
                               int editRuns, int editSuccesses, List<Long> locateToolCalls,
                               List<Long> locateListSearchCalls, List<Long> promptTokens,
                               List<Long> completionTokens, List<Long> wallMs,
                               int repeatedCalls, int totalToolCalls, int noProgress,
                               List<Integer> perRunRepeats, List<Integer> perRunNoProgress) {

        static ConfigStats of(List<RunOutcome> outcomes) {
            int runs = 0;
            int errors = 0;
            int successes = 0;
            int locateRuns = 0;
            int locateSuccesses = 0;
            int editRuns = 0;
            int editSuccesses = 0;
            List<Long> locateToolCalls = new ArrayList<>();
            List<Long> locateListSearchCalls = new ArrayList<>();
            List<Long> promptTokens = new ArrayList<>();
            List<Long> completionTokens = new ArrayList<>();
            List<Long> wallMs = new ArrayList<>();
            int repeatedCalls = 0;
            int totalToolCalls = 0;
            int noProgress = 0;
            List<Integer> perRunRepeats = new ArrayList<>();
            List<Integer> perRunNoProgress = new ArrayList<>();
            for (RunOutcome outcome : outcomes) {
                runs++;
                if (outcome.error) {
                    errors++;
                    continue;
                }
                if (outcome.success) {
                    successes++;
                }
                if ("locate".equals(outcome.evalCase.category())) {
                    locateRuns++;
                    if (outcome.success) {
                        locateSuccesses++;
                    }
                    locateToolCalls.add((long) outcome.toolCalls);
                    locateListSearchCalls.add((long) outcome.listSearchCalls);
                } else {
                    editRuns++;
                    if (outcome.success) {
                        editSuccesses++;
                    }
                }
                promptTokens.add(outcome.promptTokens);
                completionTokens.add(outcome.completionTokens);
                wallMs.add(outcome.wallMs);
                totalToolCalls += outcome.toolCalls;
                int runRepeats = outcome.toolCalls - (int) new HashSet<>(outcome.signatures).size();
                repeatedCalls += runRepeats;
                noProgress += outcome.noProgress;
                perRunRepeats.add(runRepeats);
                perRunNoProgress.add(outcome.noProgress);
            }
            return new ConfigStats(runs, errors, successes, locateRuns, locateSuccesses,
                    editRuns, editSuccesses, locateToolCalls, locateListSearchCalls, promptTokens,
                    completionTokens, wallMs, repeatedCalls, totalToolCalls, noProgress,
                    perRunRepeats, perRunNoProgress);
        }

        int validRuns() {
            return runs - errors;
        }

        double successRate() {
            return validRuns() == 0 ? 0 : (double) successes / validRuns();
        }

        double locateAccuracy() {
            return locateRuns == 0 ? 0 : (double) locateSuccesses / locateRuns;
        }

        double editSuccess() {
            return editRuns == 0 ? 0 : (double) editSuccesses / editRuns;
        }

        double repeatCallRate() {
            return totalToolCalls == 0 ? 0 : (double) repeatedCalls / totalToolCalls;
        }

        long medianLocateToolCalls() {
            return medianLong(locateToolCalls);
        }

        long medianPromptTokens() {
            return medianLong(promptTokens);
        }

        long medianWallMs() {
            return medianLong(wallMs);
        }

        int repeatCallsPerRunMedian() {
            return medianInt(perRunRepeats);
        }

        int noProgressPerRunMedian() {
            return medianInt(perRunNoProgress);
        }

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("runs", runs);
            node.put("errors", errors);
            node.put("success_rate", successRate());
            node.put("locate_accuracy", locateAccuracy());
            node.put("edit_success", editSuccess());
            node.put("median_locate_tool_calls", medianLocateToolCalls());
            node.put("median_locate_list_search_calls", medianLong(locateListSearchCalls));
            node.put("median_prompt_tokens", medianPromptTokens());
            node.put("median_completion_tokens", medianLong(completionTokens));
            node.put("median_wall_ms", medianWallMs());
            node.put("repeat_call_rate", repeatCallRate());
            node.put("repeated_calls", repeatedCalls);
            node.put("total_tool_calls", totalToolCalls);
            node.put("no_progress_stops", noProgress);
            return node;
        }
    }
}
