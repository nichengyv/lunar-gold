package com.capsule.agentkey.service.desktop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 桌面端本地会话令牌：替代云端 JWT 的单机身份凭证。
 *
 * 设计要点：
 * 1. 随机 32 字节、Base64URL 编码，只落盘到用户数据目录（<data-dir>/local-token），
 *    POSIX 系统收紧为 600；Windows 无 POSIX 权限位，依赖用户目录默认 ACL（仅本人可读写）。
 * 2. 令牌在首次启动生成后长期复用，删除文件即等于让所有旧凭证失效。
 * 3. 预留 DESKTOP_DEV_TOKEN：浏览器开发模式（Vite 5173）可用固定令牌，
 *    不必每次从日志或文件里复制，且不影响正式运行时生成的随机令牌。
 *
 * 仅在 desktop profile 下装配，云端仍走 JwtUtil + Redis 白名单。
 */
@Slf4j
@Component
@Profile("desktop")
public class LocalTokenService {

    static final String TOKEN_FILE_NAME = "local-token";
    private static final int TOKEN_BYTES = 32;

    private final Path tokenFile;
    private final String devToken;
    private final String token;

    public LocalTokenService(
            @Value("${app.desktop.data-dir:${user.home}/.agentkey/desktop}") String dataDir,
            @Value("${app.desktop.dev-token:}") String devToken,
            @Value("${app.desktop.log-token:true}") boolean logToken) {
        this.tokenFile = Path.of(dataDir).toAbsolutePath().normalize().resolve(TOKEN_FILE_NAME);
        this.devToken = StringUtils.hasText(devToken) ? devToken.trim() : null;
        this.token = loadOrCreate();
        if (logToken) {
            // 桌面端本身只监听本机，日志落在用户自己的控制台；浏览器开发模式需要它来配对
            log.info("桌面端本地令牌已就绪（仅本机使用）：{}", token);
            log.info("令牌文件：{}；桌面壳会直接读取该文件，浏览器开发模式可把它填入 localStorage.token", tokenFile);
        }
    }

    /** 当前生效的本地令牌（用于桌面壳 / 本机调试接口） */
    public String token() {
        return token;
    }

    public Path tokenFile() {
        return tokenFile;
    }

    /**
     * 校验请求携带的令牌。比较使用 MessageDigest.isEqual，避免按字节短路带来的时序差异；
     * 同时接受配置的固定开发令牌。
     */
    public boolean matches(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        byte[] actual = candidate.trim().getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), actual)) {
            return true;
        }
        return devToken != null && MessageDigest.isEqual(devToken.getBytes(StandardCharsets.UTF_8), actual);
    }

    private String loadOrCreate() {
        try {
            Files.createDirectories(tokenFile.getParent());
            if (Files.isRegularFile(tokenFile)) {
                String existing = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            String created = generate();
            Files.writeString(tokenFile, created, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            restrictPermissions(tokenFile);
            log.info("首次启动：已生成桌面端本地令牌文件 {}", tokenFile);
            return created;
        } catch (IOException e) {
            throw new IllegalStateException("桌面端本地令牌初始化失败: " + e.getMessage(), e);
        }
    }

    private String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 不支持 POSIX 权限位：文件位于当前用户主目录，默认 ACL 已限定为本人
        }
    }
}
