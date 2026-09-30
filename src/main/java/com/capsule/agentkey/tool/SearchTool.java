//联网搜索工具（Tavily + DuckDuckGo 降级）
package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 联网搜索工具：基于 Tavily API（国内访问可能超时，建议配置代理或替换为必应搜索）
 * 未配置 tavily.api.key 时，工具不会生效，避免调用超时卡死
 */
@Component
@Slf4j
public class SearchTool implements BaseTool {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    //配置密钥
    @Value("${tavily.api.key:}")
    private String tavilyApiKey;

    //可用性标记：标记这个工具能不能用
    private boolean available = false;

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        // 如果没有配置 Key，返回空描述，LLM 不会调用此工具
        if (!isAvailable()) {
            return "搜索工具未配置，暂不可用";
        }
        return "联网搜索实时信息。当你需要获取最新新闻、当前事件、实时数据、或不确定的知识时，使用此工具。"
                + "支持中文和英文搜索。返回搜索结果摘要和来源链接。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> queryProp = new HashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "搜索关键词，建议用简洁准确的自然语言描述，如'2026年8月北京天气'");
        properties.put("query", queryProp);

        Map<String, Object> maxResultsProp = new HashMap<>();
        maxResultsProp.put("type", "integer");
        maxResultsProp.put("description", "返回结果数量，默认3，最大10");
        properties.put("max_results", maxResultsProp);

        schema.put("properties", properties);
        schema.put("required", new String[]{"query"});
        return schema;
    }

    /**
     * 检查工具是否可用（有配置 Key 才可用）
     */
    public boolean isAvailable() {
        if (!available && tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            available = true;
        }
        return available;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        if (!isAvailable()) {
            return ToolResult.fail("搜索工具未配置 API Key，请在 application.yml 中配置 tavily.api.key");
        }

        long start = System.currentTimeMillis();
        String query = arguments.path("query").asText();
        int maxResults = arguments.path("max_results").asInt(3);
        if (maxResults > 10) {
            maxResults = 10;
        }

        if (query == null || query.trim().isEmpty()) {
            return ToolResult.fail("搜索关键词不能为空");
        }

        return searchByTavily(this, query, maxResults, start);
    }

    private static ToolResult searchByTavily(SearchTool searchTool, String query, int maxResults, long start) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("api_key", searchTool.tavilyApiKey);
            body.put("query", query);
            body.put("max_results", maxResults);
            body.put("search_depth", "advanced");
            body.put("include_answer", true);
            body.put("include_domains", new ArrayList<>());
            body.put("exclude_domains", new ArrayList<>());

            Request request = new Request.Builder()
                    .url("https://api.tavily.com/search")
                    .post(RequestBody.create(
                            MAPPER.writeValueAsString(body),
                            MediaType.get("application/json; charset=utf-8")))
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return ToolResult.fail("Tavily API 错误: HTTP " + response.code());
                }
                String respBody = response.body() != null ? response.body().string() : "";
                JsonNode root = MAPPER.readTree(respBody);

                //结果格式化（把tavily搜索接口返回的json转换为文字）
                StringBuilder sb = new StringBuilder();
                sb.append("【搜索关键词】").append(query).append("\n\n");

                //判断有没有answer总结字段，没有就跳有则跳过，有则拼接
                if (root.has("answer") && !root.path("answer").asText().isEmpty()) {
                    sb.append("【智能摘要】").append(root.path("answer").asText()).append("\n\n");
                }

                //root：最外层json对象
                sb.append("【搜索结果】\n");
                JsonNode results = root.path("results");

                //循环遍历每一天网页结果
                for (int i = 0; i < results.size(); i++) {
                    JsonNode r = results.get(i);
                    sb.append(i + 1).append(". ")
                            //title：网页标题   content：网页内容摘要   url：网页来源链接
                            .append(r.path("title").asText("无标题")).append("\n")
                            .append("   摘要: ").append(r.path("content").asText("无摘要")).append("\n")
                            .append("   来源: ").append(r.path("url").asText("无链接")).append("\n\n");
                }

                return ToolResult.ok(sb.toString(), System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            log.error("Tavily 搜索失败", e);
            return ToolResult.fail("搜索失败（国内网络可能无法访问 Tavily，建议配置代理或更换搜索引擎）: " + e.getMessage());
        }
    }
}