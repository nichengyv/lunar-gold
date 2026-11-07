package com.capsule.agentkey.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Spring AI Embedding 模型工厂（第四十六批接入，第四十九批删旧后为唯一向量化实现）。
 *
 * 和 ChatModel 一样不注册 Bean：Embedding 的 Key 是运行时按用户从数据库解密的
 * （SiliconFlow 的 Key 或全局兜底 Key），启动阶段拿不到，也不该因缺 Key 启动失败。
 * 每次向量化请求按「用户 Key」现建一个轻量 EmbeddingModel。
 *
 * 地址来源：embedding.api.url 本来就是 OpenAI 兼容的完整端点
 * （默认 https://api.siliconflow.cn/v1/embeddings），这里拆成
 * baseUrl=https://api.siliconflow.cn + embeddingsPath=/v1/embeddings，
 * 换服务商时只改一个配置项。
 */
@Configuration
public class SpringAiEmbeddingModelConfig {

    private final String baseUrl;
    private final String model;
    /** 第五十批：真实 ObservationRegistry，产出 Embedding 调用延迟与 token 用量指标 */
    private final ObservationRegistry observationRegistry;

    public SpringAiEmbeddingModelConfig(
            @Value("${embedding.api.url:https://api.siliconflow.cn/v1/embeddings}") String apiUrl,
            @Value("${embedding.model:BAAI/bge-m3}") String model,
            ObservationRegistry observationRegistry) {
        URI uri = URI.create(apiUrl);
        String path = uri.getRawPath();
        String apiBasePath = (path == null || path.isBlank())
                ? "/v1" : path.replaceFirst("/embeddings/?$", "");
        this.baseUrl = uri.getScheme() + "://" + uri.getAuthority() + apiBasePath;
        this.model = model;
        this.observationRegistry = observationRegistry;
    }

    /**
     * @param apiKey 用户配置的 SILICONFLOW 密钥（或全局兜底密钥），仅存在于本次调用内存中
     */
    public EmbeddingModel create(String apiKey) {
        return OpenAiEmbeddingModel.builder()
                .metadataMode(MetadataMode.NONE)//只送原文，不把元数据拼进向量
                .options(OpenAiEmbeddingOptions.builder()
                        .model(model)
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT)//避免返回 Base64
                        .build())
                .observationRegistry(observationRegistry)
                .build();
    }
}
