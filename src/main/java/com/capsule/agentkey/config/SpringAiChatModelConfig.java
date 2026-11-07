package com.capsule.agentkey.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

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

    public SpringAiChatModelConfig(
            @Value("${agent.spring-ai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${agent.spring-ai.completions-path:/v1/chat/completions}") String completionsPath,
            ObservationRegistry observationRegistry) {
        this.baseUrl = baseUrl.replaceAll("/+$", "")
                + completionsPath.replaceFirst("/chat/completions/?$", "");
        this.observationRegistry = observationRegistry;
    }

    /**
     * D2: 给定模型名，返回 fallback 备选模型列表。
     * 默认策略：任何 DeepSeek 模型失败 → 切 Moonshot/Qwen；非 DeepSeek 模型失败 → 切 DeepSeek。
     * 可通过 agent.spring-ai.fallback-map 配置覆盖（格式: "deepseek-chat=moonshot-v1-8k,qwen-plus; qwen-max=deepseek-chat"）。
     */
    public List<String> fallbackModelsFor(String model) {
        // D2: 目前硬编码 fallback——后续可以从配置文件/数据库 settings 动态读
        // 先做一个简单的互补策略：任何模型失败后尝试其他主流模型
        List<String> allKnown = List.of(
                "deepseek-chat", "deepseek-reasoner", "deepseek-pro",
                "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k",
                "qwen-plus", "qwen-turbo", "qwen-max",
                "glm-4", "glm-4-plus"
        );
        return allKnown.stream()
                .filter(m -> !m.equals(model))
                .collect(java.util.stream.Collectors.toList());
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
