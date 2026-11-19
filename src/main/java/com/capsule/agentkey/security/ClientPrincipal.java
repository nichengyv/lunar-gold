package com.capsule.agentkey.security;

import java.util.Set;

/**
 * P6-01：客户端令牌通过认证后挂在 Authentication.details 上的身份记录。
 *
 * 用途有三：
 * 1. ScopeAuthorizationFilter 判断该请求是否被允许；
 * 2. LoopbackGuardFilter 判断是否需要做回环校验；
 * 3. AgentRun.requestJson / 审批事件落审计字段 clientId。
 *
 * Web 工作台（本地令牌）与云端 JWT 的 details 分别是 "local-token" 字符串与 null，
 * 因此凡是判断 instanceof ClientPrincipal 的地方天然不会误伤既有链路。
 */
public record ClientPrincipal(Long userId, String clientId, String name, Set<String> scopes, String clientIp) {

    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }

    public ClientPrincipal withClientIp(String ip) {
        return new ClientPrincipal(userId, clientId, name, scopes, ip);
    }
}
