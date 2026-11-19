package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.entity.UserApiKey;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.ApiKeyService;
import com.capsule.agentkey.util.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/keys")
@Slf4j
public class ApiKeyController {

    @Autowired
    private ApiKeyService apiKeyService;

    @PostMapping
    public ApiResponse<Void> saveKey(@RequestBody Map<String, String> body) throws Exception {
        String provider = body.get("provider");
        String plainKey = body.get("apiKey");
        String alias = body.getOrDefault("alias", provider);
        if (provider == null || plainKey == null) {
            throw new BusinessException("provider 和 apiKey 不能为空");
        }
        apiKeyService.saveApiKey(CurrentUser.requireId(), provider, plainKey, alias);
        return ApiResponse.success(null);
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listKeys() {
        List<UserApiKey> keys = apiKeyService.listByUserId(CurrentUser.requireId());
        List<Map<String, Object>> result = keys.stream().map(k -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", k.getId());
            map.put("provider", k.getProvider());
            map.put("alias", k.getKeyAlias());
            map.put("createTime", k.getCreateTime());
            return map;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteKey(@PathVariable Long id) {
        apiKeyService.deleteById(id, CurrentUser.requireId());
        return ApiResponse.success(null);
    }

    /**
     * 测试 API Key 是否可用：保存前让用户先验证，避免聊天时才发现 Key 错了。
     * 请求体：{ provider, apiKey }——只用于测试，不入库。
     */
    @PostMapping("/test")
    public ApiResponse<Void> testKey(@RequestBody Map<String, String> body) {
        apiKeyService.testConnection(body.get("provider"), body.get("apiKey"));
        return ApiResponse.success(null);
    }
}
