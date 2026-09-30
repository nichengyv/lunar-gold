package com.capsule.agentkey.client;

import com.capsule.agentkey.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek LLM 客户端：支持同步调用（决策）和流式调用（输出）
 */
@Component
@Slf4j
public class DeepSeekAgentClient {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();
    //ObjectMapper是jackson的核心json工具对象。职责：把Java对象和json字符串相互转换还有构建树形json节点
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${deepseek.api.url:https://api.deepseek.com/chat/completions}")
    private String apiUrl;

    public interface Callback {
        void onChunk(String chunk);//收到一小块文字，反复触发
        void onComplete();//流式全部结束
        void onError(Throwable t);//网络，解析，异常
    }

    /**
     * 同步调用：用于 ReAct 决策阶段（获取 tool_calls）（问 AI 要不要调用工具）
     */
    public JsonNode callSync(String apiKey, List<ObjectNode> messages,
                             List<Map<String, Object>> tools) throws IOException {
        ObjectNode requestBody = mapper.createObjectNode();//新建json对象，用来组装请求体，put（字段名，普通值） set（字段名，jsonnode节点）
        requestBody.put("model", "deepseek-chat");//指定调用模型
        requestBody.set("messages", mapper.valueToTree(messages));//把对话列表转换为json格式，放进请求
        if (tools != null && !tools.isEmpty()) {
            requestBody.set("tools", mapper.valueToTree(tools));//把工具定义列表传给大模型
            requestBody.put("tool_choice", "auto");//让模型自动选择工具
        }
        requestBody.put("stream", false);//关闭流式输出，一次性接收完整响应再返回

        Request request = buildRequest(apiKey, requestBody);//把前面打包好的数据还有apikey打包成HTTP请求对象
        //newcall把request对象打包成可执行的调用任务  execute：真正调用任务
        try (Response response = httpClient.newCall(request).execute()) {
            //从响应对象里拿出完整的json文本内容
            //body获取响应体   string把响应体里的字节流一次性读取并转换为java字符串
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("API错误: " + response.code() + " " + body);
            }
            //把大模型返回的json对象转换为树形对象（这样Java程序能直接操作）
            return mapper.readTree(body);
        }
    }

    /**
     * 流式调用，用于最终输出答案给用户
     */
    public void streamChat(String apiKey, List<ObjectNode> messages,
                           List<Map<String, Object>> tools, Callback callback) {
        try {
            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("model", "deepseek-chat");
            requestBody.set("messages", mapper.valueToTree(messages));
            if (tools != null && !tools.isEmpty()) {
                requestBody.set("tools", mapper.valueToTree(tools));
                requestBody.put("tool_choice", "auto");
            }
            requestBody.put("stream", true);//启用流式sse返回

            Request request = buildRequest(apiKey, requestBody);
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "无响应";
                    callback.onError(new IOException("流式调用失败: " + response.code() + " " + err));
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        //byteStream()拿到原始字节输入流   InputStreamReader字节流转字符流，后面指定了解码用UTF-8
                        new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                    String line;//考虑到sse协议格式，循环逐行解析
                    while ((line = reader.readLine()) != null) {

                        //截取：把前面“data：6”这六个字符切掉，拿到纯json字符串
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            //[DONE]是结束标记，当遍历到最后一行就是它
                            if ("[DONE]".equals(data)) {
                                callback.onComplete();//结束回调
                                return;
                            }
                            try {
                                JsonNode node = mapper.readTree(data);//将json字符串解析为树形结构，方便下面使用多个path按层级提取数据
                                JsonNode delta = node.path("choices").path(0).path("delta");//dalta是增量片段，只装刚刚生成出的一小段文字
                                if (delta.has("content")) {//流式输出过程中，有些数据库可能只包含role，这里判断有没有文本内容
                                    String content = delta.get("content").asText();
                                    if (content != null && !content.isEmpty()) {
                                        callback.onChunk(content);//回调向外吐出每一小段文字，上层业务发给前端，实现打字效果
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("解析 SSE 失败: {}", data);
                            }
                        }
                    }
                    callback.onComplete();
                }
            }
        } catch (Exception e) {
            log.error("流式请求失败", e);
            callback.onError(e);
        }
    }

    private Request buildRequest(String apiKey, ObjectNode requestBody) throws IOException {
        return new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(
                        mapper.writeValueAsString(requestBody),
                        MediaType.get("application/json; charset=utf-8")))
                .build();
    }
}