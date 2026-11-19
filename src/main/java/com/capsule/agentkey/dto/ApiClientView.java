package com.capsule.agentkey.dto;

import com.capsule.agentkey.entity.ApiClient;

import java.time.LocalDateTime;
import java.util.List;

/**
 * P6-01：客户端自我描述视图。永不包含 secret / secret_hash。
 */
public record ApiClientView(
        String clientId,
        String name,
        List<String> scopes,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt,
        boolean revoked) {

    public static ApiClientView from(ApiClient client) {
        List<String> scopes = client.getScopes() == null || client.getScopes().isBlank()
                ? List.of()
                : List.of(client.getScopes().split(","));
        return new ApiClientView(
                client.getClientId(),
                client.getName(),
                scopes,
                client.getCreatedAt(),
                client.getLastUsedAt(),
                client.getRevokedAt() != null);
    }
}
