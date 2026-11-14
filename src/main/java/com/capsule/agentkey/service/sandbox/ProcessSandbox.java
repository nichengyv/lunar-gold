package com.capsule.agentkey.service.sandbox;

/**
 * P4-01：命令进程的 OS 级沙箱。
 *
 * 现状：超时/取消时 {@code CommandService.killTree} 只能遍历 {@code Process.descendants()}，
 * 一旦子进程脱离父进程（npm 起 daemon、mvn 起 fork 的 daemon 线程）就杀不到，会在用户机器上留残留。
 * 这里把命令进程挂进一个 OS 句柄（Windows 为 Job Object），句柄关闭时由内核回收整棵进程树，
 * 不依赖 Java 侧能枚举到哪些后代。
 */
public interface ProcessSandbox {

    /** 当前平台/配置是否启用沙箱 */
    boolean supported();

    /** 沙箱名，仅用于日志 */
    String name();

    /**
     * 把已启动的进程纳入沙箱约束。
     * 返回值必须在命令结束时 close()：对 Windows Job Object 而言 close 就是回收进程树。
     * 不支持或接入失败时返回 {@link Handle#NOOP}，调用方无需判空。
     */
    Handle attach(Process process);

    /** 沙箱句柄：close() = 释放约束并回收残留进程 */
    interface Handle {
        void close();

        Handle NOOP = () -> { };
    }

    /** 不做任何约束，用于测试或沙箱被关闭的场景 */
    ProcessSandbox NONE = new ProcessSandbox() {
        @Override
        public boolean supported() {
            return false;
        }

        @Override
        public String name() {
            return "none";
        }

        @Override
        public Handle attach(Process process) {
            return Handle.NOOP;
        }
    };
}
