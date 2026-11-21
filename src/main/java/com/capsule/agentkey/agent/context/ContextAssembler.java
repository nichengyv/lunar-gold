package com.capsule.agentkey.agent.context;

import com.capsule.agentkey.agent.run.PlanStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 发给模型的消息装配器（R5）。
 *
 * <p>职责只有两件：把最新计划以软提示注入上下文（P7-07），以及按需压缩历史（P7-09）。
 * 返回的是本轮调用的副本，不修改主循环持有的权威消息列表——工具消息顺序、
 * 原始事件与数据库历史因此保持原样，压缩失败也不会污染后续轮次。
 *
 * <p>行为开关：计划注入由 {@code agent.context.plan-injection.enabled} 控制，
 * 压缩由 {@code agent.context.condense.enabled} 控制；两者都关闭时上层不会创建本类实例。
 */
@Slf4j
public final class ContextAssembler {

    private final boolean planInjectionEnabled;
    private final PlanSnapshotProvider planProvider;
    private final ContextCondenser condenser;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param planInjectionEnabled 是否每轮注入最新计划
     * @param planProvider         计划快照读取入口；可为 null（此时不注入）
     * @param condenser            历史压缩器；可为 null（不压缩）
     */
    public ContextAssembler(boolean planInjectionEnabled,
                            PlanSnapshotProvider planProvider,
                            ContextCondenser condenser) {
        this.planInjectionEnabled = planInjectionEnabled;
        this.planProvider = planProvider;
        this.condenser = condenser;
    }

    /**
     * 装配本轮要发给模型的消息列表。
     *
     * @param messages 主循环持有的权威消息（不会被修改）
     * @return 可直接传给模型的消息副本
     */
    public List<ObjectNode> assemble(List<ObjectNode> messages) {
        List<ObjectNode> working = new ArrayList<>(messages == null ? List.of() : messages);
        if (planInjectionEnabled) {
            injectPlan(working);
        }
        if (condenser != null) {
            working = condenser.condense(working);
        }
        return working;
    }

    /**
     * 软注入计划：插在 system 之后、历史之前，避免打断末尾的 tool 响应与最终指令，
     * 同时保证每轮都能看到最新状态。
     */
    private void injectPlan(List<ObjectNode> working) {
        if (planProvider == null) {
            return;
        }
        List<PlanStep> plan;
        try {
            plan = planProvider.latestPlan();
        } catch (Exception e) {
            // 计划读取失败只能降级：不能让一次 DB 抖动打断整轮推理
            log.warn("计划快照读取失败，本轮跳过软注入：{}", e.getMessage());
            return;
        }
        if (plan == null || plan.isEmpty()) {
            return;
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", formatPlan(plan));
        int index = (!working.isEmpty() && "system".equals(role(working.get(0)))) ? 1 : 0;
        working.add(index, message);
        log.debug("计划软注入：{} 步（插入位置 {}）", plan.size(), index);
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
