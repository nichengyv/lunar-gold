package com.capsule.agentkey.client;

import com.capsule.agentkey.config.SpringAiChatModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话客户端（第四十四批接入 Spring AI，第四十九批删旧后为唯一实现）。
 *
 * 流式签名与回调走中立接口 {@link AgentStreamCallback}，
 * ReAct 循环只换调用实现，不改编排逻辑、不改 SSE 事件协议。
 *
 * 行为约定：
 * - 文本按 delta 逐片回调 onChunk；
 * - 工具调用按分片累积成完整 JSON，结束后一次性回调 onToolCalls；
 * - 结束后回调 onComplete，异常回调 onError。
 */
@Component
@Slf4j
public class SpringAiAgentClient {

    private final SpringAiChatModelConfig modelConfig;

    public SpringAiAgentClient(SpringAiChatModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    /** D2: streamChat 结果 —— 成功 null，失败返回异常；sentChunk 标记是否已经输出过 */
    public record StreamResult(Throwable error, boolean sentChunk) {}

    public StreamResult streamChat(String apiKey, String model, List<ObjectNode> messages,
                           List<ToolCallback> toolCallbacks, String memorySummary,
                           AgentStreamCallback callback) {
        // D2: 主模型 fallback——如果配置了备选模型，第一次失败（且还没输出任何 chunk）就换
        List<String> fallbacks = modelConfig.fallbackModelsFor(model);
        StreamResult first = doStream(apiKey, model, messages, toolCallbacks, memorySummary, callback);
        if (first.error() == null || fallbacks.isEmpty()) return first;

        // 已经发过 chunk 了就不重试——用户已经看到部分输出
        if (first.sentChunk()) return first;

        // 依次尝试 fallback
        Throwable lastErr = first.error();
        for (String fb : fallbacks) {
            log.warn("D2 主模型 {} 失败（{}），尝试 fallback {}", model, lastErr.getMessage(), fb);
            StreamResult r = doStream(apiKey, fb, messages, toolCallbacks, memorySummary, callback);
            if (r.error() == null) {
                log.info("D2 fallback {} 成功", fb);
                return r;
            }
            if (r.sentChunk()) return r; // fallback 也输出过 chunk 了就停
            lastErr = r.error();
        }
        return new StreamResult(lastErr, false);
    }

    private StreamResult doStream(String apiKey, String model, List<ObjectNode> messages,
                                  List<ToolCallback> toolCallbacks, String memorySummary,
                                  AgentStreamCallback callback) {
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        AtomicBoolean sentChunk = new AtomicBoolean(false);

        // Spring AI 的流式 chunk 里工具调用仍是分片（id/name 首片出现，arguments 多片），
        // 这里按 id 累积；id 缺失的后续分片归到最近一个调用上。
        Map<String, ObjectNode> toolCallAcc = new LinkedHashMap<>();
        try {
            ChatModel chatModel = modelConfig.create(apiKey, model);
            Prompt prompt = new Prompt(toSpringAiMessages(messages), buildOptions(model, toolCallbacks));
            // 包装 callback：onChunk 触发时标记 sentChunk
            AgentStreamCallback wrapped = new AgentStreamCallback() {
                @Override public void onChunk(String c) { sentChunk.set(true); callback.onChunk(c); }
                @Override public void onToolCalls(List<ObjectNode> tc) { callback.onToolCalls(tc); }
                @Override public void onComplete() { callback.onComplete(); }
                @Override public void onError(Throwable t) { callback.onError(t); }
            };
            if (memorySummary == null || memorySummary.isBlank()) {
                chatModel.stream(prompt)
                        .doOnNext(response -> handleResponse(response, wrapped, toolCallAcc))
                        .doOnError(streamError::set)
                        .blockLast();
            } else {
                ChatClient chatClient = ChatClient.builder(chatModel).build();
                chatClient.prompt(prompt)
                        .advisors(SessionMemoryAdvisor.of(memorySummary))
                        .stream()
                        .chatResponse()
                        .doOnNext(response -> handleResponse(response, wrapped, toolCallAcc))
                        .doOnError(streamError::set)
                        .blockLast();
            }

            if (streamError.get() != null) {
                return new StreamResult(streamError.get(), sentChunk.get());
            }
            emitToolCalls(toolCallAcc, wrapped);
            return new StreamResult(null, sentChunk.get());
        } catch (Exception e) {
            log.error("Spring AI 流式请求失败 (model={})", model, e);
            callback.onError(e);
            return new StreamResult(e, sentChunk.get());
        }
    }

    /**
     * 第四十八批：非流式文本调用，供会话摘要等辅助任务使用。
     * 失败直接抛出，由调用方沿用原有降级策略（摘要失败则跳过注入）。
     */
    public String callSync(String apiKey, String model, List<ObjectNode> messages) {
        ChatModel chatModel = modelConfig.create(apiKey, model);
        Prompt prompt = new Prompt(toSpringAiMessages(messages),
                OpenAiChatOptions.builder().model(model).build());
        return extractText(chatModel.call(prompt));
    }

    /**
     * 第四十八批：多模态转写，图片以 data URL 传入（与自研客户端收到的格式一致）。
     * 转成 Spring AI 的 UserMessage + Media，由 OpenAI 协议适配层编码成 image_url。
     */
    public String callVision(String apiKey, String model, String instruction, List<String> imageDataUrls) {
        List<Media> media = new ArrayList<>();
        if (imageDataUrls != null) {
            for (String url : imageDataUrls) {
                Media item = parseDataUrl(url);
                if (item != null) {
                    media.add(item);
                }
            }
        }
        UserMessage userMessage = UserMessage.builder().text(instruction).media(media).build();
        ChatModel chatModel = modelConfig.create(apiKey, model);
        Prompt prompt = new Prompt(List.of(userMessage),
                OpenAiChatOptions.builder().model(model).build());
        return extractText(chatModel.call(prompt));
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /** 解析 data:image/png;base64,xxx，失败返回 null（跳过该图，其余图片照常转写） */
    private Media parseDataUrl(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            log.warn("图片 data URL 格式不正确，已跳过");
            return null;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            log.warn("图片 data URL 缺少 base64 内容，已跳过");
            return null;
        }
        String header = dataUrl.substring(5, comma);
        int semi = header.indexOf(';');
        String mime = semi >= 0 ? header.substring(0, semi) : header;
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            return new Media(MimeType.valueOf(mime), new ByteArrayResource(bytes));
        } catch (Exception e) {
            log.warn("图片 data URL 解析失败，已跳过: {}", e.getMessage());
            return null;
        }
    }

