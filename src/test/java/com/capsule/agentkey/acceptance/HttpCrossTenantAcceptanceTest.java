package com.capsule.agentkey.acceptance;

import com.capsule.agentkey.controller.AgentChatController;
import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRun.RunState;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.GlobalExceptionHandler;
import com.capsule.agentkey.filter.ClientTokenFilter;
import com.capsule.agentkey.filter.JwtAuthenticationFilter;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.capsule.agentkey.security.ClientPrincipal;
import com.capsule.agentkey.service.AgentPolicyResolver;
import com.capsule.agentkey.service.ApiClientService;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.ApiKeyService;
import com.capsule.agentkey.service.ChatAttachmentService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.ContextSummaryService;
import com.capsule.agentkey.service.GenerationRegistry;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.service.SessionMemoryExtractionService;
import com.capsule.agentkey.service.TokenSessionService;
import com.capsule.agentkey.service.ToolApprovalRegistry;
import com.capsule.agentkey.service.VisionService;
import com.capsule.agentkey.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E：Phase 4 出境验收——HTTP 层跨账号越权。
 *
 * <p>用真实 {@link JwtUtil} 签发 token，真实 {@link JwtAuthenticationFilter} 解析身份，
 * 经 Spring MVC DispatcherServlet 调用真实 {@link AgentChatController} 与真实
 * {@link AgentRunService} / {@link ToolApprovalRegistry}，验证他人 run 事件流、stop 与
 * approval 都被拒绝，本人正常。
 *
 * <p>边界：不启用完整 SecurityConfig 过滤器链与 Redis 白名单（TokenSessionService 用 mock），
 * 只验证 JWT 解析 + 控制器归属校验这一段真实链路。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HttpCrossTenantAcceptanceTest {

    @Mock
    private ApiKeyService apiKeyService;
    @Mock
    private ReActAgentService agentService;
    @Mock
    private ChatService chatService;
    @Mock
    private ChatAttachmentService chatAttachmentService;
    @Mock
    private ProjectService projectService;
    @Mock
    private GenerationRegistry generationRegistry;
    @Mock
    private VisionService visionService;
    @Mock
    private ContextSummaryService contextSummaryService;
    @Mock
    private AgentPolicyResolver policyResolver;
    @Mock
    private SessionMemoryExtractionService memoryService;
    @Mock
    private AgentRunRepository runRepo;
    @Mock
    private AgentRunEventRepository eventRepo;
    @Mock
    private MeterRegistry meterRegistry;

    private ToolApprovalRegistry registry;
    private AgentChatController controller;
    private MockMvc mvc;
    private String tokenUser1;
    private String tokenUser2;

    @BeforeEach
    void setUp() throws Exception {
        registry = new ToolApprovalRegistry();
        AgentRunService runService = new AgentRunService(runRepo, eventRepo, new ObjectMapper());
        com.capsule.agentkey.service.PromptCatalog promptCatalog =
                new com.capsule.agentkey.service.PromptCatalog(new org.springframework.mock.env.MockEnvironment());
        controller = new AgentChatController(
                apiKeyService, agentService, chatService, chatAttachmentService, projectService,
                generationRegistry, visionService, contextSummaryService, policyResolver,
                registry, memoryService, runService,
                new com.capsule.agentkey.tool.AskUserQuestionTool(),
                promptCatalog, Runnable::run);

        JwtUtil jwtUtil = new JwtUtil();
        setField(jwtUtil, "secret", "acceptance-secret-key-must-be-at-least-32-bytes-long!!");
        setField(jwtUtil, "expiration", 86_400_000L);
        tokenUser1 = jwtUtil.generateToken(1L, "user-one");
        tokenUser2 = jwtUtil.generateToken(2L, "user-two");

        TokenSessionService sessions = mock(TokenSessionService.class);
        when(sessions.isActive(anyString())).thenReturn(true);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JwtAuthenticationFilter(jwtUtil, sessions))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 他人run事件流被拒绝_本人可读() throws Exception {
        AgentRun run = new AgentRun();
        run.setId(77L);
        run.setUserId(1L);
        run.setState(RunState.RUNNING);
        when(runRepo.findById(77L)).thenReturn(Optional.of(run));
        when(eventRepo.findByRunIdOrderBySeqAsc(77L)).thenReturn(List.of());

        mvc.perform(get("/api/agent/runs/77/events")
                        .header("Authorization", "Bearer " + tokenUser2))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("不属于当前用户")));

        mvc.perform(get("/api/agent/runs/77/events")
                        .header("Authorization", "Bearer " + tokenUser1))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"runId\":77")));
    }

    @Test
    void 他人审批不能被代为决策_本人可决策() throws Exception {
        ToolApprovalRegistry.Pending pending =
                registry.create(1L, 10L, "write_file", "{}", "WRITE");

        String body = "{\"decision\":\"APPROVE\",\"remember\":\"NONE\"}";

        mvc.perform(post("/api/agent/approvals/" + pending.approvalId())
                        .header("Authorization", "Bearer " + tokenUser2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("不属于当前用户")));
        assertFalse(pending.future().isDone(), "他人尝试不应唤醒审批等待");

        mvc.perform(post("/api/agent/approvals/" + pending.approvalId())
                        .header("Authorization", "Bearer " + tokenUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        assertTrue(pending.future().isDone(), "本人决策应唤醒审批等待");
    }

    @Test
    void 审批来源按入口记录为USER或CLIENT() throws Exception {
        String body = "{\"decision\":\"APPROVE\",\"remember\":\"NONE\"}";

        ToolApprovalRegistry.Pending web = registry.create(1L, 10L, "write_file", "{}", "WRITE");
        mvc.perform(post("/api/agent/approvals/" + web.approvalId())
                        .header("Authorization", "Bearer " + tokenUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        assertTrue(web.future().isDone());
        assertEquals(ToolApprovalRegistry.DecisionSource.USER, web.future().getNow(null).source(),
                "Web 本地令牌审批记 USER");

        ApiClientService clientService = mock(ApiClientService.class);
        ClientPrincipal principal =
                new ClientPrincipal(1L, "abcdefghijkl", "cli", Set.of("approval:write"), "127.0.0.1");
        when(clientService.authenticate("lgc_abcdefghijkl_secret")).thenReturn(Optional.of(principal));
        MockMvc clientMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ClientTokenFilter(clientService))
                .build();

        ToolApprovalRegistry.Pending client = registry.create(1L, 10L, "write_file", "{}", "WRITE");
        // 上一个 MockMvc 请求把认证留在了线程上下文，先清空才能让 ClientTokenFilter 真正生效
        SecurityContextHolder.clearContext();
        clientMvc.perform(post("/api/agent/approvals/" + client.approvalId())
                        .header("Authorization", "Bearer lgc_abcdefghijkl_secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        assertTrue(client.future().isDone());
        assertEquals(ToolApprovalRegistry.DecisionSource.CLIENT, client.future().getNow(null).source(),
                "客户端令牌审批记 CLIENT");
    }

    @Test
    void 他人会话不能stop_本人可stop() throws Exception {
        when(chatService.getOwnedSession(10L, 2L))
                .thenThrow(new BusinessException("无权操作该会话"));

        mvc.perform(post("/api/agent/chat/stop")
                        .param("sessionId", "10")
                        .header("Authorization", "Bearer " + tokenUser2))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("无权操作该会话")));

        mvc.perform(post("/api/agent/chat/stop")
                        .param("sessionId", "10")
                        .header("Authorization", "Bearer " + tokenUser1))
                .andExpect(status().isOk());
    }

    @Test
    void 无token与伪造token都被拒绝() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/agent/runs/77/events"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/agent/runs/77/events")
                        .header("Authorization", "Bearer not-a-valid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
