package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.PublicApi;
import com.capsule.agentkey.config.PublicApiCatalog;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.ChatSessionStats;
import com.capsule.agentkey.dto.MessageSearchHit;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.util.CurrentUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话维度接口：列表、历史消息、全文搜索、删除、统计、重命名、工作权限切换。
 *
 * <p>从 AgentChatController 拆出，URL 与行为保持不变（/api/agent/sessions/**、/api/agent/messages/search）。
 * 所有入口都通过 {@link CurrentUser#requireId()} 取归属，不信任调用方传入的 id。
 */
@RestController
@RequestMapping("/api/agent")
public class ChatSessionController {

    private final ChatService chatService;
    private final ProjectService projectService;
    private final AgentRunService agentRunService;

    public ChatSessionController(ChatService chatService,
                                 ProjectService projectService,
                                 AgentRunService agentRunService) {
        this.chatService = chatService;
        this.projectService = projectService;
        this.agentRunService = agentRunService;
    }

    @GetMapping("/sessions")
    @PublicApi(scope = "project:read", group = PublicApiCatalog.GROUP_CONTEXT)
    public ApiResponse<List<Map<String, Object>>> listSessions(
            @RequestParam(value = "projectId", required = false) Long projectId) {
        Long userId = CurrentUser.requireId();
        List<ChatSession> sessions = chatService.listSessions(
                userId, projectService.resolveProjectId(userId, projectId));
        // P5-12-2：带上每个会话最近一轮的状态，侧栏据此显示状态点
        Map<Long, String> lastStates = agentRunService.lastRunStatesBySession(
                userId, sessions.stream().map(ChatSession::getId).toList());
        List<Map<String, Object>> list = sessions.stream().map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("projectId", s.getProjectId());
            item.put("title", s.getTitle());
            item.put("model", s.getModel());
            item.put("reasoning", s.getReasoning());
            item.put("permission", s.getPermission());
            item.put("createTime", s.getCreateTime());
            item.put("updateTime", s.getUpdateTime());
            item.put("lastRunState", lastStates.get(s.getId()));
            return item;
        }).toList();
        return ApiResponse.success(list);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @PublicApi(scope = "run:read", group = PublicApiCatalog.GROUP_CONTEXT)
    public ApiResponse<List<ChatMessage>> getMessages(@PathVariable Long sessionId) {
        return ApiResponse.success(chatService.getHistory(sessionId, CurrentUser.requireId()));
    }

    /** 消息全文搜索：在当前用户的会话正文里找关键字，按会话归并返回命中片段 */
    @GetMapping("/messages/search")
    public ApiResponse<List<MessageSearchHit>> searchMessages(
            @RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.success(chatService.searchMessages(CurrentUser.requireId(), keyword));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId) {
        chatService.deleteSession(sessionId, CurrentUser.requireId());
        return ApiResponse.success(null);
    }

    /**
     * 第五十四批：前端侧边栏"会话追踪"面板的聚合统计接口。
     * 数据源：ChatMessage.trace（每次 assistant 消息的执行轨迹 JSON）。
     * 不新建表——按需 parse + 聚合。
     */
    @GetMapping("/sessions/{sessionId}/stats")
    public ApiResponse<ChatSessionStats> sessionStats(@PathVariable Long sessionId) {
        Long userId = CurrentUser.requireId();
        ChatSessionStats stats = chatService.aggregateStats(sessionId, userId);
        // P4-07：token 用量来自 agent_run，与 trace 聚合分开merge
        stats.setUsage(agentRunService.usageBySession(sessionId, userId));
        return ApiResponse.success(stats);
    }

    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<Void> renameSession(@PathVariable Long sessionId,
                                           @RequestBody RenameSessionRequest request) {
        chatService.renameSession(sessionId, CurrentUser.requireId(), request == null ? null : request.title());
        return ApiResponse.success(null);
    }

    public record RenameSessionRequest(String title) {
    }

    /** 会话头切换工作权限：立即持久化到会话，避免切走再切回被旧值覆盖 */
    @PatchMapping("/sessions/{sessionId}/permission")
    public ApiResponse<Void> updateSessionPermission(@PathVariable Long sessionId,
                                                     @RequestBody SessionPermissionRequest request) {
        chatService.updateSessionPermission(sessionId, CurrentUser.requireId(),
                request == null ? null : request.permission());
        return ApiResponse.success(null);
    }

    public record SessionPermissionRequest(String permission) {
    }
}
