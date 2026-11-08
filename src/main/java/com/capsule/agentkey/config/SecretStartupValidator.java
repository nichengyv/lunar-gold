package com.capsule.agentkey.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 启动时拒绝缺失或强度不足的认证/加密密钥，避免带着弱配置对外提供服务。 */
@Component
public class SecretStartupValidator {

    private final String jwtSecret;
    private final String aesMasterKey;
    private final Environment environment;

    public SecretStartupValidator(@Value("${app.jwt.secret:}") String jwtSecret,
                                  @Value("${app.crypto.master-key:}") String aesMasterKey,
                                  Environment environment) {
        this.jwtSecret = jwtSecret;
        this.aesMasterKey = aesMasterKey;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        // 桌面端用本地令牌替代 JWT（LocalTokenFilter），JwtUtil 不参与认证；
        // 仍要求 AES 主密钥，因为用户保存的模型 API Key 需要落盘加密。
        if (!environment.acceptsProfiles(Profiles.of("desktop"))) {
            if (!StringUtils.hasText(jwtSecret)) {
                throw new IllegalStateException(
                        "JWT_SECRET 未配置。请生成至少 32 字节的随机密钥，例如：openssl rand -base64 48");
            }
            if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("JWT_SECRET 强度不足：UTF-8 编码后至少需要 32 字节");
            }
        }

        if (!StringUtils.hasText(aesMasterKey)) {
            throw new IllegalStateException(
                    "AES_MASTER_KEY 未配置。请生成 Base64 编码的 32 字节随机密钥，例如：openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(aesMasterKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AES_MASTER_KEY 必须是合法的 Base64 字符串", e);
        }
        if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
            throw new IllegalStateException("AES_MASTER_KEY 解码后必须是 16、24 或 32 字节");
        }
    }
}
