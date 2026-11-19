package com.capsule.agentkey.eval.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5-04 阶段 B 门禁：真实模型 A/B 的数据有效性与报告完整性。
 *
 * <p>与 {@code RagRerankEvalGateTest} 口径一致：不对“开启必须优于关闭”做硬断言，
 * 收益是决策而非回归；但没有可用数据时不允许得出结论。</p>
 */
@EnabledIfEnvironmentVariable(named = "P5_04_MODEL_KEY", matches = ".+")
class IndexAbEvalTest {

    @Test
    void abReportIsStructurallyValid() throws Exception {
        IndexAbEvalRunner.Outcome outcome = IndexAbEvalRunner.execute();
        ObjectNode report = outcome.report();

        JsonNode dataset = report.path("dataset");
        assertTrue(dataset.path("fixture_files").asInt() >= 36, "fixture 至少 36 个文件");
        assertTrue(dataset.path("locate_cases").asInt() >= 12, "locate 用例至少 12 条");
        assertTrue(dataset.path("edit_cases").asInt() >= 4, "edit 用例至少 4 条");
        assertTrue(dataset.path("repo_map_chars").asInt() > 0, "ON 配置必须有 repo map");

        JsonNode validity = report.path("validity");
        assertTrue(validity.path("D1_dataset_shape").asBoolean(), "D1 数据集形态不合格");
        assertTrue(validity.path("D2_repo_map_coverage").asBoolean(), "D2 repo map 覆盖不合格");
        assertTrue(validity.path("D3_index_deterministic").asBoolean(), "D3 索引确定性不合格");
        assertTrue(validity.path("D4_symbol_recall").asBoolean(), "D4 符号召回不合格");
        assertTrue(validity.path("D5_model_failure_rate").asBoolean(),
                "D5 模型失败率超过 5%，本次不判定");
        assertTrue(validity.path("D6_same_model_and_prompt").asBoolean(), "D6 两配置条件不一致");

        JsonNode cases = report.path("cases");
        assertEquals(dataset.path("cases").asInt(), cases.size(), "逐用例结果必须齐全");
        for (JsonNode item : cases) {
            assertEquals(report.path("repeats").asInt(), item.path("on").path("runs").asInt(),
                    item.path("id").asText() + " ON 重复次数不完整");
            assertEquals(report.path("repeats").asInt(), item.path("off").path("runs").asInt(),
                    item.path("id").asText() + " OFF 重复次数不完整");
        }

        assertFalse(report.path("thresholds").path("decision").asText().isBlank(),
                "必须给出明确结论文案");
        assertTrue(java.nio.file.Files.exists(outcome.reportPath()), "A/B 报告必须落盘");
    }
}
