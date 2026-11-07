package com.capsule.agentkey.service;

import com.capsule.agentkey.client.SpringAiAgentClient;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 长上下文滚动摘要。
 *
 * 问题：历史消息在 buildMessages 里按「条数 + 字符预算」截断，被截掉的早期对话直接消失，
 * 长会话里模型会忘记用户早就说过的目标、约定和已完成的改动，出现前后矛盾。
 *
 * 做法：当未压缩的历史超过阈值时，把「已压缩游标之后、最近 N 条之前」这一段
 * 交给主模型压成要点摘要，与旧摘要合并后持久化到 chat_session；
 * 之后每轮把摘要注入 system prompt，最近几轮仍保留原文。
 * 摘要是增量滚动的（summary_until_id 游标），不会每轮重复压缩同一段。
 * 摘要调用失败时保持旧摘要并照常对话，不影响主流程。
 */
@Service
@Slf4j
public class ContextSummaryService {

    private final SpringAiAgentClient springAiClient;
    private final ChatSessionRepository sessionRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 摘要压缩用的模型，与普通对话默认模型一致 */
    @Value("${llm.chat.model:deepseek-chat}")
    private String summaryModel;

    @Value("${context.summary.enabled:true}")
    private boolean enabled;

    /** 历史条数超过该值才考虑压缩，避免短会话平白多一次模型调用 */
    @Value("${context.summary.trigger-messages:20}")
    private int triggerMessages;

    /** 最近多少条消息保持原文，不进入摘要（与 buildMessages 保留窗口对齐） */
    @Value("${context.summary.keep-recent-messages:10}")
    private int keepRecentMessages;

    /** 一次至少要压缩多少条新消息才值得调用模型，防止每轮都压缩一两条 */
    @Value("${context.summary.min-new-messages:6}")
    private int minNewMessages;

    /** 送入摘要模型的对话原文上限（字符），超出只取较新的部分 */
    @Value("${context.summary.max-input-chars:12000}")
    private int maxInputChars;

    /** 摘要本身的上限（字符），避免摘要越滚越长，反过来撑爆 system prompt */
    @Value("${context.summary.max-summary-chars:1200}")
    private int maxSummaryChars;

    public ContextSummaryService(SpringAiAgentClient springAiClient,
                                 ChatSessionRepository sessionRepo) {
        this.springAiClient = springAiClient;
        this.sessionRepo = sessionRepo;
    }

    /**
     * 返回本会话当前可用的历史摘要；必要时先压缩增量并持久化。
     * 任何失败都降级为返回旧摘要（可能为空），绝不影响本轮对话。
     *
     * @param history 本轮之前的历史消息（不含当前提问）
     * @param policy  推理档位：保留窗口与模型可见的历史条数对齐，避免压掉还会展示的原文
     */
    public String refresh(String apiKey, Long sessionId, List<ChatMessage> history,
                          AgentPolicy policy) {
        if (!enabled || sessionId == null || history == null || history.isEmpty()) {
            return "";
        }
        ChatSession session = sessionRepo.findById(sessionId).orElse(null);
        if (session == null) {
            return "";
        }
        String oldSummary = session.getContextSummary() == null ? "" : session.getContextSummary().trim();

        //保留窗口跟随推理档位；触发阈值不能低于「保留窗口 + 最小压缩量」，否则永远压不动
        int keepRecent = policy == null ? keepRecentMessages
                : Math.max(2, policy.reasoning().historyCount(false));
        int trigger = Math.max(triggerMessages, keepRecent + minNewMessages);

        if (history.size() <= trigger) {
            return oldSummary;
        }
        // 1) 找到游标之后的起点：游标及更早的消息已经压缩过
        int start = 0;
        Long untilId = session.getSummaryUntilId();
        if (untilId != null) {
            while (start < history.size() && history.get(start).getId() != null
                    && history.get(start).getId() <= untilId) {
                start++;
            }
        }
        // 2) 末尾 keepRecent 条保持原文，不压进摘要
        int end = Math.max(start, history.size() - keepRecent);
        int count = end - start;
        if (count < minNewMessages) {
            return oldSummary;
        }

        List<ChatMessage> batch = history.subList(start, end);
        String transcript = buildTranscript(batch);
        if (transcript.isBlank()) {
            return oldSummary;
        }

        try {
            String merged = summarize(apiKey, oldSummary, transcript);
            if (merged.isBlank()) {
                log.warn("会话摘要返回为空，保留旧摘要 [sessionId={}]", sessionId);
                return oldSummary;
            }
            if (merged.length() > maxSummaryChars) {
                merged = merged.substring(0, maxSummaryChars) + "…";
            }
            // 游标推进到本批最后一条：即使原文因字符预算被丢弃一部分，也不回滚游标，
            // 否则下一次会把更老的对话重新喂给摘要模型，形成重复压缩
            Long lastId = batch.get(batch.size() - 1).getId();
            session.setContextSummary(merged);
            session.setSummaryUntilId(lastId);
            sessionRepo.save(session);
            log.info("会话摘要已滚动更新 [sessionId={}]：压缩 {} 条，摘要 {} 字符",
                    sessionId, count, merged.length());
            return merged;
        } catch (Exception e) {
            log.warn("会话摘要生成失败，降级为旧摘要 [sessionId={}]: {}", sessionId, e.getMessage());
            return oldSummary;
        }
    }

    /** 把一批消息拼成「角色: 内容」的纯文本；超出字符预算时保留较新的部分 */
    private String buildTranscript(List<ChatMessage> batch) {
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (int i = batch.size() - 1; i >= 0; i--) {
            ChatMessage m = batch.get(i);
            String content = m.getContent() == null ? "" : m.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            String role = m.getRole() == ChatMessage.MessageRole.USER ? "用户" : "助手";
            String line = role + ": " + content;
            if (total + line.length() > maxInputChars) {
                int remain = maxInputChars - total;
                if (remain > 0) {
                    lines.add(line.substring(line.length() - remain));
                }
                break;
            }
            lines.add(line);
            total += line.length();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            sb.append(lines.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 调主模型（非流式）把旧摘要与新增对话合并成一段新的要点摘要。
     * 第四十九批删旧后统一走 Spring AI 调用。
     */
    private String summarize(String apiKey, String oldSummary, String transcript) throws Exception {
        List<ObjectNode> messages = new ArrayList<>();

        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        system.put("content", "你是一个会话记忆压缩器。请把用户与助手的对话压缩成简洁的中文要点摘要，"
                + "用于后续对话保持连贯。必须保留：用户的目标与偏好、已确认的结论、已做过的改动或关键命令、"
                + "尚未完成的事项、重要的文件路径或名称。不要编造对话里没有的信息，不要寒暄，不要分点以外的修饰。"
                + "只输出摘要正文，不超过 300 字。");
        messages.add(system);

        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        user.put("content", "【已有摘要】\n" + (oldSummary.isBlank() ? "（无）" : oldSummary)
                + "\n\n【新增对话】\n" + transcript
                + "\n\n请输出合并后的最新摘要（纯文本，不要标题）：");
        messages.add(user);

        return springAiClient.callSync(apiKey, summaryModel, messages).trim();
    }
}
