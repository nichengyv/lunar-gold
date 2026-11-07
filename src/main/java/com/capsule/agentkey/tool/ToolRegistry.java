package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.CapabilityService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 工具注册中心：支持运行时动态注册和发现。
 *
 * 第五十九批：dispatch 层自动 fallback。
 * - McpToolBridge 注册 MCP 等价工具时标记 isFallback=true 并建立映射
 * - buildToolCallbacks 跳过 fallback（模型看不到，避免两套纠结）
 * - dispatch 执行主工具失败 + 有 fallback 映射 → 自动 try fallback
 *
 * 第六十批：tool scoring 智能排序。
 * - dispatch 记录会话级统计（每工具 calls/success/fail）到 ThreadLocal
 * - buildRankedToolCallbacks(userPrompt, permission, sessionId) 按分数降序返回工具
 *   分数 = 关键词匹配 (60%) + 历史成功率 (25%) + 启发式优先级 (15%)
 */
@Component
@Slf4j
public class ToolRegistry {

    private final Map<String, BaseTool> tools = new ConcurrentHashMap<>();

    /** 主工具名 → fallback 工具名列表（按序尝试） */
    private final Map<String, List<String>> fallbackMap = new ConcurrentHashMap<>();

    private final Map<AgentPolicy.Permission, List<ToolCallback>> callbackCache = new ConcurrentHashMap<>();

    private final ApplicationContext applicationContext;
    private final MeterRegistry meterRegistry;
    private final CapabilityService capabilityService;

    // 第六十批：会话级工具统计。每次 ReActAgentService.execute() 开始前 reset，
    // dispatch 每次执行后累加。用 ThreadLocal<Map<String, int[3]>>: [calls, success, fail]
    private final ThreadLocal<Map<String, int[]>> sessionToolStats = ThreadLocal.withInitial(ConcurrentHashMap::new);

    public ToolRegistry(ApplicationContext applicationContext, MeterRegistry meterRegistry,
                        CapabilityService capabilityService) {
        this.applicationContext = applicationContext;
        this.meterRegistry = meterRegistry;
        this.capabilityService = capabilityService;
    }

    @PostConstruct
    public void init() {
        Map<String, BaseTool> beans = applicationContext.getBeansOfType(BaseTool.class);
        beans.values().forEach(this::register);
        log.info("Agent 工具注册完成，共 {} 个: {}", tools.size(), tools.keySet());
    }

    public boolean register(BaseTool tool) {
        String name = tool.getName();
        Capability requiredCapability = tool.requiredCapability();
        if (requiredCapability != null && !capabilityService.has(requiredCapability)) {
            log.info("当前部署模式缺少能力 {}，跳过工具注册: {}", requiredCapability, name);
            return false;
        }
        if (tools.containsKey(name)) {
            log.warn("工具 [{}] 已被覆盖", name);
        }
        tools.put(name, tool);
        callbackCache.clear();
        log.info("注册工具: {} - {}（来源: {}{}）", name, tool.getDescription(), tool.source(),
                tool.isFallback() ? "，fallback=true" : "");
        return true;
    }

    /**
     * 第五十九批：建立 fallback 映射——主工具失败时自动 try 这个备选。
     * 同一个主工具可以注册多个 fallback（按注册顺序尝试）。
     */
    public void addFallback(String mainToolName, String fallbackToolName) {
        callbackCache.clear(); // 工具清单变了，清缓存
        fallbackMap.computeIfAbsent(mainToolName, k -> new ArrayList<>()).add(fallbackToolName);
        log.info("fallback 映射: {} → {}", mainToolName, fallbackToolName);
    }

    public BaseTool getTool(String name) { return tools.get(name); }
    public boolean hasTool(String name) { return tools.containsKey(name); }
    public boolean hasFallback(String name) {
        List<String> fallbacks = fallbackMap.get(name);
        return fallbacks != null && !fallbacks.isEmpty();
    }
    public Collection<BaseTool> getAllTools() { return Collections.unmodifiableCollection(tools.values()); }

    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file", "delete_file");
    private static final Set<String> EXECUTE_TOOLS = Set.of("run_command");

    private boolean isAllowed(BaseTool tool, AgentPolicy.Permission permission) {
        if (permission == null) return true;
        String toolName = tool.getName();
        if ("mcp".equals(tool.source())) return permission.canExecute();
        if (!permission.canWrite() && WRITE_TOOLS.contains(toolName)) return false;
        return permission.canExecute() || !EXECUTE_TOOLS.contains(toolName);
    }

