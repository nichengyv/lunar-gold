package com.capsule.agentkey.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Spring AI 模型工厂（第四十四批接入，第四十九批删旧后为唯一对话调用入口）。
 *
 * 不注册 ChatModel Bean：用户 Key 是运行时从数据库解密出来的，
 * 启动阶段既拿不到 Key，也不该因为缺 Key 而启动失败。
 * 每次对话按「用户 Key + 模型名」现构建一个轻量 ChatModel 实例。
 *
 * 通过 OpenAI 兼容协议接入任意 provider（DeepSeek、智谱、Moonshot、Ollama 等），
 * base-url 由配置项 agent.spring-ai.base-url 指定，默认为 DeepSeek。
 */
@Configuration
public class SpringAiChatModelConfig {

    /** OpenAI Java SDK base URL，包含 /v1，但不含 /chat/completions */
    private final String baseUrl;
    /**
     * 第五十批：注入容器里真实的 ObservationRegistry（替换 NOOP），
     * Spring AI 的 DefaultChatModelObservationConvention 据此产出
     * gen_ai.client.operation（模型延迟）与 gen_ai.client.token.usage（token 用量）指标。
     */
    private final ObservationRegistry observationRegistry;

    /**
     * P4-06：跨 Provider 的备选模型必须显式配置。
     * 旧实现把「所有已知模型」都当 fallback，但同一把 DeepSeek 密钥去打
     * moonshot/qwen 只会得到 400，属于无效请求；默认改为空，需要时再配。
     */
    private final List<String> fallbackModels;
    private final int maxAttempts;
    private final long retryBackoffMs;
    private final long idleTimeoutSeconds;
    private final long syncTimeoutSeconds;

    public SpringAiChatModelConfig(
            @Value("${agent.spring-ai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${agent.spring-ai.completions-path:/v1/chat/completions}") String completionsPath,
            @Value("${agent.spring-ai.fallback-models:}") String fallbackModels,
            @Value("${agent.spring-ai.max-attempts:3}") int maxAttempts,
            @Value("${agent.spring-ai.retry-backoff-ms:500}") long retryBackoffMs,
            @Value("${agent.spring-ai.idle-timeout-seconds:60}") long idleTimeoutSeconds,
            @Value("${agent.spring-ai.sync-timeout-seconds:60}") long syncTimeoutSeconds,
            ObservationRegistry observationRegistry) {
        this.baseUrl = baseUrl.replaceAll("/+$", "")
                + completionsPath.replaceFirst("/chat/completions/?$", "");
        this.fallbackModels = parseModels(fallbackModels);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoffMs = Math.max(0L, retryBackoffMs);
        this.idleTimeoutSeconds = Math.max(1L, idleTimeoutSeconds);
        this.syncTimeoutSeconds = Math.max(1L, syncTimeoutSeconds);
        this.observationRegistry = observationRegistry;
    }

    private static List<String> parseModels(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 熔断与重试的隔离维度：同一个 base-url 视为同一个 Provider */
    public String providerId() {
        return baseUrl;
    }

    /** 给定模型名，返回显式配置的 fallback 备选模型（已排除自身） */
    public List<String> fallbackModelsFor(String model) {
        return fallbackModels.stream()
                .filter(m -> !m.equals(model))
                .toList();
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long retryBackoffMs() {
        return retryBackoffMs;
    }

    public long idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public long syncTimeoutSeconds() {
        return syncTimeoutSeconds;
    }

    /**
     * @param apiKey 用户已存的 DEEPSEEK 密钥（明文，仅存在于本次请求内存中）
     * @param model  本轮使用的模型名
     */
    public ChatModel create(String apiKey, String model) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .observationRegistry(observationRegistry)
                .build();
    }
}
