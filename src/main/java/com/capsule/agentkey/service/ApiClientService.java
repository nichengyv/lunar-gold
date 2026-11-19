package com.capsule.agentkey.service;

import com.capsule.agentkey.dto.ApiClientView;
import com.capsule.agentkey.entity.ApiClient;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.ApiClientRepository;
import com.capsule.agentkey.security.ApiScope;
import com.capsule.agentkey.security.ClientPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * P6-01：本地 API 客户端令牌的签发、校验与撤销。
 *
 * 令牌格式：{@code lgc_<client_id>_<secret>}
 * <ul>
 *   <li>client_id：9 字节 SecureRandom 的 Base64URL（固定 12 字符，便于无歧义解析）；</li>
 *   <li>secret：32 字节 SecureRandom 的 Base64URL（43 字符，无填充）；</li>
 *   <li>库内只存 secret 的 SHA-256 十六进制，明文令牌只在创建响应里返回一次。</li>
 * </ul>
 *
 * 校验失败一律返回空 Optional，由过滤器统一输出 401 信封；
 * last_used_at 只在全部校验通过后才更新。
 */
@Slf4j
@Service
public class ApiClientService {

    /** 客户端令牌前缀，与本地令牌 / JWT 区分的第一道判据 */
    public static final String TOKEN_PREFIX = "lgc_";

    /** client_id 固定长度：9 字节 Base64URL 无填充正好 12 字符 */
    private static final int CLIENT_ID_BYTES = 9;
    private static final int CLIENT_ID_CHARS = 12;
    private static final int SECRET_BYTES = 32;

    private final ApiClientRepository repository;
    private final SecureRandom random = new SecureRandom();

    public ApiClientService(ApiClientRepository repository) {
        this.repository = repository;
    }

    /** 签发结果：明文令牌只在这一次出现，后续任何接口都不回显 */
    public record IssuedClient(String token, ApiClientView client) {
    }

    /**
     * 创建客户端并返回一次性明文令牌。
     *
     * @param userId 所属本地用户，与本地令牌解析出的用户一致
     * @param name   客户端名称，如 cli / vscode
     * @param scopes 申请的 scope，必须全部是 {@link ApiScope} 中的已知值
     */
    @Transactional
    public IssuedClient create(Long userId, String name, Collection<String> scopes) {
        String normalizedName = normalizeName(name);
        List<String> normalizedScopes = normalizeScopes(scopes);

        String clientId = randomBase64Url(CLIENT_ID_BYTES);
        String secret = randomBase64Url(SECRET_BYTES);

        ApiClient entity = new ApiClient();
        entity.setClientId(clientId);
        entity.setUserId(userId);
        entity.setName(normalizedName);
        entity.setSecretHash(sha256Hex(secret));
        entity.setScopes(String.join(",", normalizedScopes));
        ApiClient saved = repository.save(entity);

        log.info("已签发客户端令牌 [clientId={}, name={}, scopes={}]", clientId, normalizedName, normalizedScopes);
        return new IssuedClient(TOKEN_PREFIX + clientId + "_" + secret, ApiClientView.from(saved));
    }

    /**
     * 校验明文令牌。任何一步失败都返回空，不泄漏具体原因（避免枚举 client_id）。
     * 校验顺序：格式 → 存在性 → 撤销状态 → 常量时间比较 secret → 更新 last_used_at。
     */
    @Transactional
    public Optional<ClientPrincipal> authenticate(String rawToken) {
        ParsedToken parsed = parse(rawToken);
        if (parsed == null) {
            return Optional.empty();
        }
        ApiClient client = repository.findByClientId(parsed.clientId()).orElse(null);
        if (client == null) {
            log.warn("客户端令牌校验失败：未知 clientId=[{}]", parsed.clientId());
            return Optional.empty();
        }
        if (client.getRevokedAt() != null) {
            log.warn("客户端令牌校验失败：客户端已撤销 [clientId={}]", client.getClientId());
            return Optional.empty();
        }
        if (client.getSecretHash() == null || !constantTimeEquals(client.getSecretHash(), sha256Hex(parsed.secret()))) {
            log.warn("客户端令牌校验失败：secret 不匹配 [clientId={}]", client.getClientId());
            return Optional.empty();
        }

        client.setLastUsedAt(LocalDateTime.now());
        repository.save(client);

        return Optional.of(new ClientPrincipal(
                client.getUserId(),
                client.getClientId(),
                client.getName(),
                splitScopes(client.getScopes()),
                null));
    }

    /** 客户端撤销自己：只允许撤销同 user 的记录，已撤销时保持幂等 */
    @Transactional
    public boolean revokeSelf(Long userId, String clientId) {
        ApiClient client = repository.findByClientId(clientId).orElse(null);
        if (client == null || !client.getUserId().equals(userId)) {
            return false;
        }
        if (client.getRevokedAt() == null) {
            client.setRevokedAt(LocalDateTime.now());
            repository.save(client);
            log.info("客户端已撤销 [clientId={}]", clientId);
        }
        return true;
    }

    public Optional<ApiClientView> view(Long userId, String clientId) {
        return repository.findByClientId(clientId)
                .filter(client -> client.getUserId().equals(userId))
                .map(ApiClientView::from);
    }

    public List<ApiClientView> listByUser(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(ApiClientView::from)
                .toList();
    }

    // ---------- 内部实现 ----------

    private record ParsedToken(String clientId, String secret) {
    }

    /**
     * 解析 lgc_<12 字符 client_id>_<secret>。
     * client_id 长度固定，因此无需处理 Base64URL 也包含 '_' 带来的歧义。
     */
    private ParsedToken parse(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return null;
        }
        String token = rawToken.trim();
        if (!token.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        int start = TOKEN_PREFIX.length();
        int secretStart = start + CLIENT_ID_CHARS + 1;
        if (token.length() <= secretStart || token.charAt(start + CLIENT_ID_CHARS) != '_') {
            return null;
        }
        String clientId = token.substring(start, start + CLIENT_ID_CHARS);
        String secret = token.substring(secretStart);
        return new ParsedToken(clientId, secret);
    }

    private String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException("客户端名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 64) {
            throw new BusinessException("客户端名称不能超过 64 个字符");
        }
        return trimmed;
    }

    private List<String> normalizeScopes(Collection<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new BusinessException("至少需要申请一个 scope");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (!StringUtils.hasText(scope)) {
                continue;
            }
            String value = scope.trim();
            if (ApiScope.fromValue(value).isEmpty()) {
                throw new BusinessException("未知的 scope: " + value);
            }
            unique.add(value);
        }
        if (unique.isEmpty()) {
            throw new BusinessException("至少需要申请一个 scope");
        }
        return new ArrayList<>(unique);
    }

    private Set<String> splitScopes(String scopes) {
        Set<String> result = new LinkedHashSet<>();
        if (StringUtils.hasText(scopes)) {
            for (String scope : scopes.split(",")) {
                if (StringUtils.hasText(scope)) {
                    result.add(scope.trim());
                }
            }
        }
        return result;
    }

    private String randomBase64Url(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private boolean constantTimeEquals(String expectedHex, String actualHex) {
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                actualHex.getBytes(StandardCharsets.UTF_8));
    }
}
