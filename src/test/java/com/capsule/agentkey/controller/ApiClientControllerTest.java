package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiClientView;
import com.capsule.agentkey.dto.CreateClientRequest;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.GlobalExceptionHandler;
import com.capsule.agentkey.service.ApiClientService;
import com.capsule.agentkey.util.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6-01：客户端签发端点。
 */
class ApiClientControllerTest {

    private final ApiClientService apiClientService = mock(ApiClientService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ApiClientController(apiClientService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 本地用户可创建客户端且只返回一次明文令牌() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(7L, null);
        auth.setDetails("local-token");
        SecurityContextHolder.getContext().setAuthentication(auth);

        ApiClientView view = new ApiClientView(
                "abcdefghijkl", "cli", List.of("run:read"),
                LocalDateTime.now(), null, false);
        when(apiClientService.create(eq(7L), eq("cli"), eq(List.of("run:read"))))
                .thenReturn(new ApiClientService.IssuedClient("lgc_abcdefghijkl_secret", view));

        mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"cli","scopes":["run:read"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("lgc_abcdefghijkl_secret"))
                .andExpect(jsonPath("$.data.client.clientId").value("abcdefghijkl"));
    }

    @Test
    void 缺名称或缺scope时返回400() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken(7L, null);
        auth.setDetails("local-token");
        SecurityContextHolder.getContext().setAuthentication(auth);

        mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"cli","scopes":[]}
                                """))
                .andExpect(status().isBadRequest());

        verify(apiClientService, never()).create(any(), any(), any());
    }
}
