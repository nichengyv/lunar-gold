package com.capsule.agentkey.eval.longsession;

import com.capsule.agentkey.service.AgentPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** P7-02 数据集与 fixture；只负责离线形态校验和隔离工作区物化。 */
final class LongSessionDataset {
    static final Path DATASET_DIR = Paths.get("eval", "long-session-datasets");
    /** R7a/P8-04 预注册：每层至少 10 个可执行 case，否则 L8 统计结论不成立 */
    static final int MIN_CASES_PER_LEVEL = 10;
    /** R7a/P8-04 预注册：每层至少两个独立数据集（合成形态 + 真实工程形态），防止只重复同一任务 */
    static final int MIN_BASE_DATASETS_PER_LEVEL = 2;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ATTRIBUTIONS = Set.of(
            "PLAN_DRIFT", "NO_PROGRESS", "STEP_OMISSION", "CONTEXT_TRUNCATION",
            "MEMORY_MISSING", "HISTORY_REPETITION", "TOOL_RECOVERY",
            "APPROVAL_OR_SCOPE", "MODEL_OR_PROVIDER");

    private LongSessionDataset() {
    }

    /** 读取原始数据集（未展开重复），主要供离线形态检查与报告分组使用。 */
    static List<Spec> loadBase() throws IOException {
        List<Spec> specs = new ArrayList<>();
        try (Stream<Path> files = Files.list(DATASET_DIR)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                specs.add(MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8), Spec.class));
            }
        }
        return specs;
    }

    /** 按 replicas 展开为 n≥10 的 case 集合；case id 形如 {@code base-id#r01}。 */
    static List<Spec> loadAll() throws IOException {
        List<Spec> expanded = new ArrayList<>();
        for (Spec base : loadBase()) {
            int replicas = base.replicas() <= 0 ? 1 : base.replicas();
            for (int index = 1; index <= replicas; index++) {
                expanded.add(withId(base, baseId(base.id()) + "#r" + String.format("%02d", index)));
            }
        }
        return expanded;
    }

    static String baseId(String id) {
        if (id == null) return "";
        int cut = id.indexOf('#');
        return cut < 0 ? id : id.substring(0, cut);
    }

    static int replicaIndex(String id) {
        if (id == null) return 0;
        int cut = id.indexOf("#r");
        if (cut < 0) return 0;
        try {
            return Integer.parseInt(id.substring(cut + 2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Spec withId(Spec spec, String id) {
        return new Spec(spec.schemaVersion(), id, spec.level(), spec.description(), spec.minModelCalls(),
                spec.reasoning(), spec.permission(), spec.fixture(), spec.success(), spec.failureAttribution(),
                spec.requiredCoverage(), spec.memory(), spec.turns(), spec.replicas());
    }

    static List<String> validate(List<Spec> specs) {
        List<String> errors = new ArrayList<>();
        if (specs.isEmpty()) {
            errors.add("没有加载到任何长会话数据集");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        Set<String> levels = new HashSet<>();
        Set<String> validatedBases = new HashSet<>();
        Map<String, List<Spec>> byLevel = new LinkedHashMap<>();
        for (Spec spec : specs) {
            if (!ids.add(spec.id())) errors.add("重复用例 id: " + spec.id());
            if (spec.replicas() <= 0) errors.add(baseId(spec.id()) + ": replicas 必须为正数");
            levels.add(spec.level());
            byLevel.computeIfAbsent(spec.level(), key -> new ArrayList<>()).add(spec);
            if (validatedBases.add(baseId(spec.id()))) validateSpec(spec, errors);
        }
        if (!levels.equals(Set.of("L1", "L2", "L3"))) {
            errors.add("数据集级别必须完整覆盖 L1/L2/L3，实际=" + levels);
        }
        for (String level : List.of("L1", "L2", "L3")) {
            List<Spec> cases = byLevel.getOrDefault(level, List.of());
            if (cases.size() < MIN_CASES_PER_LEVEL) {
                errors.add(level + ": 至少 " + MIN_CASES_PER_LEVEL + " 个 case 才能满足 L8，实际=" + cases.size());
            }
            long bases = cases.stream().map(spec -> baseId(spec.id())).distinct().count();
            if (bases < MIN_BASE_DATASETS_PER_LEVEL) {
                errors.add(level + ": 至少 " + MIN_BASE_DATASETS_PER_LEVEL
                        + " 个独立数据集（合成 + 真实工程形态），实际=" + bases);
            }
        }
        return errors;
    }

    private static void validateSpec(Spec spec, List<String> errors) {
        String id = spec.id() == null ? "<missing>" : baseId(spec.id());
        if (spec.schemaVersion() != 1) errors.add(id + ": schemaVersion 必须为 1");
        if (spec.minModelCalls() <= 0) errors.add(id + ": minModelCalls 必须为正数");
        int expectedMin = switch (spec.level()) {
            case "L1" -> 20;
            case "L2" -> 40;
            case "L3" -> 100;
            default -> {
                errors.add(id + ": 未知 level=" + spec.level());
                yield Integer.MAX_VALUE;
            }
        };
        if (spec.minModelCalls() < expectedMin) {
            errors.add(id + ": 轮次下限 " + spec.minModelCalls() + " < " + expectedMin);
        }
        if (spec.turns() == null || spec.turns().isEmpty()) {
            errors.add(id + ": 至少一个 execute turn");
            return;
        }
        int capacity = AgentPolicy.Reasoning.from(spec.reasoning()).maxToolRounds() * spec.turns().size();
        if (capacity < spec.minModelCalls()) {
            errors.add(id + ": 推理档位总轮次容量不足=" + capacity);
        }
        if (spec.requiredCoverage().minExecuteTurns() > spec.turns().size()) {
            errors.add(id + ": minExecuteTurns 超过数据集 turn 数");
        }
        Set<String> prompts = new HashSet<>();
        for (Turn turn : spec.turns()) {
            if (turn.prompt() == null || turn.prompt().isBlank()) errors.add(id + ": 空 prompt");
            if (!prompts.add(render(turn.prompt(), turn))) errors.add(id + ": 重复 prompt 不能凑轮次");
            if (turn.first() > 0 && (turn.last() < turn.first())) {
                errors.add(id + ": turn first/last 范围无效");
            }
        }
        for (String attribution : spec.failureAttribution().values()) {
            if (!ATTRIBUTIONS.contains(attribution)) errors.add(id + ": 未知失败归因=" + attribution);
        }

        Map<String, String> files;
        Map<String, String> artifacts;
        try {
            files = expandFixture(spec.fixture());
            artifacts = expectedArtifacts(spec);
        } catch (IllegalArgumentException e) {
            errors.add(id + ": " + e.getMessage());
            return;
        }
        for (String path : files.keySet()) validatePath(id, path, errors);
        for (String path : artifacts.keySet()) {
            if (!files.containsKey(path)) errors.add(id + ": 成功断言没有对应 fixture 文件=" + path);
        }
        Set<String> seriesExpectations = new HashSet<>();
        for (Series series : spec.fixture().series()) {
            for (int index = series.start(); index < series.start() + series.count(); index++) {
                String rendered = render(series.expectedContainsTemplate(), series.module(), index);
                if (!seriesExpectations.add(rendered)) {
                    errors.add(id + ": series 成功断言重复，存在凑工作量风险=" + rendered);
                }
            }
        }

        switch (spec.level()) {
            case "L1" -> {
                if (spec.turns().size() != 1) errors.add("L1 应为单次 execute");
                if (artifacts.size() < 10) errors.add("L1 至少 10 个独立交付断言");
                if (spec.requiredCoverage().minUpdatePlanCalls() < 2
                        || spec.requiredCoverage().minToolFailures() < 1
                        || spec.requiredCoverage().minRecoveredTools() < 1) {
                    errors.add("L1 必须预注册计划更新与工具失败恢复门禁");
                }
                requireAttributionHints(spec, errors, "noProgress", "maxRounds", "planIncomplete", "toolRecovery");
            }
            case "L2" -> {
                if (spec.turns().size() < 3) errors.add("L2 至少三次 execute 以覆盖中途 scope 变化");
                if (artifacts.size() < 20) errors.add("L2 至少 20 个独立跨文件交付断言");
                long scopes = spec.turns().stream().map(Turn::scope).filter(s -> s != null && !s.isBlank()).distinct().count();
                if (scopes < 2) errors.add("L2 至少需要两个不同 scope");
                boolean midRunChange = false;
                for (int i = 1; i < spec.turns().size() - 1; i++) {
                    if (!spec.turns().get(i).scope().equals(spec.turns().get(i - 1).scope())) midRunChange = true;
                }
                if (!midRunChange) errors.add("L2 scope 变化必须发生在中途 turn");
                if (spec.requiredCoverage().minApprovals() < 1
                        || spec.requiredCoverage().minReviewAccepted() < 1
                        || spec.requiredCoverage().minScopeChanges() < 1
                        || spec.requiredCoverage().minScopeRejections() < 1) {
                    errors.add("L2 必须预注册审批、审查流和 scope 门禁");
                }
                requireAttributionHints(spec, errors, "approvalScope", "noProgress", "maxRounds", "missingArtifact");
            }
            case "L3" -> {
                if (spec.turns().size() < 10) errors.add("L3 至少十次 execute，确保早期历史被裁剪");
                if (artifacts.size() < 50) errors.add("L3 至少 50 个独立交付断言，禁止空调用凑百轮");
                Memory memory = spec.memory();
                if (memory == null || memory.anchor() == null || memory.anchor().isBlank()) {
                    errors.add("L3 缺少记忆锚点");
                } else {
                    if (memory.probeTurn() != spec.turns().size()) errors.add("L3 记忆探针必须在最后 turn");
                    if (memory.summaryAfterTurn() < 1 || memory.summaryAfterTurn() >= memory.probeTurn()) {
                        errors.add("L3 摘要必须在探针前注入");
                    }
                    int historyBeforeProbe = (memory.probeTurn() - 1) * 2;
                    if (historyBeforeProbe <= AgentPolicy.Reasoning.from(spec.reasoning()).historyCount(false)) {
                        errors.add("L3 探针前历史不足以裁掉首轮锚点");
                    }
                }
                if (!spec.requiredCoverage().requireMemoryProbe()
                        || spec.requiredCoverage().minSummaryInjectedCalls() < 1) {
                    errors.add("L3 必须预注册摘要注入与记忆探针门禁");
                }
                requireAttributionHints(spec, errors, "memory", "context", "repetition", "noProgress");
            }
            default -> {
            }
        }
    }

    private static void requireAttributionHints(Spec spec, List<String> errors, String... keys) {
        for (String key : keys) {
            if (!spec.failureAttribution().containsKey(key)) errors.add(spec.id() + ": 缺少失败归因 hint=" + key);
        }
    }

    private static void validatePath(String id, String path, List<String> errors) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || Paths.get(path).isAbsolute() || path.contains("..")) {
            errors.add(id + ": fixture 路径必须安全且相对=" + path);
        }
    }

    static void materialize(Spec spec, Path workspace) throws IOException {
        for (Map.Entry<String, String> file : expandFixture(spec.fixture()).entrySet()) {
            Path target = workspace.resolve(file.getKey()).normalize();
            if (!target.startsWith(workspace)) throw new IOException("fixture 路径越界: " + file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
    }

    static Map<String, String> expectedArtifacts(Spec spec) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Series series : spec.fixture().series()) {
            for (int index = series.start(); index < series.start() + series.count(); index++) {
                result.put(render(series.pathTemplate(), series.module(), index),
                        render(series.expectedContainsTemplate(), series.module(), index));
            }
        }
        result.putAll(spec.success().fileContains());
        return result;
    }

    static Map<String, String> expandFixture(Fixture fixture) {
        Map<String, String> files = new LinkedHashMap<>();
        for (FixtureFile file : fixture.files()) {
            if (files.putIfAbsent(file.path(), file.content()) != null) {
                throw new IllegalArgumentException("fixture 文件重复=" + file.path());
            }
        }
        for (Series series : fixture.series()) {
            if (series.start() < 1 || series.count() < 1) throw new IllegalArgumentException("series start/count 无效");
            for (int index = series.start(); index < series.start() + series.count(); index++) {
                String path = render(series.pathTemplate(), series.module(), index);
                String content = render(series.contentTemplate(), series.module(), index);
                if (files.putIfAbsent(path, content) != null) {
                    throw new IllegalArgumentException("fixture 文件重复=" + path);
                }
            }
        }
        return files;
    }

    static String render(Turn turn) {
        String prompt = render(turn.prompt(), turn);
        if (turn.scope() != null && !turn.scope().isBlank()) {
            prompt += "\n#scope: " + turn.scope();
        }
        return prompt;
    }

    private static String render(String template, Turn turn) {
        return template.replace("{first}", String.valueOf(turn.first()))
                .replace("{last}", String.valueOf(turn.last()));
    }

    private static String render(String template, String module, int index) {
        return template.replace("{index2}", String.format("%02d", index))
                .replace("{index}", String.valueOf(index))
                .replace("{module}", module == null ? "" : module);
    }

    static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    record Spec(int schemaVersion, String id, String level, String description, int minModelCalls,
                String reasoning, String permission, Fixture fixture, Success success,
                Map<String, String> failureAttribution, RequiredCoverage requiredCoverage,
                Memory memory, List<Turn> turns, int replicas) {
    }

    record Fixture(List<FixtureFile> files, List<Series> series) {
    }

    record FixtureFile(String path, String content) {
    }

    record Series(int start, int count, String module, String pathTemplate,
                  String contentTemplate, String expectedContainsTemplate) {
    }

    record Success(Map<String, String> fileContains) {
    }

    record RequiredCoverage(int minUpdatePlanCalls, int minToolFailures, int minRecoveredTools,
                            int minApprovals, int minScopeChanges, int minScopeRejections,
                            int minReviewAccepted, int minExecuteTurns, int minSummaryInjectedCalls,
                            boolean requireMemoryProbe) {
    }

    record Memory(String anchor, int summaryAfterTurn, int probeTurn) {
    }

    record Turn(String prompt, String scope, int first, int last) {
    }
}
