package com.capsule.agentkey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把前端传来的零散字符串归一化成合法策略：
 * 模型必须在配置允许列表内（防止用户传任意模型名推高费用），
 * 档位/权限非法或缺失时回落到默认值，任何情况都不抛异常打断对话。
 */
@Component
@Slf4j
public class AgentPolicyResolver {

    private final String defaultModel;
    private final Set<String> allowedModels;

    public AgentPolicyResolver(@Value("${llm.chat.model:deepseek-chat}") String defaultModel,
                               @Value("${llm.chat.allowed-models:deepseek-chat,deepseek-reasoner}") String allowedModels) {
        String fallback = (defaultModel == null || defaultModel.isBlank()) ? "deepseek-chat" : defaultModel.trim();
        this.defaultModel = fallback;

        Set<String> set = new LinkedHashSet<>();
        if (allowedModels != null) {
            Arrays.stream(allowedModels.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(set::add);
        }
        // 默认模型必须可用，否则用户不选模型时会调到不在列表里的名字
        set.add(fallback);
        this.allowedModels = Set.copyOf(set);
    }

    public String defaultModel() {
        return defaultModel;
    }

    public Set<String> allowedModels() {
        return allowedModels;
    }

    /**
     * @param model      请求指定的模型，可为空
     * @param reasoning  推理档位（FAST/STANDARD/DEEP），可为空
     * @param permission 工作权限（READ_ONLY/WRITE/EXECUTE），可为空
     */
    public AgentPolicy resolve(String model, String reasoning, String permission) {
        String resolvedModel = defaultModel;
        if (model != null && !model.isBlank()) {
            String candidate = model.trim();
            if (allowedModels.contains(candidate)) {
                resolvedModel = candidate;
            } else {
                log.warn("模型 [{}] 不在允许列表 {} 内，回落到默认模型 {}", candidate, allowedModels, defaultModel);
            }
        }
        return new AgentPolicy(resolvedModel,
                AgentPolicy.Reasoning.from(reasoning),
                AgentPolicy.Permission.from(permission));
    }
}
