package com.capsule.agentkey.service;

import com.capsule.agentkey.client.SpringAiAgentClient;
import com.capsule.agentkey.entity.AgentRule;
import com.capsule.agentkey.entity.AgentRule.RuleScope;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.repository.AgentRuleRepository;
import com.capsule.agentkey.repository.ChatMessageRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 批次 B2：会话级自动记忆
 *
 * 每累计 EXTRACT_INTERVAL 轮助手消息后，异步让 LLM 从历史里提炼"关于用户/项目/技术栈/偏好的事实"，
 * 存进 session 作用域的 AgentRule。下次同会话对话时自动拼进 system prompt。
 *
 * 失败静默降级——提炼失败只 log，不影响主对话流程。
 */
@Service
@Slf4j
public class SessionMemoryExtractionService {

    /** 每 N 轮助手消息提炼一次（8 轮 = 4 次完整问答） */
    private static final int EXTRACT_INTERVAL = 8;

    /** 提炼用的 instruction prompt */
    private static final String EXTRACT_INSTRUCTION = """
            从下面这段对话历史中，提炼关于用户、项目、技术栈、偏好的事实。
            每条一行，用 "- " 开头。不超过 10 条。

            规则：
            - 只写事实，不写推测（能确定用户偏好 Python），不确定的不要写
            - 不要写"这是一段对话"这类废话，每条都是具体事实
            - 格式：每条独立一行，开头 "- "
            - 如果对话太短还看不出事实，返回空字符串

            对话历史：
            ---
            %s
            ---
            """;

    private final ChatMessageRepository messageRepo;
    private final AgentRuleRepository ruleRepo;
    private final SpringAiAgentClient agentClient;

    /** 提炼任务用独立线程池（跟 SSE 解耦，提炼失败不阻塞对话） */
    private final Executor extractionExecutor;

    public SessionMemoryExtractionService(ChatMessageRepository messageRepo,
                                           AgentRuleRepository ruleRepo,
                                           SpringAiAgentClient agentClient) {
        this.messageRepo = messageRepo;
        this.ruleRepo = ruleRepo;
        this.agentClient = agentClient;
        // 简单起见用 CompletableFuture 默认线程池；如果后续需要隔离可以换独立 ThreadPoolTaskExecutor
        this.extractionExecutor = Runnable::run; // 主调用方已经用 CompletableFuture.runAsync 提交到 ForkJoinPool
    }

