package com.capsule.agentkey.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话运行中的工作权限覆盖层。
 *
 * 一轮 ReAct 开始时会把本次请求的权限记到这里；用户在模型作答过程中改权限时，
 * ChatService 立即更新这里，让**当前轮剩余的每次工具调用**都按新档位判断审批，
 * 而不是等下一轮才生效。进程重启后自动失效，数据库里的会话权限仍是准。
 */
@Component
public class SessionPermissionTracker {

    private final Map<Long, AgentPolicy.Permission> permissions = new ConcurrentHashMap<>();

    public void put(Long sessionId, AgentPolicy.Permission permission) {
        if (sessionId != null && permission != null) {
            permissions.put(sessionId, permission);
        }
    }

    public AgentPolicy.Permission get(Long sessionId, AgentPolicy.Permission fallback) {
        if (sessionId == null) {
            return fallback;
        }
        return permissions.getOrDefault(sessionId, fallback);
    }

    public void remove(Long sessionId) {
        if (sessionId != null) {
            permissions.remove(sessionId);
        }
    }
}
