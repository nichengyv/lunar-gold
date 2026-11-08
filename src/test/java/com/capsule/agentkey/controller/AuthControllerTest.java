package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.DeploymentMode;
import com.capsule.agentkey.entity.User;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.exception.GlobalExceptionHandler;
import com.capsule.agentkey.service.CapabilityService;
import com.capsule.agentkey.service.EmailCodeService;
import com.capsule.agentkey.service.LoginAttemptService;
import com.capsule.agentkey.service.TokenSessionService;
import com.capsule.agentkey.service.UserService;
import com.capsule.agentkey.util.JwtUtil;
import com.capsule.agentkey.util.RsaUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账号安全闭环的接口级测试：只验证控制器编排（验证码校验、防枚举、改密后撤销会话），
 * 不启动 Spring 容器，也不连接数据库/Redis。
 */
class AuthControllerTest {

    private UserService userService;
    private JwtUtil jwtUtil;
    private RsaUtil rsaUtil;
    private LoginAttemptService loginAttemptService;
    private TokenSessionService tokenSessionService;
    private EmailCodeService emailCodeService;
    private CapabilityService capabilityService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        jwtUtil = mock(JwtUtil.class);
        rsaUtil = mock(RsaUtil.class);
        loginAttemptService = mock(LoginAttemptService.class);
        tokenSessionService = mock(TokenSessionService.class);
        emailCodeService = mock(EmailCodeService.class);
        capabilityService = mock(CapabilityService.class);
        when(capabilityService.mode()).thenReturn(DeploymentMode.CLOUD);

        AuthController controller = new AuthController(userService, jwtUtil, rsaUtil,
                loginAttemptService, tokenSessionService, emailCodeService, capabilityService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 注册缺少邮箱验证码时返回400且不建号() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"encryptedPassword\":\"cipher\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(emailCodeService);
        verifyNoInteractions(userService);
    }

    @Test
    void 注册验证码错误时拒绝建号() throws Exception {
        doThrow(new BusinessException(ErrorCode.PARAM_INVALID, "验证码不正确，请重新输入"))
                .when(emailCodeService).verify(eq("alice@example.com"),
                        eq(EmailCodeService.Scene.REGISTER), eq("123456"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"encryptedPassword\":\"cipher\","
                                + "\"email\":\"alice@example.com\",\"emailCode\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("验证码不正确，请重新输入"));

        verifyNoInteractions(userService);
    }

    @Test
    void 注册成功时邮箱统一转小写入库() throws Exception {
        when(rsaUtil.decryptPassword("cipher")).thenReturn("Passw0rd!");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"encryptedPassword\":\"cipher\","
                                + "\"email\":\"Alice@Example.COM\",\"emailCode\":\"123456\"}"))
                .andExpect(status().isOk());

        verify(emailCodeService).verify("alice@example.com", EmailCodeService.Scene.REGISTER, "123456");
        verify(userService).register("alice", "Passw0rd!", "alice@example.com");
    }

    @Test
    void 重置密码场景下未注册邮箱也返回成功() throws Exception {
        when(userService.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"emailCode\":\"123456\","
                                + "\"encryptedPassword\":\"cipher\"}"))
                .andExpect(status().isOk());

        verifyNoInteractions(emailCodeService);
        verify(userService, never()).resetPassword(anyLong(), anyString());
    }

    @Test
    void 找回密码成功后会撤销该用户全部会话() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        when(userService.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(rsaUtil.decryptPassword("cipher")).thenReturn("NewPassw0rd!");

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"emailCode\":\"654321\","
                                + "\"encryptedPassword\":\"cipher\"}"))
                .andExpect(status().isOk());

        verify(emailCodeService).verify("alice@example.com", EmailCodeService.Scene.RESET, "654321");
        verify(userService).resetPassword(7L, "NewPassw0rd!");
        verify(tokenSessionService).revokeAll(7L);
    }

    @Test
    void 找回密码发码时未注册邮箱不投递但不泄露() throws Exception {
        when(userService.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"scene\":\"RESET\"}"))
                .andExpect(status().isOk());

        verify(emailCodeService).sendCode("nobody@example.com", EmailCodeService.Scene.RESET, false);
        verify(emailCodeService, never()).sendCode(anyString(), any(), eq(true));
    }

    @Test
    void 桌面模式关闭注册与邮箱账号入口() throws Exception {
        when(capabilityService.mode()).thenReturn(DeploymentMode.DESKTOP);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"encryptedPassword\":\"cipher\","
                                + "\"email\":\"alice@example.com\",\"emailCode\":\"123456\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("桌面端为单用户模式，不提供注册与邮箱账号功能"));

        mockMvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"scene\":\"RESET\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"emailCode\":\"123456\","
                                + "\"encryptedPassword\":\"cipher\"}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(userService, emailCodeService);
    }
}
