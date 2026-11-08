package com.capsule.agentkey.filter;

import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.service.desktop.LocalTokenService;
import com.capsule.agentkey.service.desktop.LocalUserService;
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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

/**
 * 桌面端本地令牌认证（P2-03），与 JwtAuthenticationFilter 互斥：
 * desktop profile 只装配本类，云端只装配 JWT 过滤器。
 *
 * 行为约定：
 * 1. 没带令牌 → 直接放行，由 SecurityConfig 对受保护接口统一返回 401；
 * 2. 带了但校验失败 → 立即 401，避免无效凭证继续进入业务层；
 * 3. 校验通过 → 把本地用户 id 写入 SecurityContext，Controller 的 getCurrentUserId() 无需改动。
 */
@Component
@Profile("desktop")
public class LocalTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalTokenFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final LocalTokenService localTokenService;
    private final LocalUserService localUserService;

    public LocalTokenFilter(LocalTokenService localTokenService, LocalUserService localUserService) {
        this.localTokenService = localTokenService;
        this.localUserService = localUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!localTokenService.matches(token)) {
            log.warn("本地令牌无效 [path={}]", request.getRequestURI());
            ResponseWriter.writeError(response, ErrorCode.UNAUTHORIZED, "本地令牌无效，请重启桌面端");
            return;
        }
        // details 固定标记来源，便于将来区分本地会话与云端 JWT 会话
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(localUserService.localUserId(), null, new ArrayList<>());
        auth.setDetails("local-token");
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }

    /** 同时兼容前端 axios 的 Authorization 头和更直观的 X-Local-Token */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        String localToken = request.getHeader("X-Local-Token");
        return StringUtils.hasText(localToken) ? localToken.trim() : null;
    }
}
