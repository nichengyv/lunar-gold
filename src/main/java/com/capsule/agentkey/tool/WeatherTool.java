package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 天气查询工具：实时获取指定城市天气
 */
@Component
@Slf4j
public class WeatherTool implements BaseTool {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    //用于翻译json
    //readTree(String)：把 JSON 字符串 → JsonNode 树
    //writeValueAsString(Object)：把 Java 对象 → JSON 字符串
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 降级通道：wttr.in 服务器在欧洲，国内直连经常超时（2026-11-03 实测本机 connect 直接超时）。
     * 失败时按序降级：① Open-Meteo（免 key，国内可达）② web_search（需 tavily.api.key）。
     */
    private final SearchTool searchTool;

    public WeatherTool(SearchTool searchTool) {
        this.searchTool = searchTool;
    }

    @Override
    //设置注册在工具箱里的工具名字（工具唯一标识）
    public String getName() {
        return "get_current_weather";
    }

    @Override
    public String getDescription() {
        return "获取指定城市的实时天气信息，包括温度、湿度、风速、天气状况等。"
                + "当用户询问天气、气温、穿衣建议时调用。";
    }

    //构建JSON Schema参数规范
    @Override
    public Map<String, Object> getParametersSchema() {
        //最外层，整个参数对象的根节点（最外层的大括号，每个map代表一个大括号）
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        //存放所有入参的定义
        Map<String, Object> properties = new HashMap<>();

        //定义city这个参数的规则（描述，类型）
        Map<String, Object> cityProp = new HashMap<>();
        cityProp.put("type", "string");
        cityProp.put("description", "城市名称，如'北京'、'Shanghai'。支持中英文。");
        properties.put("city", cityProp);

        //properties：列出每一个参数
        schema.put("properties", properties);
        //必填参数列表：调用此工具必传city（required声明一个不能为空的清单）
        schema.put("required", new String[]{"city"});
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        //开始时间，记录工具耗时
        long start = System.currentTimeMillis();
        //从大模型传入参数中取出city
        String city = arguments.path("city").asText();
        if (city == null || city.trim().isEmpty()) {
            return ToolResult.fail("城市名称不能为空");
        }

        try {
            //拼接第三方天气接口地址
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String url = "https://wttr.in/" + city + "?format=j1&lang=zh";
            //构建HTTP GET请求（填写网址url，请求方式get/set）
            Request request = new Request.Builder().url(url).get().build();
            //发送网络请求，使用try—with—resources自动关闭response流
            //HTTP_CLIENT是客户端对象，newcall根据请求体创建一个请求（Call对象）
            //execute发起请求，执行Call对象
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return fallbackByOpenMeteo(city, start, "天气服务不可用 (HTTP " + response.code() + ")");
                }

                //读取接口返回的响应体字符串，为空则赋值空字符串
                String body = response.body() != null ? response.body().string() : "";
                //将返回的json字符串解析为jsonNode
                JsonNode root = MAPPER.readTree(body);
                //取出current_condition数组的第零个元素（实时天气数据）
                JsonNode current = root.path("current_condition").path(0);

                //逐个提取天气字段，取不到默认填充“未知”
                String weatherDesc = current.path("weatherDesc").path(0).path("value").asText("未知");
                String temp = current.path("temp_C").asText("未知");
                String humidity = current.path("humidity").asText("未知");
                String feelsLike = current.path("FeelsLikeC").asText("未知");
                String wind = current.path("windspeedKmph").asText("未知");
                String visibility = current.path("visibility").asText("未知");
                String pressure = current.path("pressure").asText("未知");

                //格式化整理成一段可读文本，交给大模型继续润色回答用户
                String result = String.format(
                        "【%s实时天气】\n天气状况: %s\n温度: %s℃\n体感温度: %s℃\n湿度: %s%%\n风速: %s公里/小时\n能见度: %s公里\n气压: %s百帕",
                        city, weatherDesc, temp, feelsLike, humidity, wind, visibility, pressure
                );
                //成功返回工具结果，附带本次执行耗时
                return ToolResult.ok(result, System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            //捕获全部异常（网络超时，json解析失败，接口异常等），打印警告日志
            log.warn("天气查询失败，尝试降级: {}", city, e);
            //直连失败时按序降级：Open-Meteo（免 key）→ web_search（需 Tavily key），都失败才返回错误
            return fallbackByOpenMeteo(city, start, "查询失败: " + e.getMessage());
        }
    }

