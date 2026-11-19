package com.capsule.agentkey.filter;

import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.security.ClientPrincipal;
import com.capsule.agentkey.service.ApiClientService;
import com.capsule.agentkey.util.ResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;

/**
 * P6-01：本地 API 客户端令牌认证（{@code Authorization: Bearer lgc_...}）。
 *
 * 顺序与分工：
 * <ol>
 *   <li>本类只认 {@code lgc_} 前缀，其他令牌原样放行给 LocalTokenFilter；</li>
 *   <li>本类注册在 LocalTokenFilter 之前，成功后 SecurityContext 已有认证，
 *       LocalTokenFilter 见已有认证直接放行，不再二次校验；</li>
 *   <li>principal 仍是 userId（Controller 通过 CurrentUser 读取），
 *       details 放 ClientPrincipal 供 scope 校验与审计使用。</li>
 * </ol>
 *
 * 仅在 desktop profile 下装配；云端 JWT 链路不受影响。
 */
@Component
@Profile("desktop")
public class ClientTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientTokenFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiClientService apiClientService;

    public ClientTokenFilter(ApiClientService apiClientService) {
        this.apiClientService = apiClientService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 已由其他过滤器完成认证时保持现状，避免覆盖身份
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String token = resolveToken(request);
        if (token == null || !token.startsWith(ApiClientService.TOKEN_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ClientPrincipal> principal = apiClientService.authenticate(token);
        if (principal.isEmpty()) {
            log.warn("客户端令牌无效 [path={}, remoteAddr={}]", request.getRequestURI(), request.getRemoteAddr());
            ResponseWriter.writeError(response, ErrorCode.UNAUTHORIZED, "客户端令牌无效，请重新签发");
            return;
        }

        ClientPrincipal client = principal.get().withClientIp(request.getRemoteAddr());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(client.userId(), null, new ArrayList<>());
        auth.setDetails(client);
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
