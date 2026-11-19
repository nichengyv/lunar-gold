package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.ApiClient;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.ApiClientRepository;
import com.capsule.agentkey.security.ClientPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P6-01：客户端令牌签发与校验的单元测试，不连数据库。
 */
@ExtendWith(MockitoExtension.class)
class ApiClientServiceTest {

    @Mock
    ApiClientRepository repository;

    private ApiClientService service;

    /** 模拟持久层：保存即写回内存，findByClientId 从内存取 */
    private final AtomicReference<ApiClient> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new ApiClientService(repository);
        lenient().when(repository.save(any(ApiClient.class))).thenAnswer(invocation -> {
            ApiClient saved = invocation.getArgument(0);
            stored.set(saved);
            return saved;
        });
        lenient().when(repository.findByClientId(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get())
                        .filter(client -> client.getClientId().equals(invocation.getArgument(0))));
    }

    @Test
    void 创建后只存哈希且明文可认证一次() {
        ApiClientService.IssuedClient issued = service.create(1L, "cli",
                List.of("chat:write", "run:read"));

        assertTrue(issued.token().startsWith("lgc_"));
        assertNotNull(stored.get());
        // 明文不落库：库内是 64 位十六进制哈希，且与令牌中的 secret 不相等
        String secret = issued.token().substring(issued.token().lastIndexOf('_') + 1);
        assertEquals(64, stored.get().getSecretHash().length());
        assertNotEquals(secret, stored.get().getSecretHash());
        assertFalse(issued.token().contains(stored.get().getSecretHash()));

        Optional<ClientPrincipal> principal = service.authenticate(issued.token());
        assertTrue(principal.isPresent());
        assertEquals(issued.client().clientId(), principal.get().clientId());
        assertEquals("cli", principal.get().name());
        assertTrue(principal.get().hasScope("chat:write"));
        assertFalse(principal.get().hasScope("changes:write"));
    }

    @Test
    void 认证成功时才更新lastUsedAt() {
        ApiClientService.IssuedClient issued = service.create(1L, "vscode", List.of("run:read"));
        assertNull(stored.get().getLastUsedAt());
        clearInvocations(repository);

        assertTrue(service.authenticate(issued.token()).isPresent());
        assertNotNull(stored.get().getLastUsedAt());
        verify(repository, times(1)).save(stored.get());

        // secret 不匹配时不更新 last_used_at，也不触发保存
        LocalDateTime before = stored.get().getLastUsedAt();
        clearInvocations(repository);
        int secretStart = ApiClientService.TOKEN_PREFIX.length() + issued.client().clientId().length() + 1;
        char first = issued.token().charAt(secretStart);
        String tampered = issued.token().substring(0, secretStart)
                + (first == 'A' ? 'B' : 'A')
                + issued.token().substring(secretStart + 1);
        assertTrue(service.authenticate(tampered).isEmpty());
        assertEquals(before, stored.get().getLastUsedAt());
        verify(repository, never()).save(any(ApiClient.class));
    }

    @Test
    void 撤销后同一令牌立即失败() {
        ApiClientService.IssuedClient issued = service.create(1L, "cli", List.of("chat:write"));
        assertTrue(service.authenticate(issued.token()).isPresent());

        assertTrue(service.revokeSelf(1L, issued.client().clientId()));
        assertNotNull(stored.get().getRevokedAt());
        assertTrue(service.authenticate(issued.token()).isEmpty());

        // 幂等：重复撤销不报错，也不改撤销时间
        LocalDateTime revokedAt = stored.get().getRevokedAt();
        assertTrue(service.revokeSelf(1L, issued.client().clientId()));
        assertEquals(revokedAt, stored.get().getRevokedAt());
    }

    @Test
    void 其他用户不能撤销该客户端() {
        ApiClientService.IssuedClient issued = service.create(1L, "cli", List.of("chat:write"));

        assertFalse(service.revokeSelf(2L, issued.client().clientId()));
        assertNull(stored.get().getRevokedAt());
    }

    @Test
    void 格式错误或未知客户端一律认证失败() {
        assertTrue(service.authenticate(null).isEmpty());
        assertTrue(service.authenticate("   ").isEmpty());
        assertTrue(service.authenticate("Bearer abc").isEmpty());
        assertTrue(service.authenticate("lgc_short_x").isEmpty());
        assertTrue(service.authenticate("lgc_000000000000_secret").isEmpty());
    }

    @Test
    void 未知或空scope拒绝签发() {
        assertThrows(BusinessException.class, () -> service.create(1L, "cli", List.of()));
        assertThrows(BusinessException.class, () -> service.create(1L, "cli", List.of("read:all")));
        assertThrows(BusinessException.class, () -> service.create(1L, "  ", List.of("run:read")));
    }

    @Test
    void 重复申请同一scope只保留一份() {
        ApiClientService.IssuedClient issued = service.create(1L, "cli",
                List.of("run:read", "run:read", "chat:write"));

        ArgumentCaptor<ApiClient> captor = ArgumentCaptor.forClass(ApiClient.class);
        verify(repository).save(captor.capture());
        assertEquals("run:read,chat:write", captor.getValue().getScopes());
        assertEquals(List.of("run:read", "chat:write"), issued.client().scopes());
    }
}
