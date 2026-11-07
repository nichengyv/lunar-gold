package com.capsule.agentkey.controller;

import com.capsule.agentkey.aspect.CapabilityGuardAspect;
import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.dto.ApprovalDecisionRequest;
import com.capsule.agentkey.exception.GlobalExceptionHandler;
import com.capsule.agentkey.mcp.McpGlobalSwitch;
import com.capsule.agentkey.mcp.McpHealthMonitor;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.repository.ProjectRepository;
import com.capsule.agentkey.service.CapabilityService;
import com.capsule.agentkey.service.FolderPickerService;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.WorkspaceFileService;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CapabilityGuardControllerTest {

    @Test
    void 云端工作区接口返回404且不调用业务服务() throws Exception {
        ProjectService projectService = mock(ProjectService.class);
        WorkspaceFileService workspaceFileService = mock(WorkspaceFileService.class);
        FolderPickerService folderPickerService = mock(FolderPickerService.class);
        MockMvc mockMvc = mockMvc(new WorkspaceController(
                projectService, workspaceFileService, folderPickerService), "cloud");

        mockMvc.perform(post("/api/workspace/pick-folder"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("当前部署模式未启用该功能"));

        verifyNoInteractions(projectService, workspaceFileService, folderPickerService);
    }

    @Test
    void 桌面端工作区接口正常执行() throws Exception {
        ProjectService projectService = mock(ProjectService.class);
        WorkspaceFileService workspaceFileService = mock(WorkspaceFileService.class);
        FolderPickerService folderPickerService = mock(FolderPickerService.class);
        when(folderPickerService.pickDirectory()).thenReturn("D:\\workspace");
        MockMvc mockMvc = mockMvc(new WorkspaceController(
                projectService, workspaceFileService, folderPickerService), "desktop");

        mockMvc.perform(post("/api/workspace/pick-folder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("D:\\workspace"));

        verifyNoInteractions(projectService, workspaceFileService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 云端MCP读写接口均返回404() throws Exception {
        McpHealthMonitor healthMonitor = mock(McpHealthMonitor.class);
        McpGlobalSwitch globalSwitch = mock(McpGlobalSwitch.class);
        ObjectProvider<List<McpSyncClient>> clientsProvider = mock(ObjectProvider.class);
        MockMvc mockMvc = mockMvc(
                new McpController(healthMonitor, globalSwitch, clientsProvider), "cloud");

        mockMvc.perform(get("/api/mcp/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(put("/api/mcp/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        verifyNoInteractions(healthMonitor, globalSwitch, clientsProvider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 桌面端MCP读写接口正常执行() throws Exception {
        McpHealthMonitor healthMonitor = mock(McpHealthMonitor.class);
        McpGlobalSwitch globalSwitch = new McpGlobalSwitch();
        globalSwitch.setEnabled(true);
        ObjectProvider<List<McpSyncClient>> clientsProvider = mock(ObjectProvider.class);
        when(clientsProvider.getIfAvailable()).thenReturn(List.of(mock(McpSyncClient.class)));
        when(healthMonitor.snapshot()).thenReturn(Map.of());
        MockMvc mockMvc = mockMvc(
                new McpController(healthMonitor, globalSwitch, clientsProvider), "desktop");

        mockMvc.perform(get("/api/mcp/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mockMvc.perform(put("/api/mcp/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertFalse(globalSwitch.isEnabled());
    }

    @Test
    void 云端文件审查接口返回404() throws Exception {
        PendingFileChangeRepository changeRepository = mock(PendingFileChangeRepository.class);
        WorkspaceFileService workspaceFileService = mock(WorkspaceFileService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        MockMvc mockMvc = mockMvc(new FileReviewController(
                changeRepository, workspaceFileService, projectRepository), "cloud");

        mockMvc.perform(get("/api/file-changes").param("projectId", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        verifyNoInteractions(changeRepository, workspaceFileService, projectRepository);
    }

    @Test
    void 桌面端文件审查接口正常执行() throws Exception {
        PendingFileChangeRepository changeRepository = mock(PendingFileChangeRepository.class);
        WorkspaceFileService workspaceFileService = mock(WorkspaceFileService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(changeRepository.findByProjectIdAndStatusOrderByCreateTimeDesc(1L, "PENDING"))
                .thenReturn(List.of());
        MockMvc mockMvc = mockMvc(new FileReviewController(
                changeRepository, workspaceFileService, projectRepository), "desktop");

        mockMvc.perform(get("/api/file-changes").param("projectId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verifyNoInteractions(workspaceFileService, projectRepository);
    }

    @Test
    void 对话审批入口声明审批能力且普通对话不做类级门控() throws Exception {
        RequiresCapability approval = AgentChatController.class
                .getMethod("respondApproval", String.class, ApprovalDecisionRequest.class)
                .getAnnotation(RequiresCapability.class);

        assertNotNull(approval);
        assertEquals(Capability.APPROVAL, approval.value());
        assertFalse(AgentChatController.class.isAnnotationPresent(RequiresCapability.class));
    }

    private MockMvc mockMvc(Object controller, String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        CapabilityService capabilityService = new CapabilityService(environment);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(controller);
        proxyFactory.addAspect(new CapabilityGuardAspect(capabilityService));
        Object proxy = proxyFactory.getProxy();
        return MockMvcBuilders.standaloneSetup(proxy)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
