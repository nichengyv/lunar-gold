package com.capsule.agentkey.eval.index;

import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.eval.index.IndexEvalFixtures.ExpectedSymbols;
import com.capsule.agentkey.service.RepoMapService;
import com.capsule.agentkey.service.index.CodeIndexService;
import com.capsule.agentkey.service.index.CodeIndexService.ScanOptions;
import com.capsule.agentkey.service.index.CodeIndexService.WorkspaceIndex;
import com.capsule.agentkey.service.index.CodeSymbol;
import com.capsule.agentkey.service.index.LightweightCodeIndexer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
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
import java.util.stream.Stream;

/**
 * P5-04 阶段 A：代码索引的确定性测量。
 *
 * <p>只测索引自身：800 文件工作区的 cold / warm / 增量耗时、复用率、repo map 预算占用、
 * 大仓库截断确定性、符号抽取准确率，以及本仓库的信息性测量。不调用任何外部模型，
 * 因此不能用来证明“收益”；收益结论由阶段 B 的真实模型 A/B 给出。</p>
 */
public class IndexBenefitEvalRunner {

    static final Path REPORT_DIR = Paths.get("eval", "index-reports");
    static final int FULL_MAX_FILES = 800;
    static final int MAX_DEPTH = 8;
    static final int REPO_MAP_MAX_FILES = 500;
    static final long MAX_FILE_BYTES = 256 * 1024;
    static final int COLD_RUNS = 3;
    static final int WARM_RUNS = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    @Test
    void runEval() throws IOException {
        execute();
    }

    /** 跑完整阶段 A 并写出报告；门禁测试复用同一入口，保证口径一致。 */
    public static Report execute() throws IOException {
        Path temp = Files.createTempDirectory("p5-04-index-eval-");
        try {
            Path workspace = IndexEvalFixtures.createSyntheticWorkspace(temp);

            ObjectNode report = MAPPER.createObjectNode();
            report.put("generated_at", LocalDateTime.now().toString());
            report.put("stage", "index-benefit");
            ObjectNode workspaceNode = report.putObject("workspace");
            workspaceNode.put("total_files", IndexEvalFixtures.JAVA_FILES + IndexEvalFixtures.TS_FILES
                    + IndexEvalFixtures.VUE_FILES + IndexEvalFixtures.DOC_FILES);
            workspaceNode.put("java_files", IndexEvalFixtures.JAVA_FILES);
            workspaceNode.put("ts_files", IndexEvalFixtures.TS_FILES);
            workspaceNode.put("vue_files", IndexEvalFixtures.VUE_FILES);
            workspaceNode.put("doc_files", IndexEvalFixtures.DOC_FILES);
            workspaceNode.put("max_depth", MAX_DEPTH);

            ScanMeasurement full = measureFullScan(workspace);
            ScanMeasurement repoMapDefault = measureRepoMapDefaultScan(workspace);
            RepoMapMeasurement repoMap = measureRepoMap(workspace);
            DeterminismMeasurement determinism = measureDeterminism(workspace);
            SymbolAccuracy accuracy = measureSymbolAccuracy();
            ObjectNode informational = measureRepository();

            report.set("scan_full", full.toJson());
            report.set("scan_repo_map_default", repoMapDefault.toJson());
            report.set("repo_map", repoMap.toJson());
            report.set("determinism", determinism.toJson());
            report.set("symbol_accuracy", accuracy.toJson());
            report.set("informational_repo_scan", informational);

            Path reportPath = writeReport(report);
            printSummary(report, reportPath);
            return new Report(report, reportPath);
        } finally {
            IndexEvalFixtures.deleteRecursively(temp);
        }
    }

