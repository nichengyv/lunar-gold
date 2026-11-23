package com.capsule.agentkey.controller;

import com.capsule.agentkey.entity.UserApiKey;
import com.capsule.agentkey.repository.UserSettingRepository;
import com.capsule.agentkey.service.ApiKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型目录：只按用户已存 Key 的 provider 动态列出可用模型。
 * 有 Key → 尝试远端 /models，拉不到返回空（不硬编码兜底）；
 * 完全没 Key → 返回空数组，前端提示用户先添加 API Key。
 */
class SettingsControllerModelsTest {

    private static final long USER_ID = 1L;

    private ApiKeyService apiKeyService;
    private SettingsController controller;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        controller = new SettingsController(mock(UserSettingRepository.class), apiKeyService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UserApiKey deepSeekKey() {
        UserApiKey key = new UserApiKey();
        key.setUserId(USER_ID);
        key.setProvider("DEEPSEEK");
        key.setEncryptedKey("cipher-text");
        return key;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> body() {
        return (List<Map<String, Object>>) controller.listModels().getBody();
    }

    @Test
    void 有Key时用账号实际可用模型() throws Exception {
        when(apiKeyService.listByUserId(USER_ID)).thenReturn(List.of(deepSeekKey()));
        when(apiKeyService.listRemoteModels(USER_ID, "DEEPSEEK"))
                .thenReturn(List.of("deepseek-flash", "deepseek-v4-pro"));

        List<Map<String, Object>> models = body();
        List<String> deepSeekIds = models.stream()
                .filter(m -> "DEEPSEEK".equals(m.get("provider")))
                .map(m -> (String) m.get("id")).toList();

        assertEquals(List.of("deepseek-flash", "deepseek-v4-pro"), deepSeekIds,
                "账号没有 deepseek-chat 时不能再把它列为可选模型");
        assertTrue(models.stream().filter(m -> "DEEPSEEK".equals(m.get("provider")))
                .allMatch(m -> Boolean.TRUE.equals(m.get("available"))));
    }

    @Test
    void 实时拉取失败时不硬编码兜底返回空列表() throws Exception {
        when(apiKeyService.listByUserId(USER_ID)).thenReturn(List.of(deepSeekKey()));
        when(apiKeyService.listRemoteModels(USER_ID, "DEEPSEEK")).thenThrow(new RuntimeException("network down"));

        List<Map<String, Object>> models = body();
        assertEquals(List.of(), models,
                "远端拉取失败不能偷偷回退到硬编码模型，应让空列表提示用户");
    }

    @Test
    void 未配置任何Key时返回空列表() throws Exception {
        when(apiKeyService.listByUserId(USER_ID)).thenReturn(List.of());

        List<Map<String, Object>> models = body();

        verify(apiKeyService, never()).listRemoteModels(USER_ID, "DEEPSEEK");
        assertEquals(List.of(), models,
                "完全没配 Key 时不能预设任何模型，应由前端提示先添加 API Key");
    }
}