    /** WMO weather_code → 中文天气状况（覆盖常见码，未收录的显示"未知(<code>)"） */
    private static String wmoDescription(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "大致晴";
            case 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 66, 67 -> "冻雨";
            case 71 -> "小雪";
            case 73 -> "中雪";
            case 75 -> "大雪";
            case 77 -> "雪粒";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "未知(" + code + ")";
        };
    }

    /**
     * 第一级降级：Open-Meteo（免 key，2026-11-03 实测国内可达）。
     * 先地理编码拿城市坐标，再查实时天气；任一步失败则继续降级到搜索。
     */
    private ToolResult fallbackByOpenMeteo(String city, long start, String originalError) {
        try {
            String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(city, StandardCharsets.UTF_8) + "&count=1&language=zh";
            JsonNode geoRoot = httpGetJson(geoUrl);
            JsonNode first = geoRoot.path("results").path(0);
            if (first.isMissingNode()) {
                return fallbackBySearch(city, start, originalError + "；Open-Meteo 找不到城市: " + city);
            }
            double lat = first.path("latitude").asDouble();
            double lon = first.path("longitude").asDouble();

            String forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,surface_pressure"
                    + "&timezone=auto";
            JsonNode current = httpGetJson(forecastUrl).path("current");

            String result = String.format(
                    "【%s实时天气（Open-Meteo 数值预报，wttr.in 直连失败的降级结果）】\n天气状况: %s\n温度: %s℃\n体感温度: %s℃\n湿度: %s%%\n风速: %s公里/小时\n气压: %s百帕",
                    city,
                    wmoDescription(current.path("weather_code").asInt(-1)),
                    current.path("temperature_2m").asText("未知"),
                    current.path("apparent_temperature").asText("未知"),
                    current.path("relative_humidity_2m").asText("未知"),
                    current.path("wind_speed_10m").asText("未知"),
                    current.path("surface_pressure").asText("未知"));
            return ToolResult.ok(result, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("Open-Meteo 降级失败: {}", city, e);
            return fallbackBySearch(city, start, originalError + "；Open-Meteo 降级失败: " + e.getMessage());
        }
    }

    private JsonNode httpGetJson(String url) throws Exception {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP " + response.code());
            }
            return MAPPER.readTree(response.body() != null ? response.body().string() : "");
        }
    }

    /**
     * wttr.in 直连失败后的降级：用 web_search 查「城市 + 现在天气」。
     * 搜索可用且成功 → 返回带降级说明的成功结果；搜索未配置或也失败 → 返回合并后的失败文案。
     */
    private ToolResult fallbackBySearch(String city, long start, String originalError) {
        if (searchTool == null || !searchTool.isAvailable()) {
            return ToolResult.fail(originalError + "（搜索降级未生效：未配置 tavily.api.key）");
        }
        ObjectNode args = MAPPER.createObjectNode();
        args.put("query", city + " 现在天气 气温");
        args.put("max_results", 3);
        ToolResult searchResult = searchTool.execute(args);
        if (searchResult.isSuccess()) {
            return ToolResult.ok("wttr.in 直连失败（" + originalError + "），以下为联网搜索降级结果：\n\n"
                    + searchResult.getContent(), System.currentTimeMillis() - start);
        }
        return ToolResult.fail(originalError + "；搜索降级也失败：" + searchResult.getError());
    }
}