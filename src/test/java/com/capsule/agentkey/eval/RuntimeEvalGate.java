package com.capsule.agentkey.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** P5-13：真实 ReAct 闭环的独立质量门禁，不与组件评测共用基线。 */
public class RuntimeEvalGate {

    static final Path BASELINE_PATH = Paths.get("eval", "baselines", "runtime-baseline.json");
    private static final double RUNTIME_RATE_DROP_PP = 2.0;
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    public void runtimeQualityAndCostMustNotRegress() throws IOException {
        EvalRunner.RunResult run = RuntimeEvalRunner.execute();
        ObjectNode current = run.report();

        if (Boolean.getBoolean("eval.updateBaseline")) {
            assertTrue(current.path("passed").asInt() == current.path("total").asInt() && current.path("total").asInt() > 0,
                    "真实 ReAct 用例未全部通过，禁止写入 Runtime 基线");
            Files.createDirectories(BASELINE_PATH.getParent());
            Files.writeString(BASELINE_PATH, MAPPER.writeValueAsString(EvalGate.baselineFrom(current))
                    + System.lineSeparator());
            System.out.println("Runtime 评测基线已更新: " + BASELINE_PATH);
            return;
        }

        assertTrue(Files.exists(BASELINE_PATH),
                "缺少 Runtime 评测基线 " + BASELINE_PATH + "；请先运行 -Deval.updateBaseline=true");
        ObjectNode baseline = (ObjectNode) MAPPER.readTree(Files.readString(BASELINE_PATH));
        List<String> failures = new ArrayList<>(EvalGate.compare(baseline, current));
        compareRate(baseline, current, "task_success_rate", failures);
        compareRate(baseline, current, "verification_coverage", failures);
        assertTrue(failures.isEmpty(), () -> "Runtime 评测门禁发现回退：\n - " + String.join("\n - ", failures)
                + "\n若变更属于预期改进，请审查报告后使用 -Deval.updateBaseline=true 更新基线。");
        System.out.println("Runtime 评测门禁通过：真实交付状态、验证覆盖率、轮次、token 与 p95 均未回退。");
    }

    private static void compareRate(ObjectNode baseline, ObjectNode current, String metric, List<String> failures) {
        double before = baseline.path(metric).asDouble();
        double after = current.path(metric).asDouble();
        if (after < before - RUNTIME_RATE_DROP_PP) {
            failures.add(String.format(Locale.ROOT, "%s: %.2f%% -> %.2f%%（下降超过 %.1fpp）",
                    metric, before, after, RUNTIME_RATE_DROP_PP));
        }
    }
}
