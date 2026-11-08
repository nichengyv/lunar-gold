package com.capsule.agentkey.filter;

import com.capsule.agentkey.service.desktop.LocalTokenService;
import com.capsule.agentkey.service.desktop.LocalUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalTokenFilterTest {

    @TempDir
    Path tempDir;

    private LocalTokenService tokenService;
    private LocalTokenFilter filter;

    @BeforeEach
    void setUp() {
        tokenService = new LocalTokenService(tempDir.toString(), "", false);
        LocalUserService localUserService = mock(LocalUserService.class);
        when(localUserService.localUserId()).thenReturn(7L);
        filter = new LocalTokenFilter(tokenService, localUserService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 有效令牌写入认证上下文() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader("Authorization", "Bearer " + tokenService.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals(7L, auth.getPrincipal());
        assertEquals("local-token", auth.getDetails());
        assertEquals(200, response.getStatus());
    }

    @Test
    void 无效令牌返回401且不写入上下文() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader("Authorization", "Bearer wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void 未携带令牌时放行由安全链决定() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/meta");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void 支持XLocalToken请求头() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader("X-Local-Token", tokenService.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus());
    }
}
