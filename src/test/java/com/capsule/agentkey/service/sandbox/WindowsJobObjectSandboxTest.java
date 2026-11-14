package com.capsule.agentkey.service.sandbox;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** P4-01：Windows Job Object 沙箱的结构体布局与真实进程回收验证 */
class WindowsJobObjectSandboxTest {

    @Test
    void structLayoutMatchesWin64() {
        // 布局错了 SetInformationJobObject 会静默失败，所以先用尺寸把 Win32 结构对齐钉住
        assertEquals(48, new WindowsJobObjectSandbox.IO_COUNTERS().size(),
                "IO_COUNTERS 在 x64 下应为 6 个 ULONGLONG");
        assertEquals(64, new WindowsJobObjectSandbox.JOBOBJECT_BASIC_LIMIT_INFORMATION().size(),
                "JOBOBJECT_BASIC_LIMIT_INFORMATION 在 x64 下应为 64 字节");
        assertEquals(144, new WindowsJobObjectSandbox.JOBOBJECT_EXTENDED_LIMIT_INFORMATION().size(),
                "JOBOBJECT_EXTENDED_LIMIT_INFORMATION 在 x64 下应为 144 字节");
    }

    @Test
    void closingHandleKillsProcessTree() throws Exception {
        assumeTrue(isWindows(), "Job Object 只在 Windows 生效");
        WindowsJobObjectSandbox sandbox = new WindowsJobObjectSandbox(true, 0L, 0);
        assumeTrue(sandbox.supported(), "当前环境无法接入 Job Object");

        // ping 会派生自己的子进程，用真实的长时间进程验证句柄关闭能连带回收
        Process process = new ProcessBuilder("cmd", "/c", "ping -n 60 127.0.0.1")
                .redirectErrorStream(true)
                .start();
        try {
            ProcessSandbox.Handle handle = sandbox.attach(process);
            assertFalse(handle == ProcessSandbox.Handle.NOOP, "Windows 上应成功挂入 Job Object");
            assertTrue(process.isAlive(), "挂入 Job 不应杀掉正在运行的进程");

            handle.close();

            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "关闭 Job 句柄后进程应被内核回收");
            assertFalse(process.isAlive());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void disabledSandboxDegradesToNoop() {
        WindowsJobObjectSandbox sandbox = new WindowsJobObjectSandbox(false, 0L, 0);
        assertFalse(sandbox.supported());
        assertEquals(ProcessSandbox.Handle.NOOP, sandbox.attach(null));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
