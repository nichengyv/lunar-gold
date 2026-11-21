package com.capsule.agentkey.service;

import com.capsule.agentkey.service.ReActAgentService.StreamCallback;
import com.capsule.agentkey.tool.AskUserQuestionTool;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ask_user_question 的挂起/恢复执行器（R4b）。
 *
 * <p>该工具绕开 {@code ToolRegistry.dispatch()}：它需要挂起 ReAct 线程等待用户回答，
 * 而 {@code BaseTool.execute()} 是同步返回的。把这段协议解析与等待逻辑从主循环抽出，
 * 让 {@link AgentOrchestrator} 只保留一个拦截点。
 */
@Slf4j
class AskUserQuestionHandler {

    /** P7-06：测试手动装配时为 null */
    private final AskUserQuestionTool tool;
    /** P7-06：超时/中断后把 Run 从 WAITING_USER_INPUT 拉回 RUNNING；可为 null */
    private final AgentRunService agentRunService;
    private final ObjectMapper mapper = new ObjectMapper();

    AskUserQuestionHandler(AskUserQuestionTool tool, AgentRunService agentRunService) {
        this.tool = tool;
        this.agentRunService = agentRunService;
    }

    boolean available() {
        return tool != null;
    }

    /**
     * 解析 arguments JSON，挂起当前线程等用户回答，返回 ToolResult。
     *
     * <p>流程：解析 questionId/question/header/options → {@link AskUserQuestionTool#ask} 挂起
     * → 用户通过 {@code POST /api/agent/runs/{runId}/answer} 回答 → 包装为 DHS 格式答案。
     */
    ToolResult execute(String arguments, StreamCallback callback, AtomicBoolean cancelRequested) {
        Long runId = WorkspaceContext.get().runId();
        if (runId == null || tool == null) {
            return ToolResult.fail("ask_user_question 需要有效的 runId 和 AskUserQuestionTool bean");
        }

        // 1. 解析 arguments——schema 规定 questions 数组长度为 1
        String questionId, question, header = null;
        List<Map<String, Object>> options = null;
        boolean multiSelect = false;
        try {
            var root = mapper.readTree(arguments);
            var questions = root.path("questions");
            if (!questions.isArray() || questions.size() < 1) {
                return ToolResult.fail("ask_user_question 需要 questions 数组（长度 1）");
            }
            var q = questions.get(0);
            questionId = q.path("id").asText(null);
            question = q.path("question").asText(null);
            if (questionId == null || questionId.isBlank()) {
                return ToolResult.fail("ask_user_question 缺少 id");
            }
            if (question == null || question.isBlank()) {
                return ToolResult.fail("ask_user_question 缺少 question");
            }
            if (q.hasNonNull("header")) header = q.path("header").asText();
            if (q.hasNonNull("options") && q.path("options").isArray()) {
                options = new ArrayList<>();
                for (var opt : q.path("options")) {
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    if (opt.has("label")) map.put("label", opt.path("label").asText());
                    if (opt.has("description")) map.put("description", opt.path("description").asText());
                    options.add(map);
                }
            }
            multiSelect = q.path("multi_select").asBoolean(false);
        } catch (Exception e) {
            log.warn("解析 ask_user_question 参数失败: {}", e.getMessage());
            return ToolResult.fail("ask_user_question 参数解析失败: " + e.getMessage());
        }

        // 2. 挂起等待 + 返回
        String answerJson = tool.ask(
                runId, questionId, question, header, options, multiSelect,
                callback, cancelRequested::get);

        // 3. 包装成 ToolResult——DHS 格式：{ "answers": [{ "id": "...", "selected": [...], "custom": "..." }] }
        try {
            var parsed = mapper.readTree(answerJson);
            if (parsed.has("error")) {
                // 超时/中断/取消时 Run 还停在 WAITING_USER_INPUT，拉回 RUNNING 与实际执行保持一致；
                // 用户已作答或 Run 已取消时该方法不改状态（内部按当前状态判断）
                abandon(runId, questionId, parsed.path("error").asText("UNKNOWN"));
                return ToolResult.fail("ask_user_question 失败: " + parsed.path("message").asText(answerJson));
            }
            return ToolResult.ok(answerJson);
        } catch (Exception e) {
            // answerJson 本身就是合法 JSON（AskUserQuestionTool 保证），解析失败说明格式有 bug
            log.error("ask_user_question 返回值解析失败: {}", e.getMessage());
            return ToolResult.ok(answerJson); // 降级——原样返回让模型自己理解
        }
    }

    /** 询问没有被回答：把 Run 从 WAITING_USER_INPUT 拉回 RUNNING，失败不影响主循环 */
    private void abandon(Long runId, String questionId, String reason) {
        if (agentRunService == null) return;
        try {
            agentRunService.abandonUserQuestion(runId, questionId, reason);
        } catch (Exception e) {
            log.warn("恢复等待用户输入的 Run 状态失败: runId={}, reason={}, error={}",
                    runId, reason, e.getMessage());
        }
    }
}
