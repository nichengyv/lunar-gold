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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型目录动态化：有 Key 时以 provider 实际返回的模型为准，失败/无 Key 回退静态目录。
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
    void 有Key时用账号实际可用模型替换硬编码目录() throws Exception {
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
    void 实时拉取失败时回退静态目录() throws Exception {
        when(apiKeyService.listByUserId(USER_ID)).thenReturn(List.of(deepSeekKey()));
        when(apiKeyService.listRemoteModels(USER_ID, "DEEPSEEK")).thenThrow(new RuntimeException("network down"));

        List<Map<String, Object>> models = body();
        List<String> deepSeekIds = models.stream()
                .filter(m -> "DEEPSEEK".equals(m.get("provider")))
                .map(m -> (String) m.get("id")).toList();

        assertEquals(List.of("deepseek-chat", "deepseek-reasoner"), deepSeekIds);
        assertTrue(models.stream().filter(m -> "DEEPSEEK".equals(m.get("provider")))
                .allMatch(m -> Boolean.TRUE.equals(m.get("available"))));
    }

    @Test
    void 未配置Key时不发起远程调用且模型不可用() throws Exception {
        when(apiKeyService.listByUserId(USER_ID)).thenReturn(List.of());

        List<Map<String, Object>> models = body();

        verify(apiKeyService, never()).listRemoteModels(USER_ID, "DEEPSEEK");
        assertFalse(models.isEmpty());
        assertTrue(models.stream().filter(m -> "DEEPSEEK".equals(m.get("provider")))
                .allMatch(m -> Boolean.FALSE.equals(m.get("available"))));
    }
}
