package com.capsule.agentkey.eval.longsession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P7-02 L1/L2/L3 离线验证 Runner。
 *
 * <p>职责：加载数据集 → schema 校验 → 门禁预注册检查 → fixture 物化 → 工件覆盖验证 → JSON 报告。
 * 不触发 ReAct 循环，不调用任何外部模型 API。
 *
 * <p>作为 JUnit 运行：{@code mvn test -Dtest=LongSessionOfflineRunner}
 * 直接生成报告：{@code mvn test -Dtest=LongSessionOfflineRunner -Dp7.offline.report=true}
 */
class LongSessionOfflineRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @TempDir
    Path tempWorkspace;

    @Test
    void runOfflineVerification() throws IOException {
        List<LongSessionDataset.Spec> specs = LongSessionDataset.loadAll();
        ObjectNode report = buildReport(specs, tempWorkspace);

        boolean writeReport = Boolean.getBoolean("p7.offline.report");
        if (writeReport) {
            Path out = Path.of("eval", "runtime-reports",
                    "long-session-offline-" + Instant.now().toString().replace(":", "-") + ".json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, MAPPER.writeValueAsString(report) + System.lineSeparator());
            System.out.println("离线报告已写入: " + out.toAbsolutePath());
        } else {
            System.out.println(MAPPER.writeValueAsString(report));
        }

        List<String> blockers = reportErrors(report);
        assertTrue(blockers.isEmpty(), () -> "L1/L2/L3 离线验证发现问题：\n  - " + String.join("\n  - ", blockers));
    }

    static ObjectNode buildReport(List<LongSessionDataset.Spec> specs, Path workspaceRoot) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("timestamp", Instant.now().toString());
        root.put("mode", "offline_only_no_model");

        // 1. schema 与门禁预注册校验
        ArrayNode issues = MAPPER.createArrayNode();
        List<String> validationErrors = LongSessionDataset.validate(specs);
        for (String err : validationErrors) issues.add(err);

        // 2. fixture 物化与路径安全
        ArrayNode cases = MAPPER.createArrayNode();
        for (LongSessionDataset.Spec spec : specs) {
            ObjectNode caseNode = MAPPER.createObjectNode();
            caseNode.put("id", spec.id());
            caseNode.put("level", spec.level());
            caseNode.put("turns", spec.turns().size());
            caseNode.put("minModelCalls", spec.minModelCalls());

            int capacity = com.capsule.agentkey.service.AgentPolicy.Reasoning.from(spec.reasoning())
                    .maxToolRounds() * spec.turns().size();
            caseNode.put("capacity", capacity);

            try {
                Path ws = workspaceRoot.resolve(spec.level().toLowerCase());
                LongSessionDataset.materialize(spec, ws);
                caseNode.put("materialized_at", ws.toAbsolutePath().toString());

                Map<String, String> fixtureFiles = LongSessionDataset.expandFixture(spec.fixture());
                Map<String, String> artifacts = LongSessionDataset.expectedArtifacts(spec);
                caseNode.put("fixtureFileCount", fixtureFiles.size());
                caseNode.put("artifactCount", artifacts.size());

                long allExist = artifacts.keySet().stream()
                        .filter(p -> Files.exists(ws.resolve(p).normalize())).count();
                caseNode.put("artifactsCoveredByFixture", allExist);

                ArrayNode issuesForCase = MAPPER.createArrayNode();
                for (String path : artifacts.keySet()) {
                    if (!Files.exists(ws.resolve(path).normalize())) {
                        issuesForCase.add("expectedArtifact 无对应 fixture: " + path);
                    }
                }
                if (!issuesForCase.isEmpty()) caseNode.set("issues", issuesForCase);
            } catch (IOException | IllegalArgumentException e) {
                issues.add(spec.id() + ": fixture 物化失败 - " + e.getMessage());
                caseNode.put("error", e.getMessage());
            }

            cases.add(caseNode);
        }

        root.set("cases", cases);
        root.set("schemaIssues", issues);
        root.put("totalCases", specs.size());
        root.put("blockers", issues.size());

        ArrayNode summary = MAPPER.createArrayNode();
        summary.add("离线验证完成：" + specs.size() + " 个数据集，" + issues.size() + " 个阻断问题");
        summary.add("注：本 Runner 只验证数据集质量，不触发真实模型或 ReAct 循环。");
        root.set("summary", summary);

        return root;
    }

    private static List<String> reportErrors(ObjectNode report) {
        List<String> blockers = new ArrayList<>();
        ArrayNode schemaIssues = (ArrayNode) report.get("schemaIssues");
        if (schemaIssues != null) {
            for (int i = 0; i < schemaIssues.size(); i++) blockers.add(schemaIssues.get(i).asText());
        }
        ArrayNode cases = (ArrayNode) report.get("cases");
        if (cases != null) {
            for (int i = 0; i < cases.size(); i++) {
                ObjectNode c = (ObjectNode) cases.get(i);
                ArrayNode issues = (ArrayNode) c.get("issues");
                if (issues != null) {
                    for (int j = 0; j < issues.size(); j++) blockers.add(c.path("id").asText() + ": " + issues.get(j).asText());
                }
            }
        }
        return blockers;
    }
}
