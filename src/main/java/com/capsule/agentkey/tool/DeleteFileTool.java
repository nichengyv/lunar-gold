package com.capsule.agentkey.tool;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.entity.PendingFileChange;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.WorkspaceFileService;
import com.capsule.agentkey.util.SimpleDiff;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 删除工作区内的文件或目录。
 *
 * 与 run_command 的 rm/del 相比，这个工具受工作区沙箱约束、不经过 shell，
 * 并且删除前会把旧内容记入审查流，用户可以在「待审查改动」里一键撤回。
 */
@Component
@Slf4j
public class DeleteFileTool implements BaseTool {

    /** 目录删除时逐文件记录审查流的条数上限，避免一次删 node_modules 撑爆内存 */
    private static final int MAX_RECORDED_FILES = 200;
    /** 单文件记录旧内容的上限；超过则无法撤回该文件，但仍会删除 */
    private static final int MAX_SNAPSHOT_BYTES = 512 * 1024;

    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;
    private final PendingFileChangeRepository pfcRepo;

    public DeleteFileTool(ProjectService projectService, WorkspaceFileService workspaceFileService,
                          PendingFileChangeRepository pfcRepo) {
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
        this.pfcRepo = pfcRepo;
    }

    @Override
    public String getName() { return "delete_file"; }

    @Override
    public Capability requiredCapability() { return Capability.LOCAL_WORKSPACE; }

    @Override
    public String getDescription() {
        return "删除工作区内的文件或目录。删除非空目录必须显式传 recursive=true（会连同目录内所有文件一起删除）。"
                + "不能删除工作区根目录和 .git 版本库目录。所有被删除的文本文件会记入审查流，用户可撤回。"
                + "删除文件请用本工具，不要用 run_command 的 rm / del。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "相对于工作区根的文件或目录路径");
        props.put("path", path);

        Map<String, Object> recursive = new HashMap<>();
        recursive.put("type", "boolean");
        recursive.put("description", "删除目录时必须为 true，表示连同目录内所有内容一起删除；删除文件可不传");
        props.put("recursive", recursive);

        schema.put("properties", props);
        schema.put("required", new String[]{"path"});
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            WorkspaceContext ctx = WorkspaceContext.get();
            if (ctx == null) return ToolResult.fail("工作区上下文丢失");
            Project p = projectService.getOwned(ctx.userId(), ctx.projectId());
            if (p.getWorkspacePath() == null) return ToolResult.fail("项目未绑定工作区");

            String path = arguments.path("path").asText("").trim();
            boolean recursive = arguments.path("recursive").asBoolean(false);
            if (path.isEmpty()) return ToolResult.fail("删除路径不能为空");

            Path root = Paths.get(p.getWorkspacePath()).toAbsolutePath().normalize();
            Path target = workspaceFileService.resolveInWorkspace(root, path);
            String relative = root.relativize(target).toString().replace('\\', '/');
            if (relative.isEmpty()) {
                return ToolResult.fail("不能删除工作区根目录");
            }
            // 软链接按「单个链接」处理，避免顺着链接删到工作区外的目标
            boolean symlink = Files.isSymbolicLink(target);
            boolean directory = !symlink && Files.isDirectory(target);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.fail("路径不存在：" + path);
            }
            if (containsGitDir(root, target)) {
                return ToolResult.fail("不能删除版本库目录 .git（如需重建仓库请让用户手动操作）");
            }
            // 真实路径校验：防止通过软链接父目录逃逸到工作区外
            if (!symlink) {
                Path real = target.toRealPath();
                if (!real.startsWith(root.toRealPath())) {
                    return ToolResult.fail("路径越界，拒绝删除：" + path);
                }
            }

            if (directory) {
                if (!recursive) {
                    boolean empty;
                    try (var list = Files.list(target)) {
                        empty = !list.findAny().isPresent();
                    }
                    if (!empty) {
                        return ToolResult.fail("目录非空，如确认要删除整个目录，请传 recursive=true：" + path);
                    }
                }
                DeleteAudit audit = recordBeforeDelete(root, target);
                workspaceFileService.deleteEntry(p.getWorkspacePath(), relative);
                String extra = audit.recorded() < audit.total()
                        ? "，其中 " + audit.recorded() + " 个已记录可撤回，其余（二进制或过大）无法撤回"
                        : "，均已记录可撤回";
                return ToolResult.ok("已删除目录：" + relative + "，共 " + audit.total() + " 个文件" + extra);
            }

            DeleteAudit audit = recordBeforeDelete(root, target);
            workspaceFileService.deleteEntry(p.getWorkspacePath(), relative);
            String restorable = audit.recorded() > 0 ? "，已记入审查流可撤回" : "（内容过大或为二进制，无法撤回）";
            return ToolResult.ok("已删除文件：" + relative + restorable);
        } catch (Exception e) {
            return ToolResult.fail("删除失败：" + e.getMessage());
        }
    }

    /** 路径中是否包含 .git 目录（含自身） */
    private boolean containsGitDir(Path root, Path target) {
        for (Path part : root.relativize(target)) {
            if (".git".equalsIgnoreCase(part.toString())) return true;
        }
        return false;
    }

    /** 一次删除的审查结果：文件总数 + 成功记录（可撤回）的数量 */
    private record DeleteAudit(int total, int recorded) {
    }

    /** 删除前把文件内容记入审查流，支持撤回 */
    private DeleteAudit recordBeforeDelete(Path root, Path target) throws Exception {
        List<Path> files = new ArrayList<>();
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target)) {
            files.add(target);
        } else {
            try (var walk = Files.walk(target)) {
                walk.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).forEach(files::add);
            }
        }

        int recorded = 0;
        for (Path file : files) {
            if (recorded >= MAX_RECORDED_FILES) break;
            // 不跟随软链接，避免把工作区外的内容读进审查流
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            String rel = root.relativize(file).toString().replace('\\', '/');
            String old = readSnapshot(file);
            if (old == null) continue;

            SimpleDiff.Result diff = SimpleDiff.compute(old, "", 40);
            WorkspaceContext.recordFileChange(new FileChange(
                    rel, "delete", "delete_file", 0, diff.deletions(), diff.text()));

            try {
                WorkspaceContext ctx = WorkspaceContext.get();
                if (ctx != null) {
                    PendingFileChange pfc = new PendingFileChange();
                    pfc.setProjectId(ctx.projectId());
                    pfc.setSessionId(ctx.sessionId());
                    pfc.setTurnId(ctx.turnId());
                    pfc.setUserId(ctx.userId());
                    pfc.setFilePath(rel);
                    pfc.setAction("delete");
                    pfc.setSource("delete_file");
                    pfc.setOldContent(old);
                    pfc.setNewContent("");
                    pfc.setDiffText(diff.text());
                    pfc.setAdditions(0);
                    pfc.setDeletions(diff.deletions());
                    pfcRepo.save(pfc);
                }
            } catch (Exception ex) {
                log.warn("PendingFileChange 落库失败: {}", ex.getMessage());
            }
            recorded++;
        }
        return new DeleteAudit(files.size(), recorded);
    }

    /** 读删除前的文本快照；二进制/过大返回 null（不记录，也无法撤回） */
    private String readSnapshot(Path file) {
        try {
            if (Files.size(file) > MAX_SNAPSHOT_BYTES) return null;
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
