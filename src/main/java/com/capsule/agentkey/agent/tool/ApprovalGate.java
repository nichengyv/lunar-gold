package com.capsule.agentkey.agent.tool;

import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.ScopeConstraintService;
import com.capsule.agentkey.service.ToolApprovalRegistry;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具执行前的唯一判定入口（R3）。
 *
 * <p>重构前，「是否需要审批」「是否在 scope 内」「有没有 MCP fallback」散落在主循环的
 * 串行/并行两个分支里，只读批和 {@code ask_user_question} 各自走特殊路径，
 * 任何一条路径漏判都不会被发现。现在风险判定与 scope 分派都收敛到这里：
 *
 * <ul>
 *   <li>{@link #assess} 只做纯判定，不产生副作用，便于单测；</li>
 *   <li>{@link #dispatch} 是工具真正执行的唯一出口——先 scope 校验，再交给 ToolRegistry；
 *       校验失败返回 {@link ToolResult#fail}，不抛异常炸掉 ReAct 循环；</li>
 *   <li>{@link #rejection} 统一「用户拒绝」时回给模型的文案，避免各分支措辞漂移。</li>
 * </ul>
 */
@Slf4j
public class ApprovalGate {

    /**
     * 风险判定结果。
     *
     * @param level            风险等级（WRITE / EXECUTE / DESTRUCTIVE）；null 表示无需审批
     * @param requiresApproval 本次调用是否必须人工确认
     */
    public record Risk(String level, boolean requiresApproval) {
        public static Risk none() {
            return new Risk(null, false);
        }

        /** 是否属于写/执行类操作；主循环用它决定失败后是否升级为「未完成」 */
        public boolean isWriteLike() {
            return "WRITE".equals(level) || "DESTRUCTIVE".equals(level);
        }
    }

    private final ToolApprovalRegistry approvals;
    private final ToolRegistry registry;
    private final ScopeConstraintService scopeConstraintService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalGate(ToolApprovalRegistry approvals, ToolRegistry registry,
                        ScopeConstraintService scopeConstraintService) {
        this.approvals = approvals;
        this.registry = registry;
        this.scopeConstraintService = scopeConstraintService;
    }

    /**
     * 判定一次调用是否需要审批。
     *
     * <p>规则与重构前一致：权限档位决定基础要求；MCP 写操作（含内置写工具的 MCP fallback）
     * 一律强制审批；用户在本会话中已「始终允许」该工具时跳过。
     */
    public Risk assess(String toolName, String arguments,
                       AgentPolicy.Permission permission, Long sessionId) {
        String risk = ToolApprovalRegistry.riskOf(toolName, arguments);
        if (risk == null) {
            return Risk.none();
        }
        boolean forced = (toolName != null && toolName.startsWith("mcp_"))
                || registry.hasFallback(toolName);
        boolean needsApproval = (permission.requiresApproval(risk, commandOf(arguments)) || forced)
                && !approvals.isSessionAllowed(sessionId, toolName);
        return new Risk(risk, needsApproval);
    }

    /**
     * 工具执行的唯一出口：scope 校验通过后才允许 dispatch。
     * 参数不是合法 JSON 时跳过 scope 解析，交给工具自己报错（与重构前一致）。
     */
    public ToolResult dispatch(String toolName, String arguments, AgentPolicy.Permission permission) {
        try {
            JsonNode args = null;
            try {
                args = mapper.readTree(arguments);
            } catch (Exception e) {
                // 参数不是合法 JSON → 让工具自己解析，scope 检查跳过
            }
            // scope 约束只用相对路径匹配——工具传绝对路径时由工具自身的沙箱校验拦截
            scopeConstraintService.checkToolCall(toolName, args, null);
            return registry.dispatch(toolName, arguments, permission);
        } catch (BusinessException e) {
            log.info("D1 scope 拦截工具调用: tool={}, reason={}", toolName, e.getMessage());
            return ToolResult.fail(e.getMessage());
        }
    }

    /** 用户拒绝后回给模型的 tool 错误：说明被拒的操作类型，并要求换方案 */
    public ToolResult rejection(String toolName, String risk) {
        String label = "EXECUTE".equals(risk) ? "执行命令"
                : "DESTRUCTIVE".equals(risk) ? "递归删除目录" : "写入文件";
        return ToolResult.fail("用户拒绝了" + label + "操作（" + toolName + "）。"
                + "请改用不具破坏性的方案；如果确实需要该操作，请向用户说明理由后等待其重新批准。");
    }

    /** 从工具入参中取出命令原文；解析失败按空串处理（不额外要求审批） */
    String commandOf(String arguments) {
        if (arguments == null || arguments.isBlank()) return "";
        try {
            return mapper.readTree(arguments).path("command").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
