package com.capsule.agentkey.filter;

import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.security.ClientPrincipal;
import com.capsule.agentkey.security.CurrentClient;
import com.capsule.agentkey.util.ResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * P6-01：客户端请求的网络边界校验。
 *
 * 只对 {@link ClientPrincipal} 请求生效，Web 工作台不带客户端令牌，完全不经过这里。
 *
 * 三道检查：
 * 1. 来源地址必须是回环（127.0.0.0/8、::1）；
 * 2. Host 头必须是 localhost / 127.0.0.1 / [::1]（可带端口），挡住 DNS rebinding；
 * 3. 若带 Origin，必须是本机 http 源或桌面端自定义 scheme。
 *
 * 校验失败一律 403 并记 warn，便于从日志定位是哪个客户端在越界。
 */
@Component
@Profile("desktop")
public class LoopbackGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoopbackGuardFilter.class);

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Set<String> DESKTOP_SCHEMES = Set.of("file://", "app://", "desktop://");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ClientPrincipal client = CurrentClient.get();
        if (client == null) {
            chain.doFilter(request, response);
            return;
        }

        String remoteAddr = request.getRemoteAddr();
        if (!isLoopbackAddress(remoteAddr)) {
            reject(request, response, client, "非回环来源: " + remoteAddr);
            return;
        }

        String host = hostWithoutPort(request.getHeader("Host"));
        if (host == null || !LOOPBACK_HOSTS.contains(host)) {
            reject(request, response, client, "Host 非本机: " + request.getHeader("Host"));
            return;
        }

        String origin = request.getHeader("Origin");
        if (StringUtils.hasText(origin) && !isAllowedOrigin(origin)) {
            reject(request, response, client, "Origin 非本机: " + origin);
            return;
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        ClientPrincipal client, String reason) throws IOException {
        log.warn("客户端请求被回环校验拒绝 [clientId={}, path={}, {}]",
                client.clientId(), request.getRequestURI(), reason);
        ResponseWriter.writeError(response, ErrorCode.FORBIDDEN, "客户端只能从本机回环地址访问");
    }

    private boolean isLoopbackAddress(String address) {
        if (!StringUtils.hasText(address)) {
            return false;
        }
        if ("127.0.0.1".equals(address) || "::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address)) {
            return true;
        }
        // 只对 IP 字面量做解析，避免对任意字符串触发 DNS 查询
        if (!address.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** 去掉端口：localhost:8080 → localhost；[::1]:8080 → [::1] */
    private String hostWithoutPort(String host) {
        if (!StringUtils.hasText(host)) {
            return null;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(0, end + 1) : value;
        }
        int colon = value.indexOf(':');
        return colon > 0 ? value.substring(0, colon) : value;
    }

    private boolean isAllowedOrigin(String origin) {
        String value = origin.trim().toLowerCase(Locale.ROOT);
        if (DESKTOP_SCHEMES.stream().anyMatch(value::startsWith)) {
            return true;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return false;
        }
        String authority = value.substring(value.indexOf("://") + 3);
        int slash = authority.indexOf('/');
        if (slash >= 0) {
            authority = authority.substring(0, slash);
        }
        String host = hostWithoutPort(authority);
        return host != null && LOOPBACK_HOSTS.contains(host);
    }
}
