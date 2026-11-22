package com.capsule.agentkey.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓库级项目指令文件：对标 AGENTS.md / CLAUDE.md / .cursorrules / copilot-instructions.md。
 */
class WorkspaceInstructionLoaderTest {

    @TempDir
    Path workspace;

    @Test
    void 未绑定工作区或没有指令文件时返回空() {
        assertEquals("", WorkspaceInstructionLoader.load(null));
        assertEquals("", WorkspaceInstructionLoader.load("  "));
        assertEquals("", WorkspaceInstructionLoader.load(workspace.toString()));
        assertEquals("", WorkspaceInstructionLoader.load(workspace.resolve("missing").toString()));
    }

    @Test
    void 读取AGENTS并标注来源与优先级() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"),
                "# 项目约定\n构建用 mvn -q compile\n", StandardCharsets.UTF_8);

        String loaded = WorkspaceInstructionLoader.load(workspace.toString());

        assertTrue(loaded.startsWith("【项目指令文件】"), loaded);
        assertTrue(loaded.contains("--- AGENTS.md ---"), loaded);
        assertTrue(loaded.contains("构建用 mvn -q compile"), loaded);
        assertTrue(loaded.contains("低于应用内显式配置的规则"), loaded);
    }

    @Test
    void 多个指令文件按固定顺序注入且不递归子目录() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "AGENTS 约定", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("CLAUDE.md"), "CLAUDE 约定", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".cursorrules"), "CURSOR 约定", StandardCharsets.UTF_8);
        Path nested = Files.createDirectories(workspace.resolve("src"));
        Files.writeString(nested.resolve("AGENTS.md"), "子目录不应读取", StandardCharsets.UTF_8);

        String loaded = WorkspaceInstructionLoader.load(workspace.toString());

        assertTrue(loaded.indexOf("AGENTS 约定") < loaded.indexOf("CLAUDE 约定"));
        assertTrue(loaded.indexOf("CLAUDE 约定") < loaded.indexOf("CURSOR 约定"));
        assertFalse(loaded.contains("子目录不应读取"), "只读取工作区根目录");
    }

    @Test
    void 读取copilot指令文件() throws Exception {
        Path github = Files.createDirectories(workspace.resolve(".github"));
        Files.writeString(github.resolve("copilot-instructions.md"), "Copilot 约定",
                StandardCharsets.UTF_8);

        String loaded = WorkspaceInstructionLoader.load(workspace.toString());

        assertTrue(loaded.contains(".github/copilot-instructions.md"), loaded);
        assertTrue(loaded.contains("Copilot 约定"), loaded);
    }

    @Test
    void 超长文件截断并显式标注() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "x".repeat(9_000),
                StandardCharsets.UTF_8);

        String loaded = WorkspaceInstructionLoader.load(workspace.toString());

        assertTrue(loaded.contains("内容过长，已截断"), "必须让模型知道内容不完整");
        assertTrue(loaded.length() < WorkspaceInstructionLoader.MAX_FILE_CHARS + 500,
                "截断后不能继续膨胀：" + loaded.length());
    }

    @Test
    void 去除UTF8BOM() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "\uFEFF# 规则", StandardCharsets.UTF_8);

        String loaded = WorkspaceInstructionLoader.load(workspace.toString());

        assertFalse(loaded.contains("\uFEFF"), "BOM 不应进入 system prompt");
        assertTrue(loaded.contains("# 规则"));
    }

    @Test
    void 非法编码文件跳过且不影响其他文件() throws Exception {
        Files.write(workspace.resolve("AGENTS.md"), new byte[]{(byte) 0xFF, (byte) 0xFE});
        Files.writeString(workspace.resolve("CLAUDE.md"), "正常约定", StandardCharsets.UTF_8);

        String loaded = WorkspaceInstructionLoader.load(workspace.toString());

        assertFalse(loaded.contains("--- AGENTS.md ---"), "非法编码文件应被跳过");
        assertTrue(loaded.contains("CLAUDE.md"), loaded);
        assertTrue(loaded.contains("正常约定"), loaded);
    }
}
