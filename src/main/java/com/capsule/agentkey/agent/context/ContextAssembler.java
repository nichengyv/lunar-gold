package com.capsule.agentkey.agent.context;

import com.capsule.agentkey.agent.run.PlanStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 发给模型的消息装配器（R5）。
 *
 * <p>职责只有两件：把最新计划以软提示注入上下文（P7-07），以及按需压缩历史（P7-09）。
 * 返回的是本轮调用的副本，不修改主循环持有的权威消息列表——工具消息顺序、
 * 原始事件与数据库历史因此保持原样，压缩失败也不会污染后续轮次。
 *
 * <p>R7c：在压缩之后，对**被省略的消息**做一次本地检索（P8-05），把相关片段
 * 渲染成一条合成 user 记忆插在 system 之后；不重新插入原始 assistant/tool 节点。
 *
 * <p>行为开关：计划注入由 {@code agent.context.plan-injection.enabled} 控制，
 * 压缩由 {@code agent.context.condense.enabled} 控制，检索由
 * {@code agent.context.retrieval.enabled} 控制；三者都关闭时上层不会创建本类实例。
 */
@Slf4j
public final class ContextAssembler {

    /** 计划、摘要、检索记忆与预算提示都是系统合成消息，不参与查询/检索 */
    private static final List<String> SYNTHETIC_PREFIXES = List.of(
            "【当前计划 Current plan】", "【历史检索记忆】", "【较早上下文摘要】", "注意：本轮只剩");
    private static final int MAX_QUERY_CHARS = 4_000;

    private final boolean planInjectionEnabled;
    private final PlanSnapshotProvider planProvider;
    private final ContextCondenser condenser;
    private final RunHistoryRetriever retriever;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param planInjectionEnabled 是否每轮注入最新计划
     * @param planProvider         计划快照读取入口；可为 null（此时不注入）
     * @param condenser            历史压缩器；可为 null（不压缩）
     */
    public ContextAssembler(boolean planInjectionEnabled,
                            PlanSnapshotProvider planProvider,
                            ContextCondenser condenser) {
        this(planInjectionEnabled, planProvider, condenser, null);
    }

    /**
     * @param retriever 历史检索器；可为 null（不注入检索记忆）。只有发生压缩后才可能命中
     */
    public ContextAssembler(boolean planInjectionEnabled,
                            PlanSnapshotProvider planProvider,
                            ContextCondenser condenser,
                            RunHistoryRetriever retriever) {
        this.planInjectionEnabled = planInjectionEnabled;
        this.planProvider = planProvider;
        this.condenser = condenser;
        this.retriever = retriever;
    }

    /**
     * 装配本轮要发给模型的消息列表。
     *
     * <p>顺序固定：复制权威消息 → 计划软注入 → 压缩 → 对被省略消息检索 →
     * 在 system 后注入一条合成 user 记忆。
     *
     * @param messages 主循环持有的权威消息（不会被修改）
     * @return 可直接传给模型的消息副本
     */
    public List<ObjectNode> assemble(List<ObjectNode> messages) {
        List<ObjectNode> working = new ArrayList<>(messages == null ? List.of() : messages);
        List<PlanStep> plan = List.of();
        if (planInjectionEnabled) {
            plan = injectPlan(working);
        }
        List<ObjectNode> beforeCondense = null;
        if (condenser != null) {
            beforeCondense = List.copyOf(working);
            working = condenser.condense(working);
        }
        if (retriever != null && beforeCondense != null) {
            injectHistoryMemory(working, beforeCondense, plan);
        }
        return working;
    }

    /**
     * 软注入计划：插在 system 之后、历史之前，避免打断末尾的 tool 响应与最终指令，
     * 同时保证每轮都能看到最新状态。
     *
     * @return 本轮实际注入的计划；没有计划或读取失败时为空列表
     */
    private List<PlanStep> injectPlan(List<ObjectNode> working) {
        if (planProvider == null) {
            return List.of();
        }
        List<PlanStep> plan;
        try {
            plan = planProvider.latestPlan();
        } catch (Exception e) {
            // 计划读取失败只能降级：不能让一次 DB 抖动打断整轮推理
            log.warn("计划快照读取失败，本轮跳过软注入：{}", e.getMessage());
            return List.of();
        }
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", formatPlan(plan));
        int index = (!working.isEmpty() && "system".equals(role(working.get(0)))) ? 1 : 0;
        working.add(index, message);
        log.debug("计划软注入：{} 步（插入位置 {}）", plan.size(), index);
        return plan;
    }

