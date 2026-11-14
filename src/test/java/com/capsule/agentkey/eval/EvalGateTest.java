package com.capsule.agentkey.eval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalGateTest {

    @Test
    void 相同结果通过门禁() {
        ObjectNode report = report(100, 100);
        assertTrue(EvalGate.compare(EvalGate.baselineFrom(report), report).isEmpty());
    }

    @Test
    void 用例由通过变为失败时拦截并指出用例() {
        ObjectNode baseline = EvalGate.baselineFrom(report(100, 100));
        ObjectNode current = EvalRunner.buildReport(List.of(
                result("case-1", true, 100, 100),
                result("case-2", false, 100, 100)));
        List<String> failures = EvalGate.compare(baseline, current);
        assertTrue(failures.stream().anyMatch(s -> s.contains("case-2") && s.contains("pass 回退")));
        assertTrue(failures.stream().anyMatch(s -> s.contains("tool_select pass_rate")));
    }

    @Test
    void token增幅超过百分之十时拦截() {
        ObjectNode baseline = EvalGate.baselineFrom(report(100, 100));
        ObjectNode current = report(120, 100);
        List<String> failures = EvalGate.compare(baseline, current);
        assertTrue(failures.stream().anyMatch(s -> s.contains("avg_tokens") && s.contains("120.00")));
    }

    @Test
    void p95时延增幅超过百分之二十时拦截() {
        ObjectNode baseline = EvalGate.baselineFrom(report(100, 1000));
        ObjectNode current = report(100, 1300);
        List<String> failures = EvalGate.compare(baseline, current);
        assertTrue(failures.stream().anyMatch(s -> s.contains("p95_latency_ms") && s.contains("1300ms")));
    }

    @Test
    void 新增或删除用例必须显式更新基线() {
        ObjectNode baseline = EvalGate.baselineFrom(report(100, 100));
        ObjectNode added = EvalRunner.buildReport(List.of(
                result("case-1", true, 100, 100),
                result("case-2", true, 100, 100),
                result("case-3", true, 100, 100)));
        assertTrue(EvalGate.compare(baseline, added).stream().anyMatch(s -> s.contains("case-3")));

        ObjectNode removed = EvalRunner.buildReport(List.of(result("case-1", true, 100, 100)));
        assertTrue(EvalGate.compare(baseline, removed).stream().anyMatch(s -> s.contains("case-2")));
    }

    private static ObjectNode report(long tokens, long durationMillis) {
        return EvalRunner.buildReport(List.of(
                result("case-1", true, tokens, durationMillis),
                result("case-2", true, tokens, durationMillis)));
    }

    private static EvalRunner.CaseResult result(String id, boolean passed, long tokens, long durationMillis) {
        return new EvalRunner.CaseResult(id, "tool_select", passed, Map.of(), 1, tokens, durationMillis);
    }
}
