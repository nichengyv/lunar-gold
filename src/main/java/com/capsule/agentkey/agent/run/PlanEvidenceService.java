package com.capsule.agentkey.agent.run;

import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.verify.VerifyResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划完成证据核对（R7c）。
 *
 * <p>只核对确定性事实：声明的相对路径是真实工作区内的常规文件、文件中包含指定文本，
 * 以及（可选）最新一次验证为 {@code PASSED} 且覆盖最新文件变更。任何读取失败、
 * 文件不存在、路径越界或验证缺失都算「证据不满足」，绝不降级为通过。
 *
 * <p>同一个服务同时供 {@code update_plan}（提交 completed 时）和
 * {@code RunTermination}（最终收口时）使用，避免两处判定漂移。
 */
@Slf4j
@Service
public class PlanEvidenceService {

    /** 工件不存在、文本缺失或验证未通过 */
    public static final String ISSUE_MISSING = "MISSING";
    /** 验证曾经通过，但之后文件又发生了变更 */
    public static final String ISSUE_STALE = "STALE";

    /** 单个工件读取上限；超过按不可读处理，避免把超大文件读进内存 */
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_PATH_LENGTH = 400;

    /** 一条证据问题；{@code stepId} 用于把 completed 步骤降级为 aborted */
    public record Issue(String stepId, String kind, String message) {
    }

    /** 工作区定位：生产环境走 ProjectService，测试可注入临时目录 */
    @FunctionalInterface
    public interface WorkspaceLocator {
        Path locate(Long userId, Long projectId);
    }

    private final WorkspaceLocator locator;

    public PlanEvidenceService(ProjectService projectService) {
        this.locator = (userId, projectId) -> {
            if (userId == null || projectService == null) {
                return null;
            }
            try {
                Long resolvedId = projectService.resolveProjectId(userId, projectId);
                String workspacePath = projectService.getOwned(userId, resolvedId).getWorkspacePath();
                return workspacePath == null || workspacePath.isBlank() ? null : Paths.get(workspacePath);
            } catch (Exception e) {
                log.warn("计划证据核对读取工作区失败，按不可用处理: {}", e.getMessage());
                return null;
            }
        };
    }

    private PlanEvidenceService(WorkspaceLocator locator) {
        this.locator = locator;
    }

    /** 没有工作区来源（单测/未绑定项目）：契约步骤一律视为证据不满足 */
    public static PlanEvidenceService withoutWorkspace() {
        return new PlanEvidenceService((userId, projectId) -> null);
    }

    public static PlanEvidenceService withLocator(WorkspaceLocator locator) {
        return new PlanEvidenceService(locator == null ? (userId, projectId) -> null : locator);
    }

    /**
     * 核对所有「completed 且有验收契约」的步骤。
     *
     * @return 空列表表示全部满足；否则每条问题对应一个步骤
     */
    public List<Issue> checkCompleted(RunContext runContext, List<PlanStep> steps) {
        if (runContext == null || steps == null || steps.isEmpty()) {
            return List.of();
        }
        Path root = locator.locate(runContext.userId(), runContext.projectId());
        boolean anyContractCompleted = steps.stream()
                .anyMatch(s -> s != null && s.hasContract() && "completed".equals(s.status()));
        if (!anyContractCompleted) {
            return List.of();
        }
        List<Issue> issues = new ArrayList<>();
        for (PlanStep step : steps) {
            if (step == null || !step.hasContract() || !"completed".equals(step.status())) {
                continue;
            }
            issues.addAll(checkStep(root, runContext, step));
        }
        return issues;
    }

    private List<Issue> checkStep(Path root, RunContext runContext, PlanStep step) {
        List<Issue> issues = new ArrayList<>();
        for (PlanStep.Artifact artifact : step.acceptance().artifacts()) {
            issues.addAll(checkArtifact(root, step.id(), artifact));
        }
        if (step.acceptance().requireVerify()) {
            VerifyResult verify = runContext.verify();
            if (verify == null || !verify.passed()) {
                issues.add(new Issue(step.id(), ISSUE_MISSING,
                        "要求验证通过，但当前验证状态为 " + (verify == null ? "未运行" : verify.status().name())));
            } else if (!runContext.verifyCoversLatestChange()) {
                issues.add(new Issue(step.id(), ISSUE_STALE, "验证结果早于最新文件变更，需要重新验证"));
            }
        }
        return issues;
    }

    private List<Issue> checkArtifact(Path root, String stepId, PlanStep.Artifact artifact) {
        String rawPath = artifact.path();
        if (root == null) {
            return List.of(new Issue(stepId, ISSUE_MISSING, "工作区不可用，无法核对工件：" + rawPath));
        }
        Path base = root.toAbsolutePath().normalize();
        if (!isSafeRelativePath(rawPath)) {
            return List.of(new Issue(stepId, ISSUE_MISSING, "工件路径非法或越出工作区：" + rawPath));
        }
        Path file = base.resolve(rawPath.trim()).normalize();
        if (!file.startsWith(base)) {
            return List.of(new Issue(stepId, ISSUE_MISSING, "工件路径越出工作区：" + rawPath));
        }
        Path realRoot;
        try {
            realRoot = base.toRealPath();
        } catch (IOException e) {
            return List.of(new Issue(stepId, ISSUE_MISSING, "工作区不可读：" + e.getMessage()));
        }
        if (!Files.exists(file)) {
            return List.of(new Issue(stepId, ISSUE_MISSING, "工件不存在：" + rawPath));
        }
        Path realFile;
        try {
            realFile = file.toRealPath();
        } catch (IOException e) {
            return List.of(new Issue(stepId, ISSUE_MISSING, "工件不可读：" + rawPath));
        }
        if (!realFile.startsWith(realRoot)) {
            return List.of(new Issue(stepId, ISSUE_MISSING, "工件路径越出工作区（符号链接）：" + rawPath));
        }
        if (!Files.isRegularFile(realFile)) {
            return List.of(new Issue(stepId, ISSUE_MISSING, "工件不是常规文件：" + rawPath));
        }

        String content;
        try {
            if (Files.size(realFile) > MAX_FILE_BYTES) {
                return List.of(new Issue(stepId, ISSUE_MISSING,
                        "工件超过 " + (MAX_FILE_BYTES / 1024 / 1024) + " MiB，无法核对：" + rawPath));
            }
            content = Files.readString(realFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of(new Issue(stepId, ISSUE_MISSING, "工件不可读：" + rawPath));
        }

        List<Issue> issues = new ArrayList<>();
        for (String expected : artifact.expectedContains()) {
            if (!content.contains(expected)) {
                issues.add(new Issue(stepId, ISSUE_MISSING,
                        "工件 " + rawPath + " 缺少必需内容：" + abbreviate(expected)));
            }
        }
        return issues;
    }

    /**
     * 提交计划时的静态路径校验：只接受工作区内的安全相对路径。
     *
     * <p>绝对路径、盘符路径、UNC 路径、任何 {@code ..} 段都直接拒绝；
     * 真实符号链接逃逸在读取时通过 {@code toRealPath} 再校验一次。
     */
    public static boolean isSafeRelativePath(String raw) {
        if (raw == null) {
            return false;
        }
        String path = raw.trim().replace('\\', '/');
        if (path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
            return false;
        }
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            return false;
        }
        Path parsed;
        try {
            parsed = Paths.get(path);
        } catch (Exception e) {
            return false;
        }
        if (parsed.isAbsolute()) {
            return false;
        }
        for (Path part : parsed) {
            if ("..".equals(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
