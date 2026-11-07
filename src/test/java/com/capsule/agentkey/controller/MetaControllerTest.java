package com.capsule.agentkey.controller;

import com.capsule.agentkey.service.CapabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetaControllerTest {

    @Test
    void 云端元信息符合公开契约() throws Exception {
        MockMvc mockMvc = mockMvc("cloud", "3.1.0");

        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("CLOUD"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.authMode").value("MULTI_USER"))
                .andExpect(jsonPath("$.capabilities",
                        contains("CHAT", "KNOWLEDGE", "DOWNLOAD")))
                .andExpect(jsonPath("$.download.enabled").value(true))
                .andExpect(jsonPath("$.download.latestVersion").value("3.1.0"));
    }

    @Test
    void 桌面元信息声明本地认证和完整本地能力() throws Exception {
        MockMvc mockMvc = mockMvc("desktop", "3.1.0");

        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DESKTOP"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.authMode").value("LOCAL_SINGLE_USER"))
                .andExpect(jsonPath("$.capabilities", contains(
                        "CHAT", "KNOWLEDGE", "LOCAL_WORKSPACE", "COMMAND_EXEC", "GIT",
                        "MCP_READ", "MCP_WRITE", "LSP", "APPROVAL")))
                .andExpect(jsonPath("$.download.enabled").value(false))
                .andExpect(jsonPath("$.download.latestVersion").doesNotExist());
    }

    private MockMvc mockMvc(String profile, String latestDesktopVersion) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        CapabilityService capabilityService = new CapabilityService(environment);
        return MockMvcBuilders.standaloneSetup(
                        new MetaController(capabilityService, "1.0.0", latestDesktopVersion))
                .build();
    }
}
