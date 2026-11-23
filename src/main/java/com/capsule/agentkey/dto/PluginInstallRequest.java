package com.capsule.agentkey.dto;

import java.util.Map;

/**
 * 插件安装请求：指定市场插件 id + 用户填写的凭证值（key=字段名，value=用户输入）。
 */
public record PluginInstallRequest(String pluginId, Map<String, String> credentials) {
}
