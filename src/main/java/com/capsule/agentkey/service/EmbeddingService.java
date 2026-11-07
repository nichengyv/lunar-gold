package com.capsule.agentkey.service;

import com.capsule.agentkey.config.SpringAiEmbeddingModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Embedding 服务：调用兼容 OpenAI 格式的 Embedding API
 * 默认使用硅基流动 SiliconFlow（DeepSeek 不提供 Embedding 端点）
 * 模型：BAAI/bge-m3（多语言，1024 维）
 *
 * 第四十六批接入 Spring AI OpenAiEmbeddingModel，第四十九批删旧后为唯一实现；
 * 地址与模型由 {@link SpringAiEmbeddingModelConfig} 读取 embedding.api.url / embedding.model。
 * 上传入库与查询检索共用本类，两处调用方无需感知实现差异。
 */
@Service
@Slf4j
public class EmbeddingService {

    private final SpringAiEmbeddingModelConfig springAiEmbeddingModelConfig;

    public EmbeddingService(SpringAiEmbeddingModelConfig springAiEmbeddingModelConfig) {
        this.springAiEmbeddingModelConfig = springAiEmbeddingModelConfig;
    }

    /**
     * 批量获取文本的 Embedding 向量
     * @param apiKey   Embedding 服务 API Key（SiliconFlow 等，兼容 OpenAI 格式）
     * @param texts    文本列表
     * @return float[][] 每个文本对应的向量
     */
    public float[][] embed(String apiKey, List<String> texts) throws IOException {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }
        EmbeddingModel model = springAiEmbeddingModelConfig.create(apiKey);
        EmbeddingResponse response = model.embedForResponse(texts);
        List<Embedding> results = new ArrayList<>(response.getResults());
        if (results.size() != texts.size()) {
            throw new IOException("Embedding 返回数量不匹配: 期望 " + texts.size() + "，实际 " + results.size());
        }
        //显式按 index 还原顺序：OpenAI 兼容服务通常已按输入顺序返回，排序防御服务端乱序导致文本与向量错配
        results.sort(Comparator.comparing(Embedding::getIndex));
        float[][] embeddings = new float[texts.size()][];
        for (int i = 0; i < results.size(); i++) {
            embeddings[i] = results.get(i).getOutput();
        }
        return embeddings;
    }

    /**
     * 单条文本 Embedding
     */
    public float[] embedSingle(String apiKey, String text) throws IOException {
        float[][] result = embed(apiKey, List.of(text));//调用批量方法，把单个文本包装成List
        return result.length > 0 ? result[0] : new float[0];//由结果就返回第0条向量，没有就返回空数组
    }
}