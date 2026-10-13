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
 * Embedding 服务：调用兼容 OpenAI 格式的 Embedding API
 * 默认使用硅基流动 SiliconFlow（DeepSeek 不提供 Embedding 端点）
 * 模型：BAAI/bge-m3（多语言，1024 维）
 */
@Service
@Slf4j
public class EmbeddingService {

    //HTTP请求，全局单例，设置连接超时30s，读取超时60s
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();//Jackson序列化/反序列化工具，处理json报文

    @Value("${embedding.api.url:https://api.siliconflow.cn/v1/embeddings}")//embedding向量接口地址（兼容 OpenAI 格式）
    private String embeddingApiUrl;

    @Value("${embedding.model:BAAI/bge-m3}")//使用的向量模型名称
    private String embeddingModel;

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

        //构建请求json体
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", embeddingModel);
        requestBody.put("encoding_format", "float");//指定返回的向量是float编码格式（另外的还有Base64）
        ArrayNode inputArray = mapper.createArrayNode();//构建数组容器
        texts.forEach(inputArray::add);//把所有文本放入inputArray
        requestBody.set("input", inputArray);//

        //用okhttp把前面组装好的json请求体，封装成一个可执行的http请求对象
        Request request = new Request.Builder()
                .url(embeddingApiUrl)//目标接口地址
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(//放入请求体
                        mapper.writeValueAsString(requestBody),//把object序列化为json字符串，再由RequestBody.create包装成okhttp能发送的体
                        MediaType.get("application/json; charset=utf-8")))//设置数据格式
                .build();

        //执行http调用
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Embedding API 错误: {} {}", response.code(), body);
                throw new IOException("Embedding API 错误: " + response.code());
            }

            //解析返回的json
            JsonNode root = mapper.readTree(body);
            JsonNode data = root.path("data");
            float[][] embeddings = new float[texts.size()][];

            //循环取出每一条向量
            //data是顶层的key，值是数组

            for (int i = 0; i < data.size(); i++) {
                //embedding是数组内每个对象的key，值是浮点数组，就是我们要的向量
                //get(i)获取这个json数组节点的第i个json对象，path从这个json对象按key找到对应的子节点
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
        float[][] result = embed(apiKey, List.of(text));//调用批量方法，把单个文本包装成List
        return result.length > 0 ? result[0] : new float[0];//由结果就返回第0条向量，没有就返回空数组
    }
}