    private static ScanMeasurement measureFullScan(Path workspace) throws IOException {
        // JIT 与首次磁盘读取会污染第一次 cold 样本，先做一次丢弃的预热
        new CodeIndexService(new LightweightCodeIndexer())
                .snapshot(workspace.toString(), new ScanOptions(MAX_DEPTH, FULL_MAX_FILES, MAX_FILE_BYTES));
        List<Long> cold = new ArrayList<>();
        for (int i = 0; i < COLD_RUNS; i++) {
            long started = System.nanoTime();
            new CodeIndexService(new LightweightCodeIndexer())
                    .snapshot(workspace.toString(), new ScanOptions(MAX_DEPTH, FULL_MAX_FILES, MAX_FILE_BYTES));
            cold.add(elapsedMillis(started));
        }

        CodeIndexService warmService = new CodeIndexService(new LightweightCodeIndexer());
        warmService.snapshot(workspace.toString(), new ScanOptions(MAX_DEPTH, FULL_MAX_FILES, MAX_FILE_BYTES));
        List<Long> warm = new ArrayList<>();
        WorkspaceIndex warmIndex = null;
        for (int i = 0; i < WARM_RUNS; i++) {
            long started = System.nanoTime();
            warmIndex = warmService.snapshot(workspace.toString(),
                    new ScanOptions(MAX_DEPTH, FULL_MAX_FILES, MAX_FILE_BYTES));
            warm.add(elapsedMillis(started));
        }

        List<Long> incremental = new ArrayList<>();
        WorkspaceIndex incrementalIndex = null;
        for (int i = 0; i < COLD_RUNS; i++) {
            Path target = workspace.resolve("src/main/java/com/demo/p0/service/Type" + (i * 10) + ".java");
            Files.writeString(target, Files.readString(target, StandardCharsets.UTF_8)
                    + "\n// incremental " + i + "\n", StandardCharsets.UTF_8);
            long started = System.nanoTime();
            incrementalIndex = warmService.snapshot(workspace.toString(),
                    new ScanOptions(MAX_DEPTH, FULL_MAX_FILES, MAX_FILE_BYTES));
            incremental.add(elapsedMillis(started));
        }

        Path removed = workspace.resolve("docs/guide/g0.md");
        Files.deleteIfExists(removed);
        WorkspaceIndex afterDelete = warmService.snapshot(workspace.toString(),
                new ScanOptions(MAX_DEPTH, FULL_MAX_FILES, MAX_FILE_BYTES));

        return new ScanMeasurement(cold, warm, incremental, warmIndex.stats(),
                incrementalIndex.stats(), afterDelete.stats());
    }

    private static ScanMeasurement measureRepoMapDefaultScan(Path workspace) {
        List<Long> cold = new ArrayList<>();
        WorkspaceIndex index = null;
        for (int i = 0; i < COLD_RUNS; i++) {
            long started = System.nanoTime();
            index = new CodeIndexService(new LightweightCodeIndexer())
                    .snapshot(workspace.toString(), ScanOptions.of(MAX_DEPTH, REPO_MAP_MAX_FILES));
            cold.add(elapsedMillis(started));
        }
        List<Long> warm = new ArrayList<>();
        CodeIndexService service = new CodeIndexService(new LightweightCodeIndexer());
        service.snapshot(workspace.toString(), ScanOptions.of(MAX_DEPTH, REPO_MAP_MAX_FILES));
        WorkspaceIndex warmIndex = null;
        for (int i = 0; i < WARM_RUNS; i++) {
            long started = System.nanoTime();
            warmIndex = service.snapshot(workspace.toString(), ScanOptions.of(MAX_DEPTH, REPO_MAP_MAX_FILES));
            warm.add(elapsedMillis(started));
        }
        return new ScanMeasurement(cold, warm, List.of(), warmIndex.stats(), warmIndex.stats(),
                warmIndex.stats(), index.limited());
    }

    private static RepoMapMeasurement measureRepoMap(Path workspace) {
        AgentRepoMapProperties properties = new AgentRepoMapProperties();
        List<Long> cold = new ArrayList<>();
        String text = "";
        for (int i = 0; i < COLD_RUNS; i++) {
            long started = System.nanoTime();
            text = new RepoMapService(properties,
                    new CodeIndexService(new LightweightCodeIndexer())).build(workspace.toString());
            cold.add(elapsedMillis(started));
        }
        RepoMapService warmService = new RepoMapService(properties,
                new CodeIndexService(new LightweightCodeIndexer()));
        warmService.build(workspace.toString());
        List<Long> warm = new ArrayList<>();
        for (int i = 0; i < WARM_RUNS; i++) {
            long started = System.nanoTime();
            warmService.build(workspace.toString());
            warm.add(elapsedMillis(started));
        }
        boolean truncated = text.contains("repo map 已截断");
        double ratio = properties.getMaxChars() == 0 ? 0
                : (double) text.length() / properties.getMaxChars();
        return new RepoMapMeasurement(cold, warm, text.length(), ratio, truncated);
    }