    /**
     * 第五十九批：跳过 isFallback 的工具——它们是 dispatch 内部用的，不给模型看。
     * 同时保持原有的权限过滤逻辑。
     */
    public List<ToolCallback> buildToolCallbacks(AgentPolicy.Permission permission) {
        return callbackCache.computeIfAbsent(permission, p -> {
            List<ToolCallback> callbacks = new ArrayList<>();
            for (BaseTool tool : tools.values()) {
                if (tool.isFallback()) continue; // 第五十九批：fallback 工具不给模型看
                if (!isAllowed(tool, p)) continue;
                callbacks.add(tool.toToolCallback());
            }
            return List.copyOf(callbacks);
        });
    }

    /**
     * 第五十九批：dispatch 执行主工具失败 + 有 fallback 映射 → 自动按序 try，
     * 全部失败才返回最终错误。模型对这个过程完全透明。
     */
    public ToolResult dispatch(String toolName, String argumentsJson, AgentPolicy.Permission permission) {
        BaseTool tool = tools.get(toolName);
        if (tool == null) {
            recordToolCall(toolName, "unknown", "unknown_tool", System.nanoTime());
            return ToolResult.fail("未知工具: " + toolName);
        }
        if (tool.isFallback()) {
            // 模型理论上不该直接调用 fallback 工具（buildToolCallbacks 已过滤），
            // 但 ReAct 循环可能在 fallback 重试后把 fallback 名反馈给模型，
            // 模型再次指名调用时就走到这里——直接执行，不走 fallback 链
            return executeTool(tool, argumentsJson, permission);
        }
        ToolResult result = executeTool(tool, argumentsJson, permission);
        // 第六十批：会话级统计——只统计主工具（fallback 不重复记）
        recordSessionStat(toolName, result.isSuccess());
        if (result.isSuccess()) return result;

        // 主工具失败，尝试 fallback
        List<String> fallbacks = fallbackMap.get(toolName);
        if (fallbacks == null || fallbacks.isEmpty()) return result;

        log.info("工具 [{}] 执行失败，尝试 {} 个 fallback: {}", toolName, fallbacks.size(), fallbacks);
        StringBuilder allErrors = new StringBuilder();
        allErrors.append(result.getContent()); // 主工具失败的错误

        for (String fbName : fallbacks) {
            BaseTool fb = tools.get(fbName);
            if (fb == null) {
                log.warn("fallback [{}] 未注册，跳过", fbName);
                allErrors.append("\n  → fallback [").append(fbName).append("] 未注册");
                continue;
            }
            if (!isAllowed(fb, permission)) {
                allErrors.append("\n  → fallback [").append(fbName).append("] 权限不允许");
                continue;
            }
            log.info("尝试 fallback [{}]...", fbName);
            ToolResult fbResult = executeTool(fb, argumentsJson, permission);
            if (fbResult.isSuccess()) {
                log.info("fallback [{}] 成功！", fbName);
                // 标明这是 fallback 结果，前端追踪面板可以区分
                return ToolResult.ok("[fallback: " + fbName + "] " + fbResult.getContent());
            }
            allErrors.append("\n  → fallback [").append(fbName).append("] 失败: ").append(fbResult.getContent());
        }

        // 全部 fallback 也失败了，返回汇总错误
        log.warn("工具 [{}] 及 {} 个 fallback 全部失败", toolName, fallbacks.size());
        return ToolResult.fail("工具 " + toolName + " 执行失败，已尝试 " + fallbacks.size()
                + " 个备选方案均失败:\n" + allErrors);
    }