    private void handleResponse(ChatResponse response, AgentStreamCallback callback,
                                Map<String, ObjectNode> toolCallAcc) {
        if (response == null || response.getResults() == null) {
            return;
        }
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output == null) {
                continue;
            }
            String text = output.getText();
            if (text != null && !text.isEmpty()) {
                callback.onChunk(text);
            }
            if (output.hasToolCalls()) {
                accumulateToolCalls(output.getToolCalls(), toolCallAcc);
            }
        }
    }

    /**
     * 把 OpenAI 的流式工具调用分片拼回完整调用。
     * 首片带 id/name，后续分片只有 arguments，因此无 id 时挂到最近一个累加器。
     */
    private void accumulateToolCalls(List<AssistantMessage.ToolCall> fragments,
                                     Map<String, ObjectNode> acc) {
        String lastKey = acc.isEmpty() ? null : new ArrayList<>(acc.keySet()).get(acc.size() - 1);
        for (AssistantMessage.ToolCall fragment : fragments) {
            String id = fragment.id();
            ObjectNode target;
            if (id != null && !id.isEmpty()) {
                target = acc.computeIfAbsent(id, this::newToolCall);
                lastKey = id;
            } else if (lastKey != null) {
                target = acc.get(lastKey);
            } else {
                lastKey = "call_" + acc.size();
                target = newToolCall(lastKey);
                acc.put(lastKey, target);
            }

            ObjectNode function = (ObjectNode) target.get("function");
            if (fragment.name() != null && !fragment.name().isEmpty()
                    && function.path("name").asText("").isEmpty()) {
                function.put("name", fragment.name());
            }
            if (fragment.arguments() != null) {
                function.put("arguments", function.path("arguments").asText("") + fragment.arguments());
            }
        }
    }

    private ObjectNode newToolCall(String id) {
        ObjectNode call = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        call.put("id", id);
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", "");
        function.put("arguments", "");
        return call;
    }

    private void emitToolCalls(Map<String, ObjectNode> acc, AgentStreamCallback callback) {
        if (!acc.isEmpty()) {
            callback.onToolCalls(new ArrayList<>(acc.values()));
        }
    }

    /**
     * 工具定义由 BaseTool.toToolCallback 提供（ToolRegistry 完成权限过滤），
     * Spring AI 2 只负责解析定义并转换为 OpenAI 协议，不执行工具；
     * 实际调用仍由自研 ReAct 循环和 ToolRegistry.dispatch 完成。
     */
    private OpenAiChatOptions buildOptions(String model, List<ToolCallback> toolCallbacks) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(model);
        if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks).toolChoice("auto");
        }
        return builder.build();
    }

    /**
     * 把 ReAct 循环里的 OpenAI 线格式消息翻译成 Spring AI 消息对象。
     * 角色映射：system / user / assistant(+tool_calls) / tool(tool_call_id)。
     */
    private List<Message> toSpringAiMessages(List<ObjectNode> messages) {
        List<Message> result = new ArrayList<>();
        Map<String, String> toolNames = new HashMap<>();
        for (ObjectNode node : messages) {
            String role = node.path("role").asText("");
            switch (role) {
                case "system" -> result.add(new SystemMessage(node.path("content").asText("")));
                case "user" -> result.add(new UserMessage(node.path("content").asText("")));
                case "assistant" -> result.add(toAssistantMessage(node, toolNames));
                case "tool" -> result.add(toToolResponseMessage(node, toolNames));
                default -> log.debug("忽略未知角色消息: {}", role);
            }
        }
        return result;
    }

    private AssistantMessage toAssistantMessage(ObjectNode node, Map<String, String> toolNames) {
        String content = node.hasNonNull("content") ? node.get("content").asText() : "";
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode toolCall : node.path("tool_calls")) {
            String id = toolCall.path("id").asText("");
            String name = toolCall.path("function").path("name").asText("");
            String arguments = toolCall.path("function").path("arguments").asText("");
            toolCalls.add(new AssistantMessage.ToolCall(id, "function", name, arguments));
            toolNames.put(id, name);
        }
        return AssistantMessage.builder().content(content).toolCalls(toolCalls).build();
    }

    private ToolResponseMessage toToolResponseMessage(ObjectNode node, Map<String, String> toolNames) {
        String id = node.path("tool_call_id").asText("");
        String name = toolNames.getOrDefault(id, "tool");
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        id, name, node.path("content").asText(""))))
                .build();
    }
}
