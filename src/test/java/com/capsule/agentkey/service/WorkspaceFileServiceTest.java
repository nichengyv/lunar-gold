package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceFileServiceTest {

    private final WorkspaceFileService service = new WorkspaceFileService();

    @TempDir
    Path workspace;

    @Test
    void 普通路径与尚不存在的嵌套文件可以解析() {
        assertDoesNotThrow(() -> service.resolveInWorkspace(workspace, "src/main/App.java"));
    }

    @Test
    void 点路径逃逸直接拒绝() {
        assertThrows(BusinessException.class,
                () -> service.resolveInWorkspace(workspace, "../outside.txt"));
    }

    @Test
    void 文件软链接不能指向工作区外() throws Exception {
        Path outside = Files.createTempFile("workspace-outside-", ".txt");
        Path link = workspace.resolve("outside-link.txt");
        assumeTrue(createLink(link, outside), "当前系统不允许创建软链接");

        assertThrows(BusinessException.class,
                () -> service.readFile(workspace.toString(), link.getFileName().toString()));
    }

    @Test
    void 新建文件的父目录软链接不能指向工作区外() throws Exception {
        Path outside = Files.createTempDirectory("workspace-outside-dir-");
        Path link = workspace.resolve("outside-dir");
        assumeTrue(createLink(link, outside), "当前系统不允许创建目录软链接");

        assertThrows(BusinessException.class,
                () -> service.writeFile(workspace.toString(), "outside-dir/new.txt", "blocked"));
    }

    private boolean createLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }
}
