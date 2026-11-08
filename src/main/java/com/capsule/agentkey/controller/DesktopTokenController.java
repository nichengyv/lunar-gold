package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.service.desktop.LocalTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 桌面端令牌引导接口：仅为本机浏览器开发模式服务。
 *
 * 正式桌面壳会直接读取 <data-dir>/local-token 并通过请求头注入，
 * 不需要经过这里；因此打包时应把 app.desktop.expose-token-endpoint 设为 false。
 *
 * 防护：
 * 1. 只接受回环地址（127.0.0.1 / ::1），外部网卡访问一律 403；
 * 2. 带 Origin 的浏览器请求必须在 app.cors.allowed-origins 白名单内，
 *    避免任意网页通过跨域简单请求拿到令牌。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop")
@Profile("desktop")
public class DesktopTokenController {

    private final LocalTokenService localTokenService;
    private final boolean exposeTokenEndpoint;
    private final String allowedOrigins;

    public DesktopTokenController(
            LocalTokenService localTokenService,
            @Value("${app.desktop.expose-token-endpoint:true}") boolean exposeTokenEndpoint,
            @Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.localTokenService = localTokenService;
        this.exposeTokenEndpoint = exposeTokenEndpoint;
        this.allowedOrigins = allowedOrigins;
    }

    @GetMapping("/token")
    public ApiResponse<Map<String, String>> token(HttpServletRequest request) {
        if (!exposeTokenEndpoint) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "本地令牌接口已关闭");
        }
        if (!isLoopback(request.getRemoteAddr())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅允许本机访问");
        }
        String origin = request.getHeader("Origin");
        if (StringUtils.hasText(origin) && !allowedOriginSet().contains(origin)) {
            log.warn("拒绝非白名单来源获取本地令牌 [origin={}]", origin);
            throw new BusinessException(ErrorCode.FORBIDDEN, "来源不在白名单内");
        }
        return ApiResponse.success(Map.of("token", localTokenService.token()));
    }

    private boolean isLoopback(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::1".equals(remoteAddr);
    }

    private Set<String> allowedOriginSet() {
        Set<String> origins = new HashSet<>();
        if (StringUtils.hasText(allowedOrigins)) {
            Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .forEach(origins::add);
        }
        return origins;
    }
}