    /**
     * 供 AgentChatController 在保存 assistant 消息后调用。
     * 判断是否到了触发点 → 是则异步提炼并存 AgentRule。
     *
     * @param sessionId 当前会话
     * @param userId    当前用户
     * @param apiKey    主对话用的 API Key（提炼复用同一个 key）
     * @param model     主对话用的模型（提炼也复用）
     */
    public void onAssistantMessage(Long sessionId, Long userId, String apiKey, String model) {
        if (sessionId == null || userId == null || apiKey == null || model == null) {
            return;
        }

        try {
            // 查当前会话已有的助手消息数
            long assistantCount = messageRepo.countBySessionIdAndRole(
                    sessionId, ChatMessage.MessageRole.ASSISTANT);

            // 没到触发点 → 跳过
            if (assistantCount <= 0 || assistantCount % EXTRACT_INTERVAL != 0) {
                return;
            }

            // 到了触发点（8 / 16 / 24 ... 轮）→ 异步提炼
            final long targetCount = assistantCount;
            CompletableFuture.runAsync(() -> {
                try {
                    extractAndStore(sessionId, userId, apiKey, model, targetCount);
                } catch (Exception e) {
                    // 提炼失败只 log，不影响主对话
                    log.warn("会话记忆提炼失败 [sessionId={}, assistantCount={}]: {}",
                            sessionId, targetCount, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.debug("会话记忆触发判断失败 [sessionId={}]: {}", sessionId, e.getMessage());
        }
    }

    private void extractAndStore(Long sessionId, Long userId, String apiKey, String model, long assistantCount) {
        // ① 拉历史（只拉 USER + ASSISTANT，跳过工具调用内部消息避免噪音）
        List<ChatMessage> all = messageRepo.findBySessionIdOrderByCreateTimeAsc(sessionId);
        StringBuilder historyText = new StringBuilder();
        int userTurn = 0;
        int assistantTurn = 0;
        for (ChatMessage msg : all) {
            ChatMessage.MessageRole role = msg.getRole();
            String content = msg.getContent() == null ? "" : msg.getContent().trim();
            if (content.isBlank()) continue;

            if (role == ChatMessage.MessageRole.USER) {
                userTurn++;
                historyText.append("【用户 #").append(userTurn).append("】\n").append(content).append("\n\n");
            } else if (role == ChatMessage.MessageRole.ASSISTANT) {
                assistantTurn++;
                // 只取助手回答的前 400 字符，避免工具调用内部思考（trace 里可能有冗长 JSON）
                String shortContent = content.length() > 400 ? content.substring(0, 400) + "..." : content;
                historyText.append("【助手 #").append(assistantTurn).append("】\n").append(shortContent).append("\n\n");
            }
            // tool / system role 跳过
        }

        if (historyText.length() < 50) {
            log.debug("会话历史太短，跳过提炼 [sessionId={}]", sessionId);
            return;
        }

        // ② 调用 LLM 提炼（低 temperature，确定性输出）
        List<ObjectNode> messages = new ArrayList<>();
        ObjectNode sysMsg = JsonNodeFactory.instance.objectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", "你是一个对话历史提炼器。严格按 instruction 输出。");
        messages.add(sysMsg);

        ObjectNode userMsg = JsonNodeFactory.instance.objectNode();
        userMsg.put("role", "user");
        userMsg.put("content", String.format(EXTRACT_INSTRUCTION, historyText));
        messages.add(userMsg);

        String facts = agentClient.callSync(apiKey, model, messages);
        if (facts == null || facts.isBlank()) {
            log.debug("LLM 返回空事实，跳过存储 [sessionId={}]", sessionId);
            return;
        }

        // ③ 存成 / 更新 AgentRule（scope=SESSION, builtin=true）
        upsertSessionMemoryRule(sessionId, userId, facts, assistantCount);
        log.info("会话记忆提炼完成 [sessionId={}, assistantCount={}], 事实条数约 {}",
                sessionId, assistantCount, facts.lines().filter(l -> l.startsWith("- ")).count());
    }

    private void upsertSessionMemoryRule(Long sessionId, Long userId, String facts, long assistantCount) {
        // 同一个 session 只保留一条 builtin 的记忆规则——后提炼的覆盖前面的，持续进化
        List<AgentRule> existing = ruleRepo.findBySessionIdAndScope(sessionId, RuleScope.SESSION);
        AgentRule builtinMemory = existing.stream()
                .filter(r -> r.isBuiltin() && "会话记忆".equals(r.getName()))
                .findFirst()
                .orElse(null);

        if (builtinMemory != null) {
            builtinMemory.setContent(facts);
            builtinMemory.setDescription("每 " + EXTRACT_INTERVAL + " 轮提炼（当前第 " + assistantCount + " 轮助手消息后）");
            ruleRepo.save(builtinMemory);
        } else {
            AgentRule rule = new AgentRule();
            rule.setName("会话记忆");
            rule.setScope(RuleScope.SESSION);
            rule.setSessionId(sessionId);
            rule.setUserId(userId);
            rule.setEnabled(true);
            rule.setBuiltin(true);
            rule.setContent(facts);
            rule.setDescription("每 " + EXTRACT_INTERVAL + " 轮提炼（当前第 " + assistantCount + " 轮助手消息后）");
            ruleRepo.save(rule);
        }
    }
}
