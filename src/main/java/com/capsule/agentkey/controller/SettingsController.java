package com.capsule.agentkey.controller;

import com.capsule.agentkey.entity.UserApiKey;
import com.capsule.agentkey.entity.UserSetting;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.UserSettingRepository;
import com.capsule.agentkey.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * 设置中心接口（前端 SettingsView.vue 契约后端实现）。
 *
 * 4 组接口：
 *   GET/PUT /api/settings/defaults — 默认对话参数（默认模型/推理档位/权限）
 *   GET /api/models — 可切换的模型列表（available 取决于该 provider 是否有 API Key）
 *   GET /api/health — 健康检查 + 版本号 + 运行时长
 *   GET /api/mcp/status + PUT /api/mcp/enabled — MCP 全局开关（现有 McpController 扩展）
 *
 * 设计决策：默认值用 upsert（query → 没有就新建），保证第一次访问就有数据。
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    private final UserSettingRepository settingRepo;
    private final ApiKeyService apiKeyService;

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) return (Long) auth.getPrincipal();
        throw new BusinessException("用户未认证");
    }

    // ========== 默认对话参数 ==========

    @GetMapping("/defaults")
    public ResponseEntity<Map<String, String>> getDefaults() {
        Long userId = getCurrentUserId();
        UserSetting s = settingRepo.findByUserId(userId).orElseGet(UserSetting::new);
        Map<String, String> resp = new LinkedHashMap<>();
        resp.put("model", s.getDefaultModel());
        resp.put("reasoning", s.getDefaultReasoning());
        resp.put("permission", s.getDefaultPermission());
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/defaults")
    @Transactional
    public ResponseEntity<Map<String, String>> updateDefaults(@RequestBody Map<String, String> body) {
        Long userId = getCurrentUserId();
        UserSetting s = settingRepo.findByUserId(userId).orElseGet(() -> {
            UserSetting created = new UserSetting();
            created.setUserId(userId);
            return created;
        });
        if (body.containsKey("model") && !body.get("model").isBlank()) {
            s.setDefaultModel(body.get("model"));
        }
        if (body.containsKey("reasoning") && !body.get("reasoning").isBlank()) {
            s.setDefaultReasoning(body.get("reasoning"));
        }
        if (body.containsKey("permission") && !body.get("permission").isBlank()) {
            s.setDefaultPermission(body.get("permission"));
        }
        settingRepo.save(s);
        log.info("对话默认值已更新 [userId={}]", userId);
        // 读回完整值返回（body 可能只传了部分字段）
        Map<String, String> resp = new LinkedHashMap<>();
        resp.put("model", s.getDefaultModel());
        resp.put("reasoning", s.getDefaultReasoning());
        resp.put("permission", s.getDefaultPermission());
        return ResponseEntity.ok(resp);
    }

    // ========== 模型列表 ==========

    /**
     * 模型列表：硬编码 AgentKey 支持的全部模型。
     * available 取决于当前用户是否为该 provider 配置了 API Key。
     * 未来可改成从 application.yml 动态解析——现在模型数量固定，硬编码足够。
     */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> listModels() {
        Long userId = getCurrentUserId();
        List<UserApiKey> myKeys = apiKeyService.listByUserId(userId);
        Set<String> configuredProviders = new HashSet<>();
        for (UserApiKey k : myKeys) {
            if (k.getEncryptedKey() != null && !k.getEncryptedKey().isBlank()) {
                configuredProviders.add(k.getProvider().toUpperCase());
            }
        }

        List<Map<String, Object>> models = new ArrayList<>();
        // DeepSeek —— AgentKey 主要 provider
        addModel(models, "deepseek-chat", "DeepSeek Chat", "DEEPSEEK", configuredProviders);
        addModel(models, "deepseek-reasoner", "DeepSeek Reasoner", "DEEPSEEK", configuredProviders);
        // OpenAI 兼容通用 —— 给用户留扩展空间
        addModel(models, "gpt-4o-mini", "GPT-4o Mini", "OPENAI", configuredProviders);
        addModel(models, "gpt-4o", "GPT-4o", "OPENAI", configuredProviders);
        // Ollama 本地 —— available 不看 API Key（本地起了就有）
        addModel(models, "qwen2.5:7b", "Qwen2.5 7B (Ollama)", "OLLAMA", configuredProviders);

        return ResponseEntity.ok(models);
    }

    private void addModel(List<Map<String, Object>> list, String id, String name,
                          String provider, Set<String> configuredProviders) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("provider", provider);
        // Ollama 是本地，不需要 API Key 也算 available
        m.put("available", "OLLAMA".equals(provider) || configuredProviders.contains(provider));
        list.add(m);
    }
}
