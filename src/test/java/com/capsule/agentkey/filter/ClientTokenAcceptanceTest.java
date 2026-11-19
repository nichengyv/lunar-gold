package com.capsule.agentkey.filter;

import com.capsule.agentkey.config.PublicApiCatalog;
import com.capsule.agentkey.security.ClientPrincipal;
import com.capsule.agentkey.service.ApiClientService;
import com.capsule.agentkey.service.desktop.LocalTokenService;
import com.capsule.agentkey.service.desktop.LocalUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6-01：客户端令牌鉴权矩阵（401 / 403 / 回环边界 / Web 零回归）。
 *
 * 直接驱动过滤器，不启动 Spring；每条用例只关心「拦截还是放行」与响应信封。
 */
class ClientTokenAcceptanceTest {

    private static final String CLIENT_ID = "abcdefghijkl";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 认证

    @Test
    void 有效客户端令牌写入认证且details为ClientPrincipal() throws Exception {
        ApiClientService service = mock(ApiClientService.class);
        ClientPrincipal principal = new ClientPrincipal(1L, CLIENT_ID, "cli", Set.of("run:read"), null);
        when(service.authenticate("lgc_" + CLIENT_ID + "_secret")).thenReturn(Optional.of(principal));

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ClientTokenFilter(service).doFilter(
                request("GET", "/api/projects", "Bearer lgc_" + CLIENT_ID + "_secret"), response, chain);

        assertNotNull(chain.getRequest(), "认证成功后必须继续过滤链");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals(1L, auth.getPrincipal());
        assertEquals(CLIENT_ID, assertInstanceOf(ClientPrincipal.class, auth.getDetails()).clientId());
    }

    @Test
    void 无效客户端令牌返回401且不再继续过滤链() throws Exception {
        ApiClientService service = mock(ApiClientService.class);
        when(service.authenticate("lgc_" + CLIENT_ID + "_bad")).thenReturn(Optional.empty());

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ClientTokenFilter(service).doFilter(
                request("GET", "/api/projects", "Bearer lgc_" + CLIENT_ID + "_bad"), response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":401"));
    }

    @Test
    void 非客户端令牌原样放行给后面的过滤器() throws Exception {
        ApiClientService service = mock(ApiClientService.class);
        MockFilterChain chain = new MockFilterChain();

        new ClientTokenFilter(service).doFilter(
                request("GET", "/api/projects", "Bearer local-token-value"),
                new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        verify(service, never()).authenticate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 本地令牌过滤器在已有认证时短路() throws Exception {
        LocalTokenService localTokenService = mock(LocalTokenService.class);
        LocalUserService localUserService = mock(LocalUserService.class);
        SecurityContextHolder.getContext().setAuthentication(clientAuth(Set.of("run:read")));

        MockFilterChain chain = new MockFilterChain();
        new LocalTokenFilter(localTokenService, localUserService).doFilter(
                request("GET", "/api/projects", "Bearer lgc_" + CLIENT_ID + "_secret"),
                new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "已有客户端认证时本地令牌过滤器必须放行");
        verify(localTokenService, never()).matches(org.mockito.ArgumentMatchers.anyString());
    }

    // ---------------------------------------------------------------- scope

    @Test
    void Web请求不经过scope校验() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        var auth = new UsernamePasswordAuthenticationToken(1L, null, new ArrayList<>());
        auth.setDetails("local-token");
        SecurityContextHolder.getContext().setAuthentication(auth);

        new ScopeAuthorizationFilter(catalog()).doFilter(
                request("GET", "/api/keys", null), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "Web 本地令牌访问任意内部接口必须保持原样");
    }

    @Test
    void 清单外接口对客户端返回403() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(clientAuth(Set.of("project:read")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ScopeAuthorizationFilter(catalog()).doFilter(
                request("GET", "/api/keys", null), response, chain);

        assertNull(chain.getRequest());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("未对客户端开放"));
    }

    @Test
    void 缺少scope时返回403且指出缺失项() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(clientAuth(Set.of("run:read")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ScopeAuthorizationFilter(catalog()).doFilter(
                request("POST", "/api/agent/chat/stream", null), response, chain);

        assertNull(chain.getRequest());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("chat:write"));
    }

    @Test
    void scope满足时放行() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(clientAuth(Set.of("project:read")));
        MockFilterChain chain = new MockFilterChain();

        new ScopeAuthorizationFilter(catalog()).doFilter(
                request("GET", "/api/projects", null), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }

    // ---------------------------------------------------------------- 网络边界

    @Test
    void 非回环来源被拒并记warn() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(clientAuth(Set.of("project:read")));
        MockHttpServletRequest request = request("GET", "/api/projects", null);
        request.setRemoteAddr("10.0.0.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new LoopbackGuardFilter().doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(403, response.getStatus());
    }

    @Test
    void 伪造Host被拒() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(clientAuth(Set.of("project:read")));
        MockHttpServletRequest request = request("GET", "/api/projects", null);
        request.addHeader("Host", "evil.example.com");

        MockFilterChain chain = new MockFilterChain();
        new LoopbackGuardFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest());
    }

    @Test
    void 外部Origin被拒() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(clientAuth(Set.of("project:read")));
        MockHttpServletRequest request = request("GET", "/api/projects", null);
        request.addHeader("Host", "127.0.0.1:8080");
        request.addHeader("Origin", "https://evil.example.com");

        MockFilterChain chain = new MockFilterChain();
        new LoopbackGuardFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest());
    }

    @Test
    void 合法回环请求放行() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(clientAuth(Set.of("project:read")));
        MockHttpServletRequest request = request("GET", "/api/projects", null);
        request.addHeader("Host", "localhost:8080");
        request.addHeader("Origin", "http://localhost:5173");
        MockFilterChain chain = new MockFilterChain();

        new LoopbackGuardFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    void Web请求不受回环校验影响() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("GET", "/api/settings/defaults", null);
        request.setRemoteAddr("10.0.0.8");

        new LoopbackGuardFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }

    // ---------------------------------------------------------------- 工具

    private PublicApiCatalog catalog() {
        PublicApiCatalog catalog = new PublicApiCatalog(null);
        catalog.apply(PublicApiCatalog.declaredEndpoints());
        return catalog;
    }

    private MockHttpServletRequest request(String method, String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr("127.0.0.1");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private UsernamePasswordAuthenticationToken clientAuth(Set<String> scopes) {
        ClientPrincipal principal = new ClientPrincipal(1L, CLIENT_ID, "cli", scopes, "127.0.0.1");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(1L, null, new ArrayList<>());
        auth.setDetails(principal);
        return auth;
    }
}
