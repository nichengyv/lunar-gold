package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.entity.PendingFileChange;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.service.ChangeSetService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.util.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件变更审查流 API
 * - 前端输入框上方浮现 "n 个文件改动待审查" 卡片
 * - 列表：文件路径 + 红绿 diff + 撤销/保留按钮
 * - 撤销：复用 {@link ChangeSetService} 的逆序回滚与外部修改冲突检测
 *
 * <p>与 {@link ChangeSetController} 共用 /api/file-changes 前缀：本类按项目维度（审查卡片），
 * 后者按任务（Session/Run）维度。所有入口都校验项目归属，不信任调用方传入的 id。
 */
@RestController
@RequestMapping("/api/file-changes")
@RequiresCapability(Capability.APPROVAL)
public class FileReviewController {

    private final PendingFileChangeRepository pfcRepo;
    private final ChangeSetService changeSetService;
    private final ProjectService projectService;
    private final ChatService chatService;

    public FileReviewController(PendingFileChangeRepository pfcRepo,
                                ChangeSetService changeSetService,
                                ProjectService projectService,
                                ChatService chatService) {
        this.pfcRepo = pfcRepo;
        this.changeSetService = changeSetService;
        this.projectService = projectService;
        this.chatService = chatService;
    }

    /** 拉项目所有 PENDING 变更（前端审查卡片数据源） */
    @GetMapping
    public ResponseEntity<List<PendingFileChange>> listByProject(
            @RequestParam Long projectId,
            @RequestParam(defaultValue = "PENDING") String status) {
        projectService.getOwned(CurrentUser.requireId(), projectId);
        List<PendingFileChange> list = pfcRepo.findByProjectIdAndStatusOrderByCreateTimeDesc(projectId, status);
        return ResponseEntity.ok(list);
    }

    /** 拉项目所有变更（含已处理，前端追踪面板也能用） */
    @GetMapping("/all")
    public ResponseEntity<List<PendingFileChange>> allByProject(@RequestParam Long projectId) {
        projectService.getOwned(CurrentUser.requireId(), projectId);
        return ResponseEntity.ok(pfcRepo.findByProjectIdOrderByCreateTimeDesc(projectId));
    }

    /** 单条撤销：文件回到 oldContent；新文件则删除 */
    @PostMapping("/{id}/revert")
    public ResponseEntity<ChangeSetService.RevertResult> revert(@PathVariable Long id) {
        Long userId = CurrentUser.requireId();
        PendingFileChange pfc = pfcRepo.findById(id).orElse(null);
        if (pfc == null) {
            return ResponseEntity.notFound().build();
        }
        projectService.getOwned(userId, pfc.getProjectId());
        if (!"PENDING".equals(pfc.getStatus())) {
            return ResponseEntity.badRequest().body(conflictResult(pfc, "该变更已处理"));
        }
        return ResponseEntity.ok(changeSetService.revertSingle(id, userId));
    }

    /** 单条保留：状态锁死 */
    @PostMapping("/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable Long id) {
        Map<String, Object> resp = new HashMap<>();
        PendingFileChange pfc = pfcRepo.findById(id).orElse(null);
        if (pfc == null) {
            resp.put("ok", false);
            resp.put("error", "变更不存在");
            return ResponseEntity.notFound().build();
        }
        projectService.getOwned(CurrentUser.requireId(), pfc.getProjectId());
        if (!"PENDING".equals(pfc.getStatus())) {
            resp.put("ok", false);
            resp.put("error", "该变更已处理");
            return ResponseEntity.badRequest().body(resp);
        }
        pfc.setStatus("ACCEPTED");
        pfcRepo.save(pfc);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    /** 项目批量全部保留 */
    @PostMapping("/accept-all")
    public ResponseEntity<Map<String, Object>> acceptAll(@RequestParam Long projectId) {
        projectService.getOwned(CurrentUser.requireId(), projectId);
        Map<String, Object> resp = new HashMap<>();
        List<PendingFileChange> pending = pfcRepo.findByProjectIdAndStatusOrderByCreateTimeDesc(projectId, "PENDING");
        long count = 0;
        for (PendingFileChange pfc : pending) {
            pfc.setStatus("ACCEPTED");
            pfcRepo.save(pfc);
            count++;
        }
        resp.put("ok", true);
        resp.put("count", count);
        return ResponseEntity.ok(resp);
    }

    /** 项目批量全部撤销：按时间倒序撤销（先撤销最新的） */
    @PostMapping("/revert-all")
    public ResponseEntity<ChangeSetService.RevertResult> revertAll(@RequestParam Long projectId) {
        projectService.getOwned(CurrentUser.requireId(), projectId);
        return ResponseEntity.ok(changeSetService.revertProject(projectId));
    }

    /**
     * 第六十八批 A3：撤回某一轮的全部改动。
     * sessionId + turnId 精确定位本轮记录；按时间倒序回滚，同一文件改多次也能回到本轮开始前。
     */
    @PostMapping("/revert-turn")
    public ResponseEntity<ChangeSetService.RevertResult> revertTurn(@RequestParam Long sessionId,
                                                                    @RequestParam String turnId) {
        Long userId = CurrentUser.requireId();
        chatService.getOwnedSession(sessionId, userId);
        return ResponseEntity.ok(changeSetService.revertTurn(sessionId, turnId, userId));
    }

    /** 统一撤回响应：与 ChangeSetController 返回同一个 RevertResult 结构 */
    private ChangeSetService.RevertResult conflictResult(PendingFileChange pfc, String reason) {
        return new ChangeSetService.RevertResult(false, 0, 0, 0,
                List.of(new ChangeSetService.Conflict(pfc.getId(), pfc.getFilePath(), reason)));
    }

}
