package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P7-06：ask_user_question —— 让模型"停下来问用户"的能力。
 *
 * <p>本类是一个"schema-only" BaseTool：它的 sole purpose 是把 ask_user_question 的 JSON Schema
 * 注册给 ToolRegistry → 让 Spring AI 下发给模型 → 模型决定要不要调用。
 *
 * <p>真正的执行（挂起 ReAct 循环 → 等用户回答 → 恢复）不在 BaseTool.execute() 里——
 * execute() 只是占位返回一个内部标记，真正的挂起/恢复由
 * {@link com.capsule.agentkey.service.ReActAgentService} 在工具循环里拦截
 * {@code toolName.equals("ask_user_question")} 后调用
 * {@link AskUserQuestionTool#ask(Long, String, String, String, List, boolean, com.capsule.agentkey.service.ReActAgentService.StreamCallback, java.util.function.BooleanSupplier)} 完成。
 *
 * <p>为什么不直接在 execute() 里挂起？因为 BaseTool.execute() 跑在同步的 ToolRegistry.dispatch() 栈帧里，
 * ReAct 循环有审批、scope 检查、只读批等复杂逻辑要先过——在 execute() 里挂起会绕开这些。
 * 拦截点选在 ReActAgentService 工具循环里，位于 scope 检查之后、审批判断之前，
 * 这样 ask_user_question 也能拿到完整的 RunContext。
 */
@Component
public final class AskUserQuestionSchemaTool implements BaseTool {

    @Override
    public String getName() {
        return "ask_user_question";
    }

    @Override
    public String getDescription() {
        return """
                当你不确定下一步方向、有多个方案需要用户选择、或者缺少关键信息才能继续时，调用此工具向用户提问。
                工具会暂停执行等待用户回答，用户的选择将作为答案返回。
                不要在每个步骤都调用——只在真正有分叉、有选择、或有信息缺口时使用。
                一次只问一个问题（questions 数组长度必须为 1）。""";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> questionObj = new LinkedHashMap<>();
        questionObj.put("type", "object");
        Map<String, Object> qProps = new LinkedHashMap<>();

        Map<String, Object> id = new LinkedHashMap<>();
        id.put("type", "string");
        id.put("description", "稳定的问题 id，用户回答时回传用于关联");
        qProps.put("id", id);

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "string");
        question.put("description", "要问用户的问题文本");
        qProps.put("question", question);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "string");
        header.put("description", "可选的小标题，帮助用户快速理解问题上下文");
        qProps.put("header", header);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("type", "array");
        options.put("description", "可选的选项列表。每个选项是 {\"label\": \"...\", \"description\": \"...\"}。不提供 options 时用户可以自由输入。推荐选项放第一位并加 (Recommended) 后缀。");
        Map<String, Object> optionItems = new LinkedHashMap<>();
        optionItems.put("type", "object");
        Map<String, Object> optProps = new LinkedHashMap<>();
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("type", "string");
        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("type", "string");
        desc.put("description", "选项的补充说明，帮助用户区分相近选项");
        optProps.put("label", label);
        optProps.put("description", desc);
        optionItems.put("properties", optProps);
        // required 是「字段名数组」，不是 JSON Schema 对象——写成 {"type":"array","const":...} 会被 DeepSeek 400 拒绝
        optionItems.put("required", List.of("label"));
        options.put("items", optionItems);
        qProps.put("options", options);

        Map<String, Object> multiSelect = new LinkedHashMap<>();
        multiSelect.put("type", "boolean");
        multiSelect.put("description", "是否允许用户多选。默认 false（单选）。");
        qProps.put("multi_select", multiSelect);

        questionObj.put("properties", qProps);
        questionObj.put("required", List.of("id", "question"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        Map<String, Object> rootProps = new LinkedHashMap<>();
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("type", "array");
        questions.put("minItems", 1);
        questions.put("maxItems", 1);
        questions.put("description", "问题列表。当前只支持一次一个问题。");
        questions.put("items", questionObj);
        rootProps.put("questions", questions);
        root.put("properties", rootProps);
        root.put("required", List.of("questions"));

        return root;
    }

    /**
     * 占位实现——ReActAgentService 会拦截 ask_user_question 的工具调用，
     * 绕开 ToolRegistry.dispatch()，直接走 AskUserQuestionTool.ask() 的挂起/恢复链路。
     * 如果 execute() 被意外调用（比如独立测试场景），返回一个明确的内部错误。
     */
    @Override
    public ToolResult execute(JsonNode arguments) {
        return ToolResult.fail("ask_user_question 必须经由 ReActAgentService 拦截执行，不能直接 dispatch");
    }

    @Override
    public boolean readOnly() {
        return true; // 不修改工作区，不触发审批，可并行（但实际会被拦截串行执行）
    }

    @Override
    public String source() {
        return "builtin";
    }
}
