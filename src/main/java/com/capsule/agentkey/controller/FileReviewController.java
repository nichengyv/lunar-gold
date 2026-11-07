package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.entity.PendingFileChange;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.repository.ProjectRepository;
import com.capsule.agentkey.service.WorkspaceFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件变更审查流 API
 * - 前端输入框上方浮现 "n 个文件改动待审查" 卡片
 * - 列表：文件路径 + 红绿 diff + 撤销/保留按钮
 * - 撤销：用 oldContent 覆盖写回；新文件（oldContent=null）则删除文件
 */
@RestController
@RequestMapping("/api/file-changes")
@RequiresCapability(Capability.APPROVAL)
@Slf4j
public class FileReviewController {

    private final PendingFileChangeRepository pfcRepo;
    private final WorkspaceFileService workspaceFileService;
    private final ProjectRepository projectRepo;

    public FileReviewController(PendingFileChangeRepository pfcRepo,
                                WorkspaceFileService workspaceFileService,
                                ProjectRepository projectRepo) {
        this.pfcRepo = pfcRepo;
        this.workspaceFileService = workspaceFileService;
        this.projectRepo = projectRepo;
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
        try {
            revertOnDisk(pfc);
            pfc.setStatus("REVERTED");
            pfcRepo.save(pfc);
            resp.put("ok", true);
        } catch (Exception e) {
            log.error("撤销失败: {}", e.getMessage());
            resp.put("ok", false);
            resp.put("error", e.getMessage());
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
        Map<String, Object> resp = new HashMap<>();
        List<PendingFileChange> pending = pfcRepo.findByProjectIdAndStatusOrderByCreateTimeDesc(projectId, "PENDING");
        long count = 0;
        long failed = 0;
        for (PendingFileChange pfc : pending) {
            try {
                revertOnDisk(pfc);
                pfc.setStatus("REVERTED");
                pfcRepo.save(pfc);
                count++;
            } catch (Exception e) {
                failed++;
                log.warn("批量撤销失败 id={}: {}", pfc.getId(), e.getMessage());
            }
        }
        resp.put("ok", true);
        resp.put("count", count);
        resp.put("failed", failed);
        return ResponseEntity.ok(resp);
    }

    /**
     * 第六十八批 A3：撤回某一轮的全部改动。
     * sessionId + turnId 精确定位本轮记录；按时间倒序回滚，同一文件改多次也能回到本轮开始前。
     */
    @PostMapping("/revert-turn")
    public ResponseEntity<Map<String, Object>> revertTurn(@RequestParam Long sessionId,
                                                          @RequestParam String turnId) {
        Map<String, Object> resp = new HashMap<>();
        List<PendingFileChange> pending = pfcRepo
                .findBySessionIdAndTurnIdAndStatusOrderByCreateTimeDesc(sessionId, turnId, "PENDING");
        long count = 0;
        long failed = 0;
        for (PendingFileChange pfc : pending) {
            try {
                revertOnDisk(pfc);
                pfc.setStatus("REVERTED");
                pfcRepo.save(pfc);
                count++;
            } catch (Exception e) {
                failed++;
                log.warn("按轮次撤销失败 id={}: {}", pfc.getId(), e.getMessage());
            }
        }
        resp.put("ok", true);
        resp.put("count", count);
        resp.put("failed", failed);
        return ResponseEntity.ok(resp);
    }

    /** 实际撤销文件操作：newContent → oldContent */
    private void revertOnDisk(PendingFileChange pfc) throws Exception {
        Project ps = projectRepo.findById(pfc.getProjectId()).orElse(null);
        if (ps == null || ps.getWorkspacePath() == null) {
            throw new IllegalStateException("项目或工作区路径不存在");
        }
        Path root = Paths.get(ps.getWorkspacePath());
        Path file = workspaceFileService.resolveInWorkspace(root, pfc.getFilePath());
        if (pfc.getOldContent() == null) {
            // 新文件：直接删
            Files.deleteIfExists(file);
        } else {
            // modify / delete：覆盖回旧内容（删除目录后撤销时要先把父目录建回来）
            Files.createDirectories(file.getParent());
            Files.writeString(file, pfc.getOldContent(), StandardCharsets.UTF_8);
        }
    }
}
