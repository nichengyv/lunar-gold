package com.capsule.agentkey.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * P6-01：客户端最小授权 scope。
 *
 * 命名格式为「资源:动作」，与 Web 端的能力开关（@RequiresCapability）正交：
 * 前者表达「这个客户端被允许做什么」，后者表达「这个部署形态是否支持该功能」。
 */
public enum ApiScope {

    CHAT_WRITE("chat:write"),
    RUN_READ("run:read"),
    CHANGES_READ("changes:read"),
    CHANGES_WRITE("changes:write"),
    APPROVAL_WRITE("approval:write"),
    PROJECT_READ("project:read"),
    CLIENT_ADMIN("client:admin");

    private final String value;

    ApiScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ApiScope> fromValue(String value) {
        return Arrays.stream(values()).filter(scope -> scope.value.equals(value)).findFirst();
    }
}
