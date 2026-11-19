package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.service.AgentExperienceMetricsService;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.util.CurrentUser;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Agent 可观测性接口：从 AgentChatController 拆出，URL 与行为保持不变
 * （/api/agent/metrics、/api/agent/metrics/experience）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentMetricsController {

    private final AgentExperienceMetricsService experienceMetricsService;
    private final MeterRegistry meterRegistry;
    private final ProjectService projectService;

    public AgentMetricsController(AgentExperienceMetricsService experienceMetricsService,
                                  MeterRegistry meterRegistry,
                                  ProjectService projectService) {
        this.experienceMetricsService = experienceMetricsService;
        this.meterRegistry = meterRegistry;
        this.projectService = projectService;
    }

    /** P5-11-2：当前用户的体验指标；Run/审批使用最近 100 条窗口，工具 Timer 为进程累计。 */
    @GetMapping("/metrics/experience")
    public ApiResponse<AgentExperienceMetricsService.ExperienceMetrics> experienceMetrics() {
        return ApiResponse.success(experienceMetricsService.getMetrics(CurrentUser.requireId()));
    }

    /**
     * D3 可观测性前端面板：返回当前应用累计的 Agent 工具调用统计。
     * 聚合 MeterRegistry 里的 agent.tool.execution Timer，按 tool 分组输出调用次数、P50/P95/P99 耗时（毫秒）、成功率。
     * 前端工作台"监控"tab 直接调这个 endpoint 展示。
     */
    @GetMapping("/metrics")
    public ApiResponse<Map<String, Object>> metrics(
            @RequestParam(value = "projectId", required = false) Long projectId) {
        Long userId = CurrentUser.requireId();
        if (projectId != null) {
            projectService.getOwned(userId, projectId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, Object>> tools = new LinkedHashMap<>();
        long totalCalls = 0;
        long totalSuccess = 0;
        long totalFail = 0;

        for (var timer : meterRegistry.find("agent.tool.execution").timers()) {
            // 无归属标签的旧指标及其他用户/项目的指标一律不可见
            if (!userId.toString().equals(timer.getId().getTag("userId"))) continue;
            if (projectId != null && !projectId.toString().equals(timer.getId().getTag("projectId"))) continue;
            String toolName = timer.getId().getTag("tool");
            if (toolName == null) toolName = "(unknown)";
            String source = timer.getId().getTag("source");
            String outcome = timer.getId().getTag("result");

            totalCalls += timer.count();
            if ("success".equals(outcome)) totalSuccess += timer.count();
            else totalFail += timer.count();

            Map<String, Object> bucket = tools.computeIfAbsent(toolName, k -> new LinkedHashMap<>());
            bucket.put("calls", ((Number) bucket.getOrDefault("calls", 0L)).longValue() + timer.count());
            bucket.put("count", ((Number) bucket.getOrDefault("count", 0L)).longValue() + timer.count());
            bucket.put("p50Ms", Math.round(timer.percentile(0.5, TimeUnit.MILLISECONDS)));
            bucket.put("p95Ms", Math.round(timer.percentile(0.95, TimeUnit.MILLISECONDS)));
            bucket.put("p99Ms", Math.round(timer.percentile(0.99, TimeUnit.MILLISECONDS)));
            bucket.put("maxMs", Math.round(timer.max(TimeUnit.MILLISECONDS)));
            // source tag 合并（可能有多个 source 打到同一个 tool）
            @SuppressWarnings("unchecked")
            java.util.Set<String> srcs = (java.util.Set<String>) bucket.computeIfAbsent("sources", k -> new java.util.LinkedHashSet<String>());
            if (source != null) srcs.add(source);
            // result 分桶累计（success/fail）
            @SuppressWarnings("unchecked")
            Map<String, Long> outcomes = (Map<String, Long>) bucket.computeIfAbsent("outcomes", k -> new HashMap<String, Long>());
            outcomes.merge(outcome == null ? "unknown" : outcome, timer.count(), Long::sum);
        }

        // 计算成功率（每个 tool 自己算）
        for (var entry : tools.entrySet()) {
            Map<String, Object> b = entry.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Long> outcomes = (Map<String, Long>) b.get("outcomes");
            long succ = outcomes.getOrDefault("success", 0L);
            long tot = succ + outcomes.getOrDefault("fail", 0L) + outcomes.getOrDefault("error", 0L) + outcomes.getOrDefault("unknown", 0L);
            b.put("successRate", tot == 0 ? 0.0 : Math.round(succ * 10000.0 / tot) / 100.0);
        }

        result.put("generatedAt", java.time.Instant.now().toString());
        result.put("userId", userId);
        if (projectId != null) result.put("projectId", projectId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCalls", totalCalls);
        summary.put("uniqueTools", tools.size());
        summary.put("successCalls", totalSuccess);
        summary.put("failCalls", totalFail);
        summary.put("successRate", totalCalls == 0 ? 0.0 : Math.round(totalSuccess * 10000.0 / totalCalls) / 100.0);
        result.put("summary", summary);
        result.put("tools", tools);

        return ApiResponse.success(result);
    }
}