    /**
     * 对压缩省略的消息做本地检索，并把命中片段合成一条 user 记忆。
     *
     * <p>候选按对象身份识别「是否仍留在窗口内」：压缩器折叠/摘要/滑窗时替换或移除的
     * 原始节点即为省略集。命中内容与 retained 消息按规范化内容去重，任何异常都跳过注入。
     */
    private void injectHistoryMemory(List<ObjectNode> working, List<ObjectNode> beforeCondense,
                                     List<PlanStep> plan) {
        try {
            Set<ObjectNode> retained = Collections.newSetFromMap(new IdentityHashMap<>());
            retained.addAll(working);
            List<RunHistoryRetriever.Source> omitted = new ArrayList<>();
            for (int i = 0; i < beforeCondense.size(); i++) {
                ObjectNode message = beforeCondense.get(i);
                if (retained.contains(message) || !isRetrievable(message)) {
                    continue;
                }
                omitted.add(new RunHistoryRetriever.Source(i, message));
            }
            if (omitted.isEmpty()) {
                return;
            }
            String query = buildQuery(working, plan);
            if (query.isBlank()) {
                return;
            }
            List<RunHistoryRetriever.Hit> hits = retriever.retrieve(omitted, query);
            if (hits.isEmpty()) {
                return;
            }
            Set<String> retainedKeys = retainedKeys(working);
            List<RunHistoryRetriever.Hit> kept = hits.stream()
                    .filter(hit -> !retainedKeys.contains(normalize(hit.content())))
                    .toList();
            if (kept.isEmpty()) {
                return;
            }
            ObjectNode memory = mapper.createObjectNode();
            memory.put("role", "user");
            memory.put("content", formatMemory(kept));
            int index = (!working.isEmpty() && "system".equals(role(working.get(0)))) ? 1 : 0;
            working.add(index, memory);
            log.debug("历史检索记忆注入：{} 条片段（会话内共省略 {} 条）", kept.size(), omitted.size());
        } catch (Exception e) {
            log.warn("历史检索记忆注入失败，本轮跳过：{}", e.getMessage());
        }
    }

    /** 查询由最新真实用户输入与当前计划标题组成，忽略合成消息 */
    private String buildQuery(List<ObjectNode> working, List<PlanStep> plan) {
        StringBuilder query = new StringBuilder();
        for (int i = working.size() - 1; i >= 0; i--) {
            ObjectNode message = working.get(i);
            if (!"user".equals(role(message)) || message.has("tool_calls")) {
                continue;
            }
            String content = message.path("content").asText("").trim();
            if (content.isEmpty() || isSynthetic(content)) {
                continue;
            }
            query.append(content);
            break;
        }
        if (plan != null) {
            for (PlanStep step : plan) {
                query.append(' ').append(step.title());
            }
        }
        return query.length() <= MAX_QUERY_CHARS ? query.toString() : query.substring(0, MAX_QUERY_CHARS);
    }

    private static String formatMemory(List<RunHistoryRetriever.Hit> hits) {
        StringBuilder sb = new StringBuilder("【历史检索记忆】以下片段因上下文压缩已从当前窗口省略，")
                .append("但与本轮任务相关；按原始顺序列出，仅供回忆，不替代重新读取文件。\n");
        for (RunHistoryRetriever.Hit hit : hits) {
            sb.append("[#").append(hit.index()).append(' ').append(hit.role()).append("] ")
                    .append(hit.content()).append('\n');
        }
        return sb.toString();
    }

    private static boolean isRetrievable(ObjectNode message) {
        String role = role(message);
        if (!"user".equals(role) && !"assistant".equals(role) && !"tool".equals(role)) {
            return false;
        }
        String content = message.path("content").asText("").trim();
        return !content.isEmpty() && !isSynthetic(content);
    }

    private static boolean isSynthetic(String content) {
        for (String prefix : SYNTHETIC_PREFIXES) {
            if (content.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> retainedKeys(List<ObjectNode> working) {
        Set<String> keys = new HashSet<>();
        for (ObjectNode message : working) {
            String content = message.path("content").asText("").trim();
            if (!content.isEmpty()) {
                keys.add(normalize(content));
            }
            String toolCallId = message.path("tool_call_id").asText("");
            if (!toolCallId.isEmpty()) {
                keys.add("tool:" + toolCallId);
            }
        }
        return keys;
    }

    private static String normalize(String content) {
        return content == null ? "" : content.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 渲染 P7-07 约定的 {@code Current plan:} 文本 */
    public static String formatPlan(List<PlanStep> plan) {
        StringBuilder sb = new StringBuilder("【当前计划 Current plan】\n");
        sb.append("以下是从持久化状态重读的最新计划快照，请保持与之一致；")
                .append("完成某步后调用 update_plan 更新状态。\n");
        for (int i = 0; i < plan.size(); i++) {
            PlanStep step = plan.get(i);
            sb.append(i + 1).append(". [").append(step.status()).append("] ")
                    .append(step.title()).append('\n');
        }
        return sb.toString();
    }

    private static String role(ObjectNode message) {
        return message.path("role").asText("");
    }
}
