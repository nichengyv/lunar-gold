package com.capsule.agentkey.eval.longsession;

import com.capsule.agentkey.service.AgentPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P7-02 L1/L2/L3 长会话数据集离线验证。
 *
 * <p>仅验证数据集 schema、门禁预注册、fixture 物化与路径安全，
 * 不触发 ReAct 循环或真实模型调用。
 */
class LongSessionDatasetTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() throws IOException {
        LongSessionDataset.deleteRecursively(tempDir);
    }

    @Test
    void allThreeDatasetsLoadAndValidate() throws IOException {
        List<LongSessionDataset.Spec> specs = LongSessionDataset.loadAll();
        assertNotNull(specs, "loadAll() 不应返回 null");
        assertTrue(specs.size() >= LongSessionDataset.MIN_CASES_PER_LEVEL * 3,
                () -> "展开后的 case 数不足：实际=" + specs.size());

        List<String> errors = LongSessionDataset.validate(specs);
        assertTrue(errors.isEmpty(), () -> "数据集校验发现错误：\n  - " + String.join("\n  - ", errors));
    }

    @Test
    void everyLevelHasAtLeastTenCasesAndTwoBaseDatasets() throws IOException {
        List<LongSessionDataset.Spec> specs = LongSessionDataset.loadAll();
        for (String level : List.of("L1", "L2", "L3")) {
            var selected = specs.stream().filter(s -> level.equals(s.level())).toList();
            assertTrue(selected.size() >= LongSessionDataset.MIN_CASES_PER_LEVEL,
                    () -> level + " 至少 " + LongSessionDataset.MIN_CASES_PER_LEVEL + " 个 case，实际=" + selected.size());
            long bases = selected.stream().map(s -> LongSessionDataset.baseId(s.id())).distinct().count();
            assertTrue(bases >= LongSessionDataset.MIN_BASE_DATASETS_PER_LEVEL,
                    () -> level + " 至少 " + LongSessionDataset.MIN_BASE_DATASETS_PER_LEVEL + " 个独立数据集，实际=" + bases);
            assertTrue(selected.stream().anyMatch(s -> LongSessionDataset.baseId(s.id()).startsWith("repo-")),
                    () -> level + " 必须包含真实工程形态数据集（repo-*）");
        }
    }

    @Test
    void expandedReplicaIdsKeepBaseAndReplicaIndex() throws IOException {
        List<LongSessionDataset.Spec> specs = LongSessionDataset.loadAll();
        LongSessionDataset.Spec first = specs.get(0);
        assertTrue(first.id().contains("#r01"), () -> "首条 case 应为 #r01，实际=" + first.id());
        assertEquals(LongSessionDataset.baseId(first.id()) + "#r01", first.id());
        assertTrue(LongSessionDataset.replicaIndex(first.id()) >= 1);

        long replicas = specs.stream()
                .filter(s -> LongSessionDataset.baseId(s.id()).equals(LongSessionDataset.baseId(first.id())))
                .count();
        assertEquals(first.replicas(), (int) replicas, "replicas 声明与实际展开数必须一致");
    }

    @Test
    void datasetsHaveDistinctIdsAndCompleteLevels() throws IOException {
        List<LongSessionDataset.Spec> specs = LongSessionDataset.loadAll();

        var ids = specs.stream().map(LongSessionDataset.Spec::id).distinct().toList();
        assertEquals(specs.size(), ids.size(), "展开后的 case id 必须唯一");

        var levels = specs.stream().map(LongSessionDataset.Spec::level).distinct().toList();
        assertTrue(levels.containsAll(List.of("L1", "L2", "L3")),
                () -> "必须覆盖 L1/L2/L3 全部级别，实际：" + levels);
    }

    @Test
    void reasoningCapacitySatisfiesMinModelCalls() throws IOException {
        List<LongSessionDataset.Spec> specs = LongSessionDataset.loadAll();
        for (LongSessionDataset.Spec spec : specs) {
            AgentPolicy.Reasoning reasoning = AgentPolicy.Reasoning.from(spec.reasoning());
            int capacity = reasoning.maxToolRounds() * spec.turns().size();
            assertTrue(capacity >= spec.minModelCalls(),
                    () -> spec.id() + ": 推理档位总轮次容量不足，容量=" + capacity
                            + " < minModelCalls=" + spec.minModelCalls());
        }
    }

    @Test
    void l1PreRegisteredGates() throws IOException {
        LongSessionDataset.Spec l1 = findBase("l1-single-module-refactor");

        // L1 应为单次 execute，至少 10 个独立交付断言
        assertEquals(1, l1.turns().size(), "L1 应为单次 execute");
        assertTrue(LongSessionDataset.expectedArtifacts(l1).size() >= 10,
                () -> "L1 至少 10 个独立交付断言，实际=" + LongSessionDataset.expectedArtifacts(l1).size());

        // 门禁预注册：计划更新 ≥2、工具失败 ≥1、恢复 ≥1
        var rc = l1.requiredCoverage();
        assertTrue(rc.minUpdatePlanCalls() >= 2, "L1 必须预注册 minUpdatePlanCalls >= 2");
        assertTrue(rc.minToolFailures() >= 1, "L1 必须预注册 minToolFailures >= 1");
        assertTrue(rc.minRecoveredTools() >= 1, "L1 必须预注册 minRecoveredTools >= 1");
        assertFalse(rc.requireMemoryProbe(), "L1 不要求记忆探针");
    }

    @Test
    void l2PreRegisteredGates() throws IOException {
        LongSessionDataset.Spec l2 = findBase("l2-cross-file-scope-review");

        // L2 至少三次 execute 覆盖 scope 变化，至少 20 个独立交付断言
        assertTrue(l2.turns().size() >= 3, "L2 至少三次 execute");
        assertTrue(LongSessionDataset.expectedArtifacts(l2).size() >= 20,
                () -> "L2 至少 20 个独立交付断言，实际=" + LongSessionDataset.expectedArtifacts(l2).size());

        // L2 至少 2 个不同 scope 且中途切换
        long distinctScopes = l2.turns().stream()
                .map(t -> t.scope() == null ? "" : t.scope())
                .filter(s -> !s.isBlank())
                .distinct()
                .count();
        assertTrue(distinctScopes >= 2, () -> "L2 至少需要 2 个不同 scope，实际=" + distinctScopes);

        // 门禁预注册
        var rc = l2.requiredCoverage();
        assertTrue(rc.minApprovals() >= 1, "L2 必须预注册 minApprovals >= 1");
        assertTrue(rc.minScopeChanges() >= 1, "L2 必须预注册 minScopeChanges >= 1");
        assertTrue(rc.minScopeRejections() >= 1, "L2 必须预注册 minScopeRejections >= 1");
        assertTrue(rc.minReviewAccepted() >= 1, "L2 必须预注册 minReviewAccepted >= 1");
        assertFalse(rc.requireMemoryProbe(), "L2 不要求记忆探针");
    }

    @Test
    void l3PreRegisteredGates() throws IOException {
        LongSessionDataset.Spec l3 = findBase("l3-cumulative-history-memory");

        // L3 至少十次 execute，确保早期历史被裁剪；至少 50 个独立交付断言
        assertTrue(l3.turns().size() >= 10, "L3 至少十次 execute");
        assertTrue(LongSessionDataset.expectedArtifacts(l3).size() >= 50,
                () -> "L3 至少 50 个独立交付断言，实际=" + LongSessionDataset.expectedArtifacts(l3).size());

        // 记忆锚点与摘要注入
        var memory = l3.memory();
        assertNotNull(memory, "L3 必须有 memory 配置");
        assertNotNull(memory.anchor(), "L3 必须有记忆锚点");
        assertTrue(memory.anchor().length() >= 8, "记忆锚点应有足够长度避免猜测");
        assertEquals(l3.turns().size(), memory.probeTurn(), "L3 探针必须在最后 turn");
        assertTrue(memory.summaryAfterTurn() >= 1 && memory.summaryAfterTurn() < memory.probeTurn(),
                "L3 摘要必须在探针前注入");

        // 门禁预注册
        var rc = l3.requiredCoverage();
        assertTrue(rc.requireMemoryProbe(), "L3 必须预注册 requireMemoryProbe");
        assertTrue(rc.minSummaryInjectedCalls() >= 1, "L3 必须预注册 minSummaryInjectedCalls >= 1");
    }

    @Test
    void fixtureMaterializationCreatesAllFiles() throws IOException {
        List<LongSessionDataset.Spec> specs = LongSessionDataset.loadAll();

        for (LongSessionDataset.Spec spec : specs) {
            Path workspace = tempDir.resolve(spec.id().replace('#', '-'));
            LongSessionDataset.materialize(spec, workspace);

            // 物化后每个 fixture 文件必须存在且内容正确
            Map<String, String> files = LongSessionDataset.expandFixture(spec.fixture());
            for (Map.Entry<String, String> entry : files.entrySet()) {
                Path target = workspace.resolve(entry.getKey()).normalize();
                assertTrue(Files.exists(target),
                        () -> spec.id() + ": fixture 文件不存在 " + entry.getKey());
                assertEquals(entry.getValue(), Files.readString(target),
                        () -> spec.id() + ": fixture 文件内容不匹配 " + entry.getKey());
            }

            // 物化后所有 expectedArtifacts 必须在 fixture 中有对应源文件
            Map<String, String> artifacts = LongSessionDataset.expectedArtifacts(spec);
            for (String path : artifacts.keySet()) {
                assertTrue(Files.exists(workspace.resolve(path).normalize()),
                        () -> spec.id() + ": expectedArtifact 没有对应 fixture 源文件 " + path);
            }
        }
    }

    @Test
    void fixturePathsAreSafeRelative() throws IOException {
        List<LongSessionDataset.Spec> specs = LongSessionDataset.loadAll();
        for (LongSessionDataset.Spec spec : specs) {
            Map<String, String> files = LongSessionDataset.expandFixture(spec.fixture());
            for (String path : files.keySet()) {
                assertFalse(path.startsWith("/"),
                        () -> spec.id() + ": fixture 路径不得是绝对路径 " + path);
                assertTrue(path.indexOf('\\') < 0,
                        () -> spec.id() + ": fixture 路径不得包含反斜杠 " + path);
                assertFalse(path.contains(".."),
                        () -> spec.id() + ": fixture 路径不得包含 '..' " + path);
            }
        }
    }

    @Test
    void turnsHaveDistinctPromptsRendered() throws IOException {
        List<LongSessionDataset.Spec> specs = LongSessionDataset.loadAll();
        for (LongSessionDataset.Spec spec : specs) {
            var rendered = spec.turns().stream()
                    .map(LongSessionDataset::render)
                    .toList();
            long distinct = rendered.stream().distinct().count();
            assertEquals(rendered.size(), distinct,
                    () -> spec.id() + ": 存在重复 prompt，凑工作量风险。渲染后 prompts=" + rendered);
        }
    }

    @Test
    void turnPromptSubstitutesFirstAndLast() throws IOException {
        LongSessionDataset.Spec l2 = findBase("l2-cross-file-scope-review");

        String first = LongSessionDataset.render(l2.turns().get(0));
        assertTrue(first.contains("Rule1 至 Rule8"), () -> "首 turn 未替换区间占位符：" + first);
        assertFalse(first.contains("{first}") || first.contains("{last}"),
                () -> "首 turn 仍含未替换占位符：" + first);

        String last = LongSessionDataset.render(l2.turns().get(2));
        assertTrue(last.contains("Rule17 至 Rule24"), () -> "末 turn 未替换区间占位符：" + last);
        assertFalse(last.contains("{first}") || last.contains("{last}"),
                () -> "末 turn 仍含未替换占位符：" + last);
    }

    @Test
    void failureAttributionHintsCoverAllRequiredKeys() throws IOException {
        LongSessionDataset.Spec l1 = findBase("l1-single-module-refactor");
        LongSessionDataset.Spec l2 = findBase("l2-cross-file-scope-review");
        LongSessionDataset.Spec l3 = findBase("l3-cumulative-history-memory");

        // 每个数据集必须包含 5+ 预注册的失败归因 hint
        assertTrue(l1.failureAttribution().size() >= 5, "L1 至少 5 个失败归因 hint");
        assertTrue(l2.failureAttribution().size() >= 5, "L2 至少 5 个失败归因 hint");
        assertTrue(l3.failureAttribution().size() >= 5, "L3 至少 5 个失败归因 hint");
    }

    @Test
    void expandFixtureRejectsDuplicatePaths() {
        LongSessionDataset.Fixture duplicateFixture = new LongSessionDataset.Fixture(
                List.of(
                        new LongSessionDataset.FixtureFile("a.txt", "v1"),
                        new LongSessionDataset.FixtureFile("a.txt", "v2")
                ),
                List.of()
        );
        assertThrows(IllegalArgumentException.class, () -> LongSessionDataset.expandFixture(duplicateFixture),
                "重复 fixture 路径应抛 IllegalArgumentException");
    }

    @Test
    void deleteRecursivelyClearsTempWorkspace() throws IOException {
        Path workspace = tempDir.resolve("cleanup-test");
        Files.createDirectories(workspace.resolve("nested/deep"));
        Files.writeString(workspace.resolve("a.txt"), "content");
        Files.writeString(workspace.resolve("nested/b.txt"), "content");
        Files.writeString(workspace.resolve("nested/deep/c.txt"), "content");

        LongSessionDataset.deleteRecursively(workspace);
        assertFalse(Files.exists(workspace), "deleteRecursively 应完全删除目录");
    }

    // —— helper ——

    private LongSessionDataset.Spec findBase(String baseId) throws IOException {
        return LongSessionDataset.loadAll().stream()
                .filter(s -> baseId.equals(LongSessionDataset.baseId(s.id())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到 baseId=" + baseId + " 的数据集"));
    }
}
