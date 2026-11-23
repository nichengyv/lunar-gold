package com.capsule.agentkey.controller;

import com.capsule.agentkey.entity.UserApiKey;
import com.capsule.agentkey.entity.UserSetting;
import com.capsule.agentkey.repository.UserSettingRepository;
import com.capsule.agentkey.service.ApiKeyService;
import com.capsule.agentkey.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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

    // ========== 默认对话参数 ==========

    @GetMapping("/defaults")
    public ResponseEntity<Map<String, String>> getDefaults() {
        Long userId = CurrentUser.requireId();
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
        Long userId = CurrentUser.requireId();
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
     * 模型列表：只返回"用户自己配过 API Key 的 provider"下可用的模型。
     *
     * <p>规则：
     * <ul>
     *   <li>有 Key 的 provider → 优先尝试远端拉 /models 实时列表；拉不到时只返回空（不硬编码兜底）</li>
     *   <li>Ollama 本地 → 不需要 Key 也算 available（但也不硬编码具体模型名，由用户自己配）</li>
     *   <li>没有任何 Key → 返回空数组，前端据此提示"请先添加 API Key"</li>
     * </ul>
     */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> listModels() {
        Long userId = CurrentUser.requireId();
        List<UserApiKey> myKeys = apiKeyService.listByUserId(userId);
        Set<String> configuredProviders = new HashSet<>();
        for (UserApiKey k : myKeys) {
            if (k.getEncryptedKey() != null && !k.getEncryptedKey().isBlank()) {
                configuredProviders.add(k.getProvider().toUpperCase());
            }
        }

        List<Map<String, Object>> models = new ArrayList<>();
        for (String provider : configuredProviders) {
            try {
                List<String> liveModels = apiKeyService.listRemoteModels(userId, provider);
                for (String id : liveModels) {
                    addModel(models, id, id, provider, true);
                }
            } catch (Exception e) {
                log.warn("拉取 provider={} 的模型列表失败 [userId={}]: {}", provider, userId, e.getMessage());
            }
        }
        // Ollama 本地是特例：不需要 Key，也不硬编码模型名，只提示前端可配置
        return ResponseEntity.ok(models);
    }

    private void addModel(List<Map<String, Object>> list, String id, String name,
                          String provider, boolean available) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("provider", provider);
        m.put("available", available);
        list.add(m);
    }
}
