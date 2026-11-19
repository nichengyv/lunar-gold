package com.capsule.agentkey.eval.index;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5-04 阶段 A 门禁：索引本身的确定性、增量正确性与符号质量。
 *
 * <p>不调用外部模型，也不把耗时设为门槛（机器差异会导致 CI 抖动）；
 * 收益结论由阶段 B 的真实模型 A/B 给出。</p>
 */
class IndexBenefitEvalTest {

    private static final double MIN_SYMBOL_RECALL = 0.8;

    @Test
    void deterministicAndIncrementalIndexMeetsContract() throws IOException {
        IndexBenefitEvalRunner.Report result = IndexBenefitEvalRunner.execute();
        ObjectNode report = result.report();

        assertTrue(report.path("determinism").path("identical").asBoolean(),
                "同一工作区三次 cold build 的 path + symbol 必须一致");
        double recall = report.path("symbol_accuracy").path("recall").asDouble();
        assertTrue(recall >= MIN_SYMBOL_RECALL,
                "符号 name-level recall 低于 " + MIN_SYMBOL_RECALL + "，实际 " + recall);

        ObjectNode scan = (ObjectNode) report.path("scan_full");
        assertEquals(1, scan.path("incremental_scanned_files").asInt(),
                "修改 1 个文件后只应重扫该文件");
        assertEquals(IndexEvalFixtures.JAVA_FILES + IndexEvalFixtures.TS_FILES
                        + IndexEvalFixtures.VUE_FILES + IndexEvalFixtures.DOC_FILES - 1,
                scan.path("incremental_reused_files").asInt(),
                "其余文件应全部命中缓存");
        assertEquals(1, scan.path("removed_after_delete").asInt(),
                "删除 1 个文件后缓存清理数应为 1");
        assertFalse(scan.path("limited").asBoolean(), "800 文件工作区在 800 上限内不应被截断");

        assertTrue(report.path("repo_map").path("chars").asInt() > 0, "repo map 不应为空");
        assertTrue(report.path("repo_map").path("budget_usage_ratio").asDouble() <= 1.0,
                "repo map 不能超出字符预算");
        assertTrue(report.path("informational_repo_scan").path("informational").asBoolean(),
                "本仓库测量必须标记为信息性，不参与门槛");
        assertTrue(java.nio.file.Files.exists(result.reportPath()), "报告文件必须落盘");
    }
}
