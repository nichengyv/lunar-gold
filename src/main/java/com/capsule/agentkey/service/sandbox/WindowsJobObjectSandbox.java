package com.capsule.agentkey.service.sandbox;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * P4-01：基于 Windows Job Object 的进程沙箱。
 *
 * 做法：为每条命令创建一个 Job，设置
 * <ul>
 *   <li>{@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}：关闭 Job 句柄时内核杀掉 Job 内全部进程，
 *       即使子进程已经脱离父进程（npm/mvn 起 daemon）也逃不掉；</li>
 *   <li>{@code JOB_OBJECT_LIMIT_PROCESS_MEMORY}：单进程提交内存上限，防止一条 build 把机器内存吃光；</li>
 *   <li>{@code JOB_OBJECT_LIMIT_ACTIVE_PROCESS}：Job 内并发进程数上限，防止 fork 炸弹。</li>
 * </ul>
 *
 * 只在 Windows 生效；其他平台 {@link #supported()} 返回 false，调用方退回进程树强杀。
 * 已知窗口：进程由 {@code ProcessBuilder} 启动后再 attach，若在 attach 之前就派生出子进程，
 * 该子进程不在 Job 内。真正的彻底方案是 CREATE_SUSPENDED + AssignProcessToJobObject + ResumeThread，
 * 那需要放弃 ProcessBuilder 自己调 CreateProcess，不在本次范围。
 */
@Component
@Slf4j
public class WindowsJobObjectSandbox implements ProcessSandbox {

    /** JOBOBJECT_EXTENDED_LIMIT_INFORMATION 对应的 InfoClass */
    private static final int JOBOBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;

    private static final int JOB_OBJECT_LIMIT_PROCESS_MEMORY = 0x0000_0100;
    private static final int JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x0000_0008;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x0000_2000;

    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;

    private final boolean enabled;
    private final long memoryLimitMb;
    private final int maxProcesses;
    private volatile Kernel32Job kernel32;

    public WindowsJobObjectSandbox(
            @Value("${workspace.command.sandbox.enabled:true}") boolean enabled,
            @Value("${workspace.command.sandbox.memory-limit-mb:4096}") long memoryLimitMb,
            @Value("${workspace.command.sandbox.max-processes:64}") int maxProcesses) {
        this.enabled = enabled && isWindows();
        this.memoryLimitMb = Math.max(0L, memoryLimitMb);
        this.maxProcesses = Math.max(0, maxProcesses);
    }

    @Override
    public boolean supported() {
        return enabled;
    }

    @Override
    public String name() {
        return "windows-job-object";
    }

    @Override
    public Handle attach(Process process) {
        if (!enabled || process == null || !process.isAlive()) {
            return Handle.NOOP;
        }
        Pointer job = null;
        try {
            Kernel32Job api = kernel32();
            job = api.CreateJobObjectW(null, null);
            if (job == null) {
                log.warn("P4-01 沙箱: CreateJobObject 失败（err={}），本条命令退回进程树强杀",
                        Native.getLastError());
                return Handle.NOOP;
            }

            JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            limits.BasicLimitInformation.limitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                    | (memoryLimitMb > 0 ? JOB_OBJECT_LIMIT_PROCESS_MEMORY : 0)
                    | (maxProcesses > 0 ? JOB_OBJECT_LIMIT_ACTIVE_PROCESS : 0);
            limits.BasicLimitInformation.activeProcessLimit = maxProcesses;
            limits.ProcessMemoryLimit = memoryLimitMb * 1024L * 1024L;
            limits.write();
            if (!api.SetInformationJobObject(job, JOBOBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                    limits.getPointer(), limits.size())) {
                log.warn("P4-01 沙箱: SetInformationJobObject 失败（err={}），仍继续接入 Job",
                        Native.getLastError());
            }

            Pointer hProcess = api.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false,
                    (int) process.pid());
            if (hProcess == null) {
                log.warn("P4-01 沙箱: OpenProcess(pid={}) 失败（err={}），本条命令退回进程树强杀",
                        process.pid(), Native.getLastError());
                api.CloseHandle(job);
                return Handle.NOOP;
            }
            try {
                if (!api.AssignProcessToJobObject(job, hProcess)) {
                    log.warn("P4-01 沙箱: AssignProcessToJobObject(pid={}) 失败（err={}），"
                            + "本条命令退回进程树强杀", process.pid(), Native.getLastError());
                    api.CloseHandle(job);
                    return Handle.NOOP;
                }
            } finally {
                api.CloseHandle(hProcess);
            }

            log.info("P4-01 沙箱: 命令 pid={} 已挂入 Job Object（memory={}MB, maxProcesses={}）",
                    process.pid(), memoryLimitMb, maxProcesses);
            return new JobHandle(api, job);
        } catch (Throwable t) {
            // 沙箱接入失败不能影响命令本身，降级为原来的进程树强杀
            if (job != null) {
                closeQuietly(job);
            }
            log.warn("P4-01 沙箱: 接入失败（{}），本条命令退回进程树强杀", t.toString());
            return Handle.NOOP;
        }
    }

    private Kernel32Job kernel32() {
        Kernel32Job local = kernel32;
        if (local == null) {
            synchronized (this) {
                local = kernel32;
                if (local == null) {
                    local = Native.load("kernel32", Kernel32Job.class);
                    kernel32 = local;
                }
            }
        }
        return local;
    }

    private void closeQuietly(Pointer job) {
        try {
            kernel32().CloseHandle(job);
        } catch (Throwable ignore) {
            // 已经没有可用的降级手段，忽略
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Job 句柄：close() 触发 KILL_ON_JOB_CLOSE，内核负责回收整棵进程树 */
    private static final class JobHandle implements Handle {
        private final Kernel32Job api;
        private final Pointer job;
        private boolean closed;

        private JobHandle(Kernel32Job api, Pointer job) {
            this.api = api;
            this.job = job;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                api.CloseHandle(job);
            } catch (Throwable t) {
                log.warn("P4-01 沙箱: 关闭 Job 句柄失败（{}）", t.toString());
            }
        }
    }

    /** 只用到的 5 个 kernel32 函数；jna-platform 的 Kernel32 未导出 Job Object API */
    public interface Kernel32Job extends Library {
        Pointer CreateJobObjectW(Pointer lpJobAttributes, Pointer lpName);

        boolean SetInformationJobObject(Pointer hJob, int infoClass, Pointer info, int infoLength);

        boolean AssignProcessToJobObject(Pointer hJob, Pointer hProcess);

        Pointer OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean CloseHandle(Pointer handle);
    }

    /** 与 Win32 IO_COUNTERS 一致，仅占位（Job 限制用不到，但结构体布局不能省） */
    public static class IO_COUNTERS extends Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("readOperationCount", "writeOperationCount", "otherOperationCount",
                    "readTransferCount", "writeTransferCount", "otherTransferCount");
        }
    }

    /** 与 Win32 JOBOBJECT_BASIC_LIMIT_INFORMATION 一致 */
    public static class JOBOBJECT_BASIC_LIMIT_INFORMATION extends Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public long minimumWorkingSetSize;
        public long maximumWorkingSetSize;
        public int activeProcessLimit;
        public long affinity;
        public int priorityClass;
        public int schedulingClass;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("perProcessUserTimeLimit", "perJobUserTimeLimit", "limitFlags",
                    "minimumWorkingSetSize", "maximumWorkingSetSize", "activeProcessLimit",
                    "affinity", "priorityClass", "schedulingClass");
        }
    }

    /** 与 Win32 JOBOBJECT_EXTENDED_LIMIT_INFORMATION 一致（x64 下 144 字节） */
    public static class JOBOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation = new JOBOBJECT_BASIC_LIMIT_INFORMATION();
        public IO_COUNTERS IoInfo = new IO_COUNTERS();
        public long ProcessMemoryLimit;
        public long JobMemoryLimit;
        public long PeakProcessMemoryUsed;
        public long PeakJobMemoryUsed;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("BasicLimitInformation", "IoInfo", "ProcessMemoryLimit",
                    "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed");
        }
    }
}