    private static DeterminismMeasurement measureDeterminism(Path workspace) throws IOException {
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < COLD_RUNS; i++) {
            WorkspaceIndex index = new CodeIndexService(new LightweightCodeIndexer())
                    .snapshot(workspace.toString(), ScanOptions.of(MAX_DEPTH, REPO_MAP_MAX_FILES));
            hashes.add(hash(index));
        }
        boolean identical = hashes.stream().distinct().count() == 1;
        return new DeterminismMeasurement(hashes, identical);
    }

    private static SymbolAccuracy measureSymbolAccuracy() throws IOException {
        LightweightCodeIndexer indexer = new LightweightCodeIndexer();
        Map<String, LanguageStats> byLanguage = new LinkedHashMap<>();
        int totalExpected = 0;
        int totalMatched = 0;
        int totalExtracted = 0;

        for (Map.Entry<String, ExpectedSymbols> entry : IndexEvalFixtures.labeledSymbolFiles().entrySet()) {
            String fileName = Paths.get(entry.getKey()).getFileName().toString();
            ExpectedSymbols expected = entry.getValue();
            Set<String> actual = new HashSet<>();
            for (CodeSymbol symbol : indexer.index(fileName, expected.content())) {
                actual.add(symbol.name());
            }
            Set<String> wanted = new HashSet<>(expected.names());
            Set<String> matched = new HashSet<>(wanted);
            matched.retainAll(actual);

            totalExpected += wanted.size();
            totalExtracted += actual.size();
            totalMatched += matched.size();
            byLanguage.computeIfAbsent(expected.language(), k -> new LanguageStats())
                    .add(wanted.size(), actual.size(), matched.size());
        }

        return new SymbolAccuracy(totalExpected, totalExtracted, totalMatched, byLanguage);
    }

    private static ObjectNode measureRepository() throws IOException {
        Path repository = Paths.get(".").toAbsolutePath().normalize();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("informational", true);
        node.put("path", repository.toString());
        try {
            long started = System.nanoTime();
            WorkspaceIndex index = new CodeIndexService(new LightweightCodeIndexer())
                    .snapshot(repository.toString(), ScanOptions.defaults());
            node.put("scan_ms", elapsedMillis(started));
            node.put("files", index.files().size());
            node.put("symbols", index.stats().symbolCount());
            node.put("limited", index.limited());
            String repoMap = new RepoMapService(new AgentRepoMapProperties(),
                    new CodeIndexService(new LightweightCodeIndexer())).build(repository.toString());
            node.put("repo_map_chars", repoMap.length());
            node.put("repo_map_truncated", repoMap.contains("repo map 已截断"));
        } catch (Exception e) {
            node.put("error", String.valueOf(e.getMessage()));
        }
        return node;
    }

    private static Path writeReport(ObjectNode report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Path path = REPORT_DIR.resolve("index-" + LocalDateTime.now().format(STAMP) + ".json");
        Files.writeString(path, MAPPER.writeValueAsString(report) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return path;
    }

    private static void printSummary(ObjectNode report, Path reportPath) {
        ObjectNode full = (ObjectNode) report.path("scan_full");
        ObjectNode repoMap = (ObjectNode) report.path("repo_map");
        ObjectNode accuracy = (ObjectNode) report.path("symbol_accuracy");
        System.out.println("\n========== P5-04 阶段 A：索引确定性测量 ==========");
        System.out.printf(Locale.ROOT,
                "800 文件 cold_p95=%dms warm_p95=%dms incremental_p95=%dms reuse_rate=%.1f%%%n",
                full.path("cold_p95_ms").asLong(), full.path("warm_p95_ms").asLong(),
                full.path("incremental_p95_ms").asLong(), full.path("reuse_rate").asDouble() * 100);
        System.out.printf(Locale.ROOT,
                "repo map 默认预算 cold_p95=%dms warm_p95=%dms chars=%d ratio=%.1f%% truncated=%s%n",
                repoMap.path("cold_p95_ms").asLong(), repoMap.path("warm_p95_ms").asLong(),
                repoMap.path("chars").asInt(), repoMap.path("budget_usage_ratio").asDouble() * 100,
                repoMap.path("truncated").asBoolean());
        System.out.printf(Locale.ROOT,
                "大仓库确定性=%s 符号 precision=%.1f%% recall=%.1f%%%n",
                report.path("determinism").path("identical").asBoolean(),
                accuracy.path("precision").asDouble() * 100,
                accuracy.path("recall").asDouble() * 100);
        System.out.println("报告已写入: " + reportPath);
    }

    private static String hash(WorkspaceIndex index) throws IOException {
        StringBuilder content = new StringBuilder();
        for (var file : index.files()) {
            content.append(file.path()).append('|');
            for (CodeSymbol symbol : file.symbols()) {
                content.append(symbol.display()).append(',');
            }
            content.append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("计算索引哈希失败", e);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    static long p95(List<Long> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    public record Report(ObjectNode report, Path reportPath) {
    }

    private record ScanMeasurement(List<Long> cold, List<Long> warm, List<Long> incremental,
                                   CodeIndexService.IndexStats warmStats,
                                   CodeIndexService.IndexStats incrementalStats,
                                   CodeIndexService.IndexStats afterDeleteStats,
                                   boolean limited) {

        ScanMeasurement(List<Long> cold, List<Long> warm, List<Long> incremental,
                        CodeIndexService.IndexStats warmStats,
                        CodeIndexService.IndexStats incrementalStats,
                        CodeIndexService.IndexStats afterDeleteStats) {
            this(cold, warm, incremental, warmStats, incrementalStats, afterDeleteStats, false);
        }

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("cold_p95_ms", p95(cold));
            node.put("warm_p95_ms", p95(warm));
            node.put("incremental_p95_ms", p95(incremental));
            node.set("cold_samples_ms", samples(cold));
            node.set("warm_samples_ms", samples(warm));
            node.set("incremental_samples_ms", samples(incremental));
            int scanned = warmStats.scannedFiles();
            int reused = warmStats.reusedFiles();
            node.put("reused_files", reused);
            node.put("scanned_files", scanned);
            node.put("reuse_rate", scanned + reused == 0 ? 0 : (double) reused / (scanned + reused));
            node.put("incremental_scanned_files", incrementalStats.scannedFiles());
            node.put("incremental_reused_files", incrementalStats.reusedFiles());
            node.put("removed_after_delete", afterDeleteStats.removedFiles());
            node.put("limited", limited);
            return node;
        }

        private ArrayNode samples(List<Long> values) {
            ArrayNode array = MAPPER.createArrayNode();
            values.forEach(array::add);
            return array;
        }
    }

    private record RepoMapMeasurement(List<Long> cold, List<Long> warm, int chars,
                                      double budgetUsageRatio, boolean truncated) {

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("cold_p95_ms", p95(cold));
            node.put("warm_p95_ms", p95(warm));
            node.put("chars", chars);
            node.put("budget_usage_ratio", budgetUsageRatio);
            node.put("truncated", truncated);
            return node;
        }
    }

    private record DeterminismMeasurement(List<String> hashes, boolean identical) {

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            ArrayNode array = MAPPER.createArrayNode();
            hashes.forEach(array::add);
            node.set("hashes", array);
            node.put("identical", identical);
            return node;
        }
    }

    private static final class LanguageStats {
        private int expected;
        private int extracted;
        private int matched;

        void add(int expected, int extracted, int matched) {
            this.expected += expected;
            this.extracted += extracted;
            this.matched += matched;
        }

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("expected", expected);
            node.put("extracted", extracted);
            node.put("matched", matched);
            node.put("precision", extracted == 0 ? 0 : (double) matched / extracted);
            node.put("recall", expected == 0 ? 0 : (double) matched / expected);
            return node;
        }
    }

    private record SymbolAccuracy(int expected, int extracted, int matched,
                                  Map<String, LanguageStats> byLanguage) {

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("expected", expected);
            node.put("extracted", extracted);
            node.put("matched", matched);
            node.put("precision", extracted == 0 ? 0 : (double) matched / extracted);
            node.put("recall", expected == 0 ? 0 : (double) matched / expected);
            ObjectNode languages = node.putObject("by_language");
            byLanguage.forEach((language, stats) -> languages.set(language, stats.toJson()));
            return node;
        }
    }
}
