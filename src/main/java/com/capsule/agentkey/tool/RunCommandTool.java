package com.capsule.agentkey.tool;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.service.CommandService;
import com.capsule.agentkey.service.ProjectService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/** 在工作区内执行终端命令（构建、测试、脚本），带白名单与超时保护 */
@Component
@Slf4j
public class RunCommandTool implements BaseTool {

    private final ProjectService projectService;
    private final CommandService commandService;

    /** 这些目录里通常是依赖/构建产物，内容变化不展示给用户，避免「改了 3000 个文件」的噪音 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", ".idea", ".gradle",
            ".next", ".nuxt", "coverage", "__pycache__", ".venv", "venv");

    /** 命令前后快照的规模上限：工作区再大也不会扫全盘 */
    private static final int MAX_SNAPSHOT_FILES = 4000;
    private static final int MAX_SNAPSHOT_DEPTH = 6;
    private static final int MAX_REPORTED_CHANGES = 60;
    private static final long MAX_STAMP_BYTES = 2L * 1024 * 1024;

    public RunCommandTool(ProjectService projectService, CommandService commandService) {
        this.projectService = projectService;
        this.commandService = commandService;
    }

    @Override
    public String getName() { return "run_command"; }

    @Override
    public Capability requiredCapability() { return Capability.COMMAND_EXEC; }

