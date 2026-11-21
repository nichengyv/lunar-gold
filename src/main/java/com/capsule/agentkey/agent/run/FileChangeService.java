package com.capsule.agentkey.agent.run;

import com.capsule.agentkey.diagnostics.DiagnosticsResult;
import com.capsule.agentkey.diagnostics.DiagnosticsService;
import com.capsule.agentkey.tool.FileChange;
import com.capsule.agentkey.tool.WorkspaceContext;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作区变更的收集与诊断（R3）。
 *
 * <p>重构前这段逻辑直接写在 ReAct 主循环里：drain 变更、调诊断、算指纹、算文件版本、
 * 回写 WorkspaceContext。它属于「文件变更」职责，与模型决策无关，抽出来后主循环只保留
 * 回调推送与无进展判定。
 */
@Slf4j
public class FileChangeService {

    /** 本批变更的诊断结果与指纹；指纹供主循环识别「连续相同诊断」 */
    public record Collected(DiagnosticsResult diagnostics, String fingerprint) {
    }

    private final DiagnosticsService diagnosticsService;

    public FileChangeService(DiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    /** 取走工具线程登记的变更并清空缓冲 */
    public List<FileChange> drain() {
        return WorkspaceContext.drainFileChanges();
    }

    /**
     * 对本批变更文件收集诊断，并把结果、指纹、文件版本回写当前 RunContext。
     * 诊断服务内部已做降级，这里不再额外吞异常。
     */
    public Collected collect(String workspacePath, List<FileChange> changes) {
        Path root = workspacePath == null || workspacePath.isBlank()
                ? null : Paths.get(workspacePath);
        DiagnosticsResult diagnostics = diagnosticsService.collect(root, changes);
        String fingerprint = DiagnosticsService.fingerprint(diagnostics.diagnostics());

        WorkspaceContext ctx = WorkspaceContext.get();
        if (ctx != null) {
            ctx.setDiagnostics(diagnostics);
            ctx.setDiagnosticsFingerprint(fingerprint);
            ctx.setDiagnosticsFileVersion(fileVersion(workspacePath, changes));
        }
        return new Collected(diagnostics, fingerprint);
    }

    /** P5-05：把本批变更文件的版本拼成可追溯字符串，随诊断结果一起落事件 */
    String fileVersion(String workspacePath, List<FileChange> changes) {
        if (workspacePath == null || workspacePath.isBlank() || changes == null || changes.isEmpty()) {
            return "";
        }
        try {
            Path root = Paths.get(workspacePath);
            return changes.stream()
                    .filter(c -> c != null && c.path() != null)
                    .map(c -> c.path() + "@" + diagnosticsService.fileVersion(root.resolve(c.path())))
                    .collect(Collectors.joining(","));
        } catch (Exception e) {
            return "";
        }
    }
}
