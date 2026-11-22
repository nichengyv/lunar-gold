package com.capsule.agentkey.agent.context;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * 读取工作区根目录的项目指令文件。
 *
 * <p>对标头部 Agent 的仓库级约定：Claude Code 的 {@code CLAUDE.md}、Codex/Trae 的
 * {@code AGENTS.md}、Cursor 的 {@code .cursorrules}、Copilot 的
 * {@code .github/copilot-instructions.md}。这些文件通常随仓库提交，让 Agent 自动遵守
 * 项目构建、测试、风格与目录约定，不需要用户逐条录入应用内规则。
 *
 * <p>边界：
 * <ul>
 *   <li>只读固定文件名，不递归、不执行任何内容；</li>
 *   <li>不跟随符号链接，路径必须落在工作区根目录内；</li>
 *   <li>单文件与总量都有字符上限，超长截断并显式标注；</li>
 *   <li>任何读取异常都降级为空，不阻断本轮推理。</li>
 * </ul>
 */
@Slf4j
public final class WorkspaceInstructionLoader {

    /** 按固定顺序读取；同时存在时全部注入，便于从其他工具迁移 */
    static final List<String> FILES = List.of(
            "AGENTS.md",
            "CLAUDE.md",
            ".cursorrules",
            ".github/copilot-instructions.md");

    static final int MAX_FILE_CHARS = 8_000;
    static final int MAX_TOTAL_CHARS = 20_000;

    private static final String HEADER = "【项目指令文件】\n"
            + "以下内容来自工作区根目录的项目指令文件（AGENTS.md / CLAUDE.md / .cursorrules / "
            + "copilot-instructions.md）；它们低于应用内显式配置的规则，高于通用提示。\n";

    private WorkspaceInstructionLoader() {
    }

    /**
     * 读取并渲染工作区项目指令；没有文件或全部为空时返回空字符串。
     *
     * @param workspacePath 工作区根目录；null/空白表示当前没有绑定工作区
     */
    public static String load(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return "";
        }
        Path root;
        try {
            root = Path.of(workspacePath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return "";
        }
        if (!Files.isDirectory(root)) {
            return "";
        }

        StringBuilder rendered = new StringBuilder();
        int total = 0;
        for (String name : FILES) {
            if (total >= MAX_TOTAL_CHARS) {
                break;
            }
            Path file = root.resolve(name).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String text = readBounded(file, Math.min(MAX_FILE_CHARS, MAX_TOTAL_CHARS - total));
            if (text.isBlank()) {
                continue;
            }
            rendered.append("\n--- ").append(name).append(" ---\n").append(text).append('\n');
            total += text.length();
        }
        return rendered.length() == 0 ? "" : HEADER + rendered;
    }

    /** 有界读取，避免超大文件先整体进内存；非法编码返回空 */
    private static String readBounded(Path file, int limit) {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while (text.length() <= limit && (read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
        } catch (Exception e) {
            log.warn("读取项目指令文件失败，跳过: path={}, error={}", file, e.getMessage());
            return "";
        }
        String content = text.toString();
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        content = content.strip();
        if (content.length() > limit) {
            return content.substring(0, limit) + "\n…（内容过长，已截断）";
        }
        return content;
    }
}
