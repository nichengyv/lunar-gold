package com.capsule.agentkey.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6-01：健康接口的兼容性字段。
 */
class HealthControllerTest {

    @Test
    void 健康响应包含服务版本与契约版本() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HealthController()).build();

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("1.0.0-SNAPSHOT"))
                .andExpect(jsonPath("$.serverVersion").value("1.0.0-SNAPSHOT"))
                .andExpect(jsonPath("$.apiContractVersion").value(HealthController.API_CONTRACT_VERSION))
                .andExpect(jsonPath("$.uptimeMs").exists())
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
