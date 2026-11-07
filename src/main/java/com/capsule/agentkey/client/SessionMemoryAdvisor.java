package com.capsule.agentkey.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.core.Ordered;

/**
 * 第四十七批：会话记忆 Advisor。
 *
 * 长上下文滚动摘要原先由 ReActAgentService 直接拼进 system prompt。
 * 这里把「摘要注入」这一步搬进 Spring AI 的调用链：摘要文本作为 Advisor 的入参，
 * 在真正调用模型之前追加到 system 消息上，ReAct 编排、历史裁剪、工具循环都不感知。
 *
 * 第四十九批删旧后，本 Advisor 是摘要注入的唯一路径（system prompt 里不再重复拼接）。
 */
@Slf4j
public class SessionMemoryAdvisor implements BaseAdvisor {

    private final String summary;

    private SessionMemoryAdvisor(String summary) {
        this.summary = summary;
    }

    /** 每次请求一个实例：摘要跟会话走，不能做成单例 */
    public static SessionMemoryAdvisor of(String summary) {
        return new SessionMemoryAdvisor(summary);
    }

    /**
     * 摘要段落的统一文案。
     * 以空行开头，便于直接追加到已有 system 内容后面。
     */
    public static String section(String summary) {
        return "\n\n【会话历史摘要】\n"
                + "以下是本会话中更早对话的压缩摘要，用于保持连贯；"
                + "若与最近几条原文消息冲突，一律以最近原文为准：\n"
                + summary.trim();
    }

    @Override
    public String getName() {
        return "sessionMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        // 每轮 ReAct 都会调一次模型，这里用 debug 级别，避免把日志刷满
        log.debug("会话历史摘要经 Advisor 注入 system 消息（{} 字符）", summary.trim().length());
        // 注意：Prompt.augmentSystemMessage(String) 是「整体替换」system 文本（内部走
        // SystemMessage.mutate().text(新文本)），不是追加。直接传摘要会把原有的全部
        // 系统提示词（工具规则、文件规则等）整段覆盖掉，表现为模型遗失全部约束。
        // 因此这里必须用 Function 重载，显式在原有文本后面拼接。
        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(
                        systemMessage -> new SystemMessage(systemMessage.getText() + section(summary))))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }
}
