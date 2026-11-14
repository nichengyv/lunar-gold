package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.entity.PendingFileChange;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.service.ChangeSetService;
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
 */
@RestController
@RequestMapping("/api/file-changes")
@RequiresCapability(Capability.APPROVAL)
public class FileReviewController {

    private final PendingFileChangeRepository pfcRepo;
    private final ChangeSetService changeSetService;

    public FileReviewController(PendingFileChangeRepository pfcRepo,
                                ChangeSetService changeSetService) {
        this.pfcRepo = pfcRepo;
        this.changeSetService = changeSetService;
    }

    /** 拉项目所有 PENDING 变更（前端审查卡片数据源） */
    @GetMapping
    public ResponseEntity<List<PendingFileChange>> listByProject(
            @RequestParam Long projectId,
            @RequestParam(defaultValue = "PENDING") String status) {
        List<PendingFileChange> list = pfcRepo.findByProjectIdAndStatusOrderByCreateTimeDesc(projectId, status);
        return ResponseEntity.ok(list);
    }

    /** 拉项目所有变更（含已处理，前端追踪面板也能用） */
    @GetMapping("/all")
    public ResponseEntity<List<PendingFileChange>> allByProject(@RequestParam Long projectId) {
        return ResponseEntity.ok(pfcRepo.findByProjectIdOrderByCreateTimeDesc(projectId));
    }

    /** 单条撤销：文件回到 oldContent；新文件则删除 */
    @PostMapping("/{id}/revert")
    public ResponseEntity<Map<String, Object>> revert(@PathVariable Long id) {
        Map<String, Object> resp = new HashMap<>();
        PendingFileChange pfc = pfcRepo.findById(id).orElse(null);
        if (pfc == null) {
            resp.put("ok", false);
            resp.put("error", "变更不存在");
            return ResponseEntity.notFound().build();
        }
        if (!"PENDING".equals(pfc.getStatus())) {
            resp.put("ok", false);
            resp.put("error", "该变更已处理");
            return ResponseEntity.badRequest().body(resp);
        }
        ChangeSetService.RevertResult result = changeSetService.revertSingle(id, null);
        if (result.count() > 0) {
            resp.put("ok", true);
        } else {
            resp.put("ok", false);
            resp.put("error", result.conflicts().isEmpty()
                    ? "撤销失败" : result.conflicts().get(0).reason());
        }
        return ResponseEntity.ok(resp);
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
    public ResponseEntity<Map<String, Object>> revertAll(@RequestParam Long projectId) {
        ChangeSetService.RevertResult result = changeSetService.revertProject(projectId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", result.ok());
        resp.put("count", result.count());
        resp.put("failed", result.failed());
        resp.put("skippedAccepted", result.skippedAccepted());
        resp.put("conflicts", result.conflicts());
        return ResponseEntity.ok(resp);
    }

    /**
     * 第六十八批 A3：撤回某一轮的全部改动。
     * sessionId + turnId 精确定位本轮记录；按时间倒序回滚，同一文件改多次也能回到本轮开始前。
     */
    @PostMapping("/revert-turn")
    public ResponseEntity<Map<String, Object>> revertTurn(@RequestParam Long sessionId,
                                                          @RequestParam String turnId) {
        ChangeSetService.RevertResult result = changeSetService.revertTurn(sessionId, turnId, null);
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", result.ok());
        resp.put("count", result.count());
        resp.put("failed", result.failed());
        resp.put("skippedAccepted", result.skippedAccepted());
        resp.put("conflicts", result.conflicts());
        return ResponseEntity.ok(resp);
    }
}