    @Override
    public String getDescription() {
        return "在项目工作区内执行终端命令。用途：构建（mvn/gradle/npm build）、"
                + "测试（mvn test/npm test）、查看版本（mvn -version/node -v）、"
                + "启动开发服务器（npm run dev）、git 状态等。"
                + "规则：\n"
                + "1. 直接写程序名，**不要加 shell 包装器**：\n"
                + "   ❌ cmd /c mvn clean package  ❌ bash mvn clean package\n"
                + "   ✅ mvn clean package\n"
                + "   Windows/macOS/Linux 自动找 .cmd/.exe 后缀变体\n"
                + "2. 只允许安全的开发工具：npm/node/pnpm/yarn/git/mvn/java/python/go/pip/gradle 等\n"
                + "3. 默认 120 秒超时，构建/测试类命令请显式传 timeout=300（最大 600）\n"
                + "4. 不支持管道(|)、重定向(>)、命令串联(&&)；需要的话写脚本文件再用解释器执行\n"
                + "5. 命令失败会返回退出码和输出，请据此修复后重试\n"
                + "6. 浏览目录或读文件请用 list_dir / read_file，不要用 dir/cat/ls\n"
                + "7. 删除文件或目录请用 delete_file，不要用 rm/del/rd";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> command = new HashMap<>();
        command.put("type", "string");
        command.put("description", "完整命令行，不含 shell 管道或重定向，如 'npm run build'");
        props.put("command", command);

        Map<String, Object> cwd = new HashMap<>();
        cwd.put("type", "string");
        cwd.put("description", "相对工作区根目录的执行目录，如 'frontend'；留空表示根目录");
        props.put("cwd", cwd);

        Map<String, Object> timeout = new HashMap<>();
        timeout.put("type", "integer");
        timeout.put("description", "超时秒数，默认 60，最大 300；构建类命令可适当调大");
        props.put("timeout", timeout);

        schema.put("properties", props);
        schema.put("required", new String[]{"command"});
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            WorkspaceContext ctx = WorkspaceContext.get();
            if (ctx == null) return ToolResult.fail("工作区上下文丢失");
            Project p = projectService.getOwned(ctx.userId(), ctx.projectId());
            if (p.getWorkspacePath() == null) return ToolResult.fail("项目未绑定工作区，请先在项目设置中选择文件夹");

            String command = arguments.path("command").asText("");
            // 注意 1：显式传 null 时 isMissingNode() 是 false，旧写法会把 cwd 取成字符串 "null"
            // 导致报「执行目录不存在：null」，所以这里用 asText(默认值) 统一处理缺失/空值
            // 注意 2：timeout 用 asInt(默认值)，文本或 null 都退回默认超时
            String cwd = arguments.path("cwd").asText(null);
            if (cwd != null && (cwd.isBlank() || ".".equals(cwd) || "null".equalsIgnoreCase(cwd))) {
                cwd = null;
            }
            int timeout = arguments.path("timeout").asInt(0);

            long start = System.currentTimeMillis();
            //命令可能修改文件（格式化、代码生成、安装依赖等）：执行前后各做一次轻量快照，
            //对比出新增/修改的文件推给前端；只记录路径与动作，不存内容，避免大文件 diff 开销
            Map<String, FileStamp> before = snapshot(p.getWorkspacePath());
            CommandService.CommandResult r = commandService.run(
                    p.getWorkspacePath(), command, cwd, timeout <= 0 ? null : timeout, ctx.cancelled());
            recordCommandChanges(p.getWorkspacePath(), before);

            String body = format(command, r);
            if (r.cancelled()) {
                return ToolResult.fail("命令已被用户停止执行\n" + body);
            }
            if (r.timedOut()) {
                return ToolResult.fail("命令超时被终止，请缩小执行范围或调大 timeout\n" + body);
            }
            if (r.exitCode() != 0) {
                return ToolResult.fail("命令执行失败（退出码 " + r.exitCode() + "）\n" + body);
            }
            return ToolResult.ok(body, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage());
        }
    }

    /** 轻量文件指纹：只看修改时间和大小，足够发现「命令改了哪些文件」，不读内容 */
    private record FileStamp(long mtime, long size) {
    }

    private Map<String, FileStamp> snapshot(String workspaceRoot) {
        Path root = Paths.get(workspaceRoot);
        Map<String, FileStamp> map = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root, MAX_SNAPSHOT_DEPTH)) {
            Iterator<Path> it = walk.iterator();
            while (it.hasNext() && map.size() < MAX_SNAPSHOT_FILES) {
                Path p = it.next();
                try {
                    if (Files.isDirectory(p) || Files.size(p) > MAX_STAMP_BYTES) {
                        continue;
                    }
                    if (isInSkippedDir(root, p)) {
                        continue;
                    }
                    map.put(relative(root, p),
                            new FileStamp(Files.getLastModifiedTime(p).toMillis(), Files.size(p)));
                } catch (IOException e) {
                    //单个文件读不到属性就跳过，不影响命令本身的结果
                }
            }
        } catch (IOException e) {
            log.debug("工作区快照失败，跳过变更检测: {}", e.getMessage());
        }
        return map;
    }

    private void recordCommandChanges(String workspaceRoot, Map<String, FileStamp> before) {
        Map<String, FileStamp> after = snapshot(workspaceRoot);
        List<FileChange> changes = new ArrayList<>();
        for (Map.Entry<String, FileStamp> e : after.entrySet()) {
            FileStamp old = before.get(e.getKey());
            if (old == null) {
                changes.add(new FileChange(e.getKey(), "create", "run_command", 0, 0, ""));
            } else if (old.mtime() != e.getValue().mtime() || old.size() != e.getValue().size()) {
                changes.add(new FileChange(e.getKey(), "modify", "run_command", 0, 0, ""));
            }
        }
        if (changes.isEmpty()) {
            return;
        }
        changes.sort(Comparator.comparing(FileChange::path));
        int n = Math.min(changes.size(), MAX_REPORTED_CHANGES);
        for (int i = 0; i < n; i++) {
            WorkspaceContext.recordFileChange(changes.get(i));
        }
        if (changes.size() > n) {
            log.info("命令改动文件过多，仅上报前 {} 个（共 {} 个）", n, changes.size());
        }
    }

    private boolean isInSkippedDir(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path part : rel) {
            if (SKIP_DIRS.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private String format(String command, CommandService.CommandResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("命令: ").append(command).append('\n');
        sb.append("耗时: ").append(r.durationMs()).append("ms，退出码: ").append(r.exitCode()).append('\n');
        if (r.timedOut()) sb.append("已超时强制终止\n");
        if (r.cancelled()) sb.append("用户已停止\n");
        sb.append("输出:\n");
        sb.append(r.output().isBlank() ? "（无输出）" : r.output());
        if (r.truncated()) sb.append("\n[输出过长，已截断]");
        return sb.toString();
    }
}
