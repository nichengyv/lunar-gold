package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                    return ToolResult.fail("天气服务不可用 (HTTP " + response.code() + ")");
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
            log.warn("天气查询失败: {}", city, e);
            //返回标准化失败结果，异常信息传给大模型
            return ToolResult.fail("查询失败: " + e.getMessage());
        }
    }
}