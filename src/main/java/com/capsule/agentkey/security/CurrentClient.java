package com.capsule.agentkey.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * P6-01：当前请求的客户端身份读取。
 *
 * 与 {@link com.capsule.agentkey.util.CurrentUser} 并列：后者拿 userId，
 * 这里拿「本次请求是否由客户端令牌发起」。Web 本地令牌与云端 JWT 返回 null。
 */
public final class CurrentClient {

    private CurrentClient() {
    }

    public static ClientPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof ClientPrincipal client ? client : null;
    }

    public static String clientIdOrNull() {
        ClientPrincipal client = get();
        return client == null ? null : client.clientId();
    }
}
