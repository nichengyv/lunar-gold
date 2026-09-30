package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class WebFetchTool implements BaseTool {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    @Override
    public String getName() {
        return "fetch_webpage";
    }

    @Override
    public String getDescription() {
        return "抓取指定网页的正文内容。当用户提供了具体URL、或需要读取某篇文章、文档、" +
                "GitHub页面、新闻详情时，使用此工具获取页面文字内容。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> url = new HashMap<>();
        url.put("type", "string");
        url.put("description", "完整的网页URL，如 https://example.com/article");
        props.put("url", url);
        schema.put("properties", props);
        //required标签用于标记必传参数
        schema.put("required", new String[]{"url"});
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        String url = arguments.path("url").asText();
        if (url == null || !url.startsWith("http")) {
            return ToolResult.fail("请提供有效的 http/https URL");
        }
        try {

            //User—Agent（UA）伪装（让浏览器允许程序访问）
            //这里url：要访问的地址
            Request request = new Request.Builder().url(url).get()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build();
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return ToolResult.fail("网页访问失败: HTTP " + response.code());
                }
                String html = response.body() != null ? response.body().string() : "";

                // 从网页扒取原始html后，把没用的广告，导航，侧边栏全部删掉
                Document doc = Jsoup.parse(html);
                // 移除脚本和样式{script：JavaScript 代码
                //style：CSS 样式
                //nav：导航栏（通常包含大量重复链接）
                //footer：页脚（版权、备案号、友情链接）
                //header：页头（Logo、菜单）
                //aside：侧边栏（广告、推荐文章）
                //先 .remove()，再取 .text()——顺序不能反}
                doc.select("script, style, nav, footer, header, aside").remove();
                String text = doc.body().text();
                // 压缩空白
                //replaceAll("\\s+", " ")	把所有空白（空格、Tab、换行、全角空格）压缩成单个空格
                text = text.replaceAll("\\s+", " ").trim();
                // 截断过长内容
                //trim()	去掉首尾空白
                //> 3000 截断	防止超长网页（如小说、论坛）撑爆大模型的 Token 限制
                if (text.length() > 3000) {
                    text = text.substring(0, 3000) + "\n\n[内容过长，已截断]";
                }
                return ToolResult.ok("【网页内容】\nURL: " + url + "\n\n" + text);
            }
        } catch (Exception e) {
            log.error("网页抓取失败: {}", url, e);
            return ToolResult.fail("抓取失败: " + e.getMessage());
        }
    }
}