    /** 真正的工具执行（参数校验 + execute + 指标埋点）。dispatch 和 fallback 共用。 */
    private ToolResult executeTool(BaseTool tool, String argumentsJson, AgentPolicy.Permission permission) {
        // 权限过滤
        if (!isAllowed(tool, permission)) {
            String label = permission == null ? "受限" : permission.label();
            long now = System.nanoTime();
            recordToolCall(tool.getName(), tool.source(), "denied", now);
            return ToolResult.fail("当前工作权限为「" + label + "」，不允许调用工具 " + tool.getName());
        }
        long startNanos = System.nanoTime();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode arguments;
            if (argumentsJson == null || argumentsJson.isBlank()) {
                arguments = mapper.createObjectNode();
            } else {
                arguments = mapper.readTree(argumentsJson);
                if (arguments == null || arguments.isMissingNode()) arguments = mapper.createObjectNode();
            }
            if (!arguments.isObject()) {
                recordToolCall(tool.getName(), tool.source(), "failure", startNanos);
                return ToolResult.fail("参数必须是 JSON 对象: " + argumentsJson);
            }
            ToolResult result = tool.execute(arguments);
            recordToolCall(tool.getName(), tool.source(), result.isSuccess() ? "success" : "failure", startNanos);
            return result;
        } catch (Exception e) {
            log.error("工具 [{}] 执行异常", tool.getName(), e);
            recordToolCall(tool.getName(), tool.source(), "error", startNanos);
            return ToolResult.fail("执行异常: " + e.getMessage());
        }
    }

    private void recordToolCall(String toolName, String source, String result, long startNanos) {
        WorkspaceContext context = WorkspaceContext.get();
        String userId = context == null || context.userId() == null ? "none" : context.userId().toString();
        String projectId = context == null || context.projectId() == null ? "none" : context.projectId().toString();
        Timer.builder("agent.tool.execution")
                .description("Agent 工具调用耗时与结果")
                .tag("tool", toolName)
                .tag("source", source)
                .tag("result", result)
                .tag("userId", userId)
                .tag("projectId", projectId)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    // ========== 第六十批：会话级工具统计 + 智能排序 ==========

    /** 每次新会话/新 execute() 开始时 reset。避免跨会话污染。 */
    public void resetSessionStats() {
        sessionToolStats.get().clear();
    }

    /** dispatch 成功/失败分支调用。int[0]=calls, [1]=success, [2]=fail */
    private void recordSessionStat(String toolName, boolean success) {
        Map<String, int[]> stats = sessionToolStats.get();
        int[] counts = stats.computeIfAbsent(toolName, k -> new int[3]);
        counts[0]++;
        if (success) counts[1]++;
        else counts[2]++;
    }

    /**
     * 第六十批：替代 buildToolCallbacks(policy.permission())。
     * 按用户意图 + 历史成功率 + 启发式优先级对工具排序后返回。
     * fallback 工具仍然过滤（模型看不到）。
     */
    public List<ToolCallback> buildRankedToolCallbacks(String userPrompt, AgentPolicy.Permission permission) {
        // 先取未排序的 fallback 已过滤的 BaseTool 列表
        List<BaseTool> candidates = new ArrayList<>();
        for (BaseTool t : tools.values()) {
            if (t.isFallback()) continue;
            if (!isAllowed(t, permission)) continue;
            candidates.add(t);
        }
        if (candidates.isEmpty()) return List.of();

        // 计算每个工具的分数
        String promptLower = userPrompt == null ? "" : userPrompt.toLowerCase();
        // 分词：按非字母数字 + 下划线/下划线切；中文不分词（单个字匹配）
        String[] promptTokens = promptLower.split("[^\\p{L}\\p{N}_]+");

        // 预计算启发式优先级
        Map<String, Integer> heuristicRank = Map.ofEntries(
                Map.entry("get_current_weather", 10),
                Map.entry("web_search", 9),
                Map.entry("search_files", 8),
                Map.entry("list_dir", 8),
                Map.entry("read_file", 7),
                Map.entry("list_allowed_directories", 7),
                Map.entry("write_file", 5),
                Map.entry("edit_file", 5),
                Map.entry("delete_file", 5),
                Map.entry("run_command", 4),
                Map.entry("create_directory", 6),
                Map.entry("move_file", 5)
        );

        // 预计算 prompt 关键词频（用于 overlap 比例）
        int totalPromptTokens = promptTokens.length;
        int totalPromptChars = promptLower.length();

        // 预计算每个工具的关键词集：工具名 + description 里的关键词
        // 把 description 转小写分词，按非字母数字切
        Map<String, Set<String>> toolKeywords = new HashMap<>();
        Map<String, String> toolDescLower = new HashMap<>();
        for (BaseTool t : candidates) {
            String desc = t.getDescription() == null ? "" : t.getDescription().toLowerCase();
            toolDescLower.put(t.getName(), desc);
            Set<String> kw = new HashSet<>();
            // 工具名拆 token
            for (String tok : t.getName().split("[^\\p{L}\\p{N}_]+")) {
                if (!tok.isBlank()) kw.add(tok);
            }
            // description 拆 token
            for (String tok : desc.split("[^\\p{L}\\p{N}_]+")) {
                if (!tok.isBlank() && tok.length() >= 2) kw.add(tok);
            }
            toolKeywords.put(t.getName(), kw);
        }

        // 预计算会话级成功率
        Map<String, Double> toolSuccessRate = new HashMap<>();
        Map<String, int[]> stats = sessionToolStats.get();
        for (BaseTool t : candidates) {
            int[] c = stats.get(t.getName());
            if (c != null && c[0] > 0) {
                toolSuccessRate.put(t.getName(), (double) c[1] / c[0]);
            } else {
                toolSuccessRate.put(t.getName(), 0.5); // 没调过的给个中性分
            }
        }

        // 打分
        List<double[]> scores = new ArrayList<>();
        for (BaseTool t : candidates) {
            // ===== 维度 1：关键词匹配（60%） =====
            double keywordScore = 0;
            Set<String> kw = toolKeywords.get(t.getName());
            if (totalPromptTokens > 0) {
                int overlapTokens = 0;
                for (String tok : promptTokens) {
                    if (!tok.isBlank() && kw.contains(tok)) overlapTokens++;
                }
                // token overlap 比例
                keywordScore = (double) overlapTokens / totalPromptTokens;
            }
            // 补充：description 子串匹配（处理中文或自然语言描述如 "写文件"）
            String desc = toolDescLower.get(t.getName());
            if (totalPromptChars > 0) {
                int charMatch = 0;
                for (char c : promptLower.toCharArray()) {
                    if (desc.indexOf(c) >= 0) charMatch++;
                }
                double charRatio = (double) charMatch / totalPromptChars;
                keywordScore = Math.max(keywordScore, charRatio * 0.5); // 子串匹配只算一半权重
            }
            // 工具名精确匹配额外加分
            if (promptLower.contains(t.getName().toLowerCase())) keywordScore += 0.3;
            keywordScore = Math.min(1.0, keywordScore);

            // ===== 维度 2：历史成功率（25%） =====
            double successScore = toolSuccessRate.get(t.getName());
            // 低调用次数的（如 <2 次）往 0.5 拉，避免偶然的全成功/全失败带偏
            int[] c = stats.get(t.getName());
            if (c == null || c[0] < 2) {
                successScore = successScore * 0.5 + 0.5 * 0.5; // 向 0.5 拉一半
            }

            // ===== 维度 3：启发式优先级（15%） =====
            int rank = heuristicRank.getOrDefault(t.getName(), 5);
            double heuristicScore = rank / 10.0; // 0~1 之间

            // ===== 加权总得分 =====
            double total = keywordScore * 0.60 + successScore * 0.25 + heuristicScore * 0.15;
            scores.add(new double[]{total, keywordScore, successScore, heuristicScore});
        }

        // 按总分降序排序
        List<int[]> rankIdx = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) rankIdx.add(new int[]{i});
        rankIdx.sort((a, b) -> {
            double diff = scores.get(b[0])[0] - scores.get(a[0])[0];
            return diff > 0 ? 1 : diff < 0 ? -1 : 0;
        });

        // 转成 ToolCallback
        List<ToolCallback> ranked = new ArrayList<>();
        for (int[] idx : rankIdx) {
            ranked.add(candidates.get(idx[0]).toToolCallback());
        }

        // 日志：前 3 个工具的分数
        if (!ranked.isEmpty()) {
            StringBuilder sb = new StringBuilder("tool scoring top3: ");
            for (int i = 0; i < Math.min(3, candidates.size()); i++) {
                int toolIdx = rankIdx.get(i)[0];
                String name = candidates.get(toolIdx).getName();
                double[] s = scores.get(toolIdx);
                sb.append(name).append("(total=").append(String.format("%.2f", s[0]))
                        .append(" kw=").append(String.format("%.2f", s[1]))
                        .append(" suc=").append(String.format("%.2f", s[2]))
                        .append(" h=").append(String.format("%.2f", s[3])).append(") ");
            }
            log.info("{} (prompt={})", sb, userPrompt == null ? "" : userPrompt.length() > 60 ? userPrompt.substring(0, 60) + "..." : userPrompt);
        }

        return List.copyOf(ranked);
    }
}
