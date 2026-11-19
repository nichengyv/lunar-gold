package com.capsule.agentkey.filter;

import com.capsule.agentkey.config.PublicApiCatalog;
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
import java.util.Optional;

/**
 * P6-01：客户端令牌的 scope 校验。
 *
 * 只对 details 为 {@link ClientPrincipal} 的请求生效 —— Web 工作台（本地令牌）与云端 JWT
 * 的 details 不是该类型，因此完全不过这段逻辑，这是「Web 零回归」的机制保证。
 *
 * 判断顺序：是否属于公开子集 → 所需 scope 是否被持有。两种失败都用 403，
 * 但消息不同，方便 CLI 直接提示用户该补哪个 scope。
 */
@Component
@Profile("desktop")
public class ScopeAuthorizationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ScopeAuthorizationFilter.class);

    private final PublicApiCatalog publicApiCatalog;

    public ScopeAuthorizationFilter(PublicApiCatalog publicApiCatalog) {
        this.publicApiCatalog = publicApiCatalog;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ClientPrincipal client = CurrentClient.get();
        if (client == null) {
            chain.doFilter(request, response);
            return;
        }

        String path = pathWithinApplication(request);
        Optional<PublicApiCatalog.PublicEndpoint> endpoint =
                publicApiCatalog.find(request.getMethod(), path);
        if (endpoint.isEmpty()) {
            log.warn("客户端访问未开放接口被拒 [clientId={}, {} {}]", client.clientId(), request.getMethod(), path);
            ResponseWriter.writeError(response, ErrorCode.FORBIDDEN,
                    "该接口未对客户端开放：" + request.getMethod() + " " + path);
            return;
        }

        String required = endpoint.get().scope();
        if (StringUtils.hasText(required) && !client.hasScope(required)) {
            log.warn("客户端缺少 scope [clientId={}, {} {}，需要 {}]",
                    client.clientId(), request.getMethod(), path, required);
            ResponseWriter.writeError(response, ErrorCode.FORBIDDEN, "缺少 scope: " + required);
            return;
        }

        chain.doFilter(request, response);
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
