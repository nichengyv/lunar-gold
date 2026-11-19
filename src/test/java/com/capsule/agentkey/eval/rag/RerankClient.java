package com.capsule.agentkey.eval.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * P5-07-3：评测专用的真实 reranker 客户端（SiliconFlow /v1/rerank 兼容结构）。
 *
 * <p>只存在于测试作用域，不进入生产链路。失败一律抛出可读异常：
 * 超时、非 2xx、返回数量不足都视为“本次运行不可用于结论”，不静默降级为无 rerank。</p>
 */
final class RerankClient {

    private final String url;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    RerankClient(String url, String model, String apiKey, int timeoutMs) {
        this.url = url;
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = Duration.ofMillis(Math.max(1, timeoutMs));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    record Ranked(int index, double score) {
    }

    /** tokens 来自响应 meta.tokens.input_tokens（兼容 usage.total_tokens），不可得时为 null。 */
    record Result(List<Ranked> ranked, Integer tokens) {
    }

    Result rerank(String query, List<String> documents, int topN) throws IOException, InterruptedException {
        if (documents == null || documents.isEmpty()) {
            return new Result(List.of(), 0);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("query", query);
        body.put("top_n", Math.min(topN, documents.size()));
        body.put("return_documents", false);
        ArrayNode docs = body.putArray("documents");
        documents.forEach(docs::add);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException("reranker 调用失败（URL=" + url + "）: " + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("reranker 返回 HTTP " + response.statusCode()
                    + "，响应片段: " + snippet(response.body()));
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IOException("reranker 响应缺少 results: " + snippet(response.body()));
        }
        List<Ranked> ranked = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            int index = node.path("index").asInt(-1);
            if (index < 0 || index >= documents.size()) {
                throw new IOException("reranker 返回越界下标 " + index + "（文档数 " + documents.size() + "）");
            }
            ranked.add(new Ranked(index, node.path("relevance_score").asDouble()));
        }
        if (ranked.size() != Math.min(topN, documents.size())) {
            throw new IOException("reranker 返回数量不匹配：期望 "
                    + Math.min(topN, documents.size()) + "，实际 " + ranked.size());
        }
        Integer tokens = null;
        JsonNode inputTokens = root.path("meta").path("tokens").path("input_tokens");
        if (inputTokens.isNumber()) {
            tokens = inputTokens.asInt();
        } else if (root.path("usage").path("total_tokens").isNumber()) {
            tokens = root.path("usage").path("total_tokens").asInt();
        }
        return new Result(ranked, tokens);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }
}
