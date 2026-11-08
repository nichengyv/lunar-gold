package com.capsule.agentkey.service.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面端数据库生命周期中不依赖真实 PostgreSQL 的部分：
 * 崩溃残留的 postmaster.pid 判定与空闲端口选择。
 * 真正的 initdb / 启动 / pgvector 检索需要完整运行时，放在启动冒烟与 P2-08 覆盖。
 */
class DbLifecycleManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void 没有残留文件时无需恢复() throws IOException {
        assertFalse(DbLifecycleManager.recoverStaleLock(tempDir));
    }

    @Test
    void 残留PID已退出时清理并允许重启() throws IOException {
        Path pidFile = tempDir.resolve("postmaster.pid");
        // 取一个不可能存在的 PID：进程不存在 → 视为上次异常退出
        Files.writeString(pidFile, "2147483646\n" + tempDir + "\n", StandardCharsets.UTF_8);

        assertTrue(DbLifecycleManager.recoverStaleLock(tempDir));
        assertFalse(Files.exists(pidFile), "残留的 postmaster.pid 应被删除");
    }

    @Test
    void 残留PID仍存活时拒绝启动() throws IOException {
        long currentPid = ProcessHandle.current().pid();
        Path pidFile = tempDir.resolve("postmaster.pid");
        Files.writeString(pidFile, currentPid + "\n" + tempDir + "\n", StandardCharsets.UTF_8);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DbLifecycleManager.recoverStaleLock(tempDir));
        assertTrue(error.getMessage().contains("已被其他进程占用"));
        assertTrue(Files.exists(pidFile), "存活进程的锁文件不能被删除");
    }

    @Test
    void 自动选择的端口可被监听() throws IOException {
        int port = DbLifecycleManager.findFreePort();
        assertTrue(port > 0 && port <= 65535);

        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", port));
        }
    }

    @Test
    void 数据目录按约定落在根目录下的db子目录() {
        DbLifecycleManager manager = new DbLifecycleManager(tempDir.toString(), "", 0);
        assertEquals(tempDir.toAbsolutePath().normalize().resolve("db"), manager.dataDirectory());
    }
}
