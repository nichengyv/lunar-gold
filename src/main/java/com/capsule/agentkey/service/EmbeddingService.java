package com.capsule.agentkey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 服务：调用 DeepSeek / OpenAI 的 Embedding API
 * DeepSeek 模型：deepseek-embedding（或 text-embedding-3-small）
 */
@Service
@Slf4j
public class  EmbeddingService {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${deepseek.api.url:https://api.deepseek.com/chat/completions}")
    private String deepseekBaseUrl;

    @Value("${embedding.api.url:https://api.deepseek.com/embeddings}")
    private String embeddingApiUrl;

    @Value("${embedding.model:deepseek-embedding}")
    private String embeddingModel;

    /**
     * 批量获取文本的 Embedding 向量
     * @param apiKey   DeepSeek API Key
     * @param texts    文本列表
     * @return float[][] 每个文本对应的向量
     */
    public float[][] embed(String apiKey, List<String> texts) throws IOException {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", embeddingModel);
        requestBody.put("encoding_format", "float");
        ArrayNode inputArray = mapper.createArrayNode();
        texts.forEach(inputArray::add);
        requestBody.set("input", inputArray);

        Request request = new Request.Builder()
                .url(embeddingApiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(
                        mapper.writeValueAsString(requestBody),
                        MediaType.get("application/json; charset=utf-8")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Embedding API 错误: {} {}", response.code(), body);
                throw new IOException("Embedding API 错误: " + response.code());
            }

            JsonNode root = mapper.readTree(body);
            JsonNode data = root.path("data");
            float[][] embeddings = new float[texts.size()][];

            for (int i = 0; i < data.size(); i++) {
                JsonNode embeddingNode = data.get(i).path("embedding");
                float[] vec = new float[embeddingNode.size()];
                for (int j = 0; j < embeddingNode.size(); j++) {
                    vec[j] = embeddingNode.get(j).floatValue();
                }
                embeddings[i] = vec;
            }
            return embeddings;
        }
    }

    /**
     * 单条文本 Embedding
     */
    public float[] embedSingle(String apiKey, String text) throws IOException {
        float[][] result = embed(apiKey, List.of(text));
        return result.length > 0 ? result[0] : new float[0];
    }
}