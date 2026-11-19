package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.PublicApi;
import com.capsule.agentkey.config.PublicApiCatalog;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.service.ChangeSetService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.util.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P5-14：任务级 ChangeSet 与交付 API。
 *
 * <p>以 Session 为任务边界，聚合多个 Run 的变更、审批、诊断与验证；
 * 支持按 Run 过滤、按 Run 撤回与整体撤回。所有入口校验会话归属。
 */
@RestController
@RequestMapping("/api/file-changes")
@RequiresCapability(Capability.APPROVAL)
public class ChangeSetController {

    private final ChangeSetService changeSetService;
    private final ChatService chatService;

    public ChangeSetController(ChangeSetService changeSetService, ChatService chatService) {
        this.changeSetService = changeSetService;
        this.chatService = chatService;
    }

    /** 任务交付视图；runId 可选，用于按单个 Run 过滤 */
    @GetMapping("/changeset")
    @PublicApi(scope = "changes:read", group = PublicApiCatalog.GROUP_TASK)
    public ChangeSetService.ChangeSetView changeSet(@RequestParam Long sessionId,
                                                    @RequestParam(required = false) Long runId) {
        Long userId = CurrentUser.requireId();
        chatService.getOwnedSession(sessionId, userId);
        return changeSetService.view(userId, sessionId, runId);
    }

    /** 整体撤回：按执行逆序回滚本任务所有 PENDING 变更 */
    @PostMapping("/revert-session")
    @PublicApi(scope = "changes:write", group = PublicApiCatalog.GROUP_TASK)
    public ChangeSetService.RevertResult revertSession(@RequestParam Long sessionId) {
        chatService.getOwnedSession(sessionId, CurrentUser.requireId());
        return changeSetService.revertSession(sessionId);
    }

    /** 按 Run 撤回：只回滚该 Run 对应的 PENDING 变更 */
    @PostMapping("/revert-run")
    @PublicApi(scope = "changes:write", group = PublicApiCatalog.GROUP_TASK)
    public ChangeSetService.RevertResult revertRun(@RequestParam Long sessionId,
                                                   @RequestParam Long runId) {
        Long userId = CurrentUser.requireId();
        chatService.getOwnedSession(sessionId, userId);
        return changeSetService.revertRun(sessionId, runId, userId);
    }

}
