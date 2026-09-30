package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.entity.UserApiKey;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.ApiKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new BusinessException("用户未认证");
    }

    @PostMapping
    public ApiResponse<Void> saveKey(@RequestBody Map<String, String> body) throws Exception {
        String provider = body.get("provider");
        String plainKey = body.get("apiKey");
        String alias = body.getOrDefault("alias", provider);
        if (provider == null || plainKey == null) {
            throw new BusinessException("provider 和 apiKey 不能为空");
        }
        apiKeyService.saveApiKey(getCurrentUserId(), provider, plainKey, alias);
        return ApiResponse.success(null);
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listKeys() {
        List<UserApiKey> keys = apiKeyService.listByUserId(getCurrentUserId());
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
        apiKeyService.deleteById(id, getCurrentUserId());
        return ApiResponse.success(null);
    }
}
