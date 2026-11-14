package com.capsule.agentkey.diagnostics;

import java.nio.file.Path;
import java.util.List;

/**
 * 诊断来源抽象。实现类必须自行降级：任何失败都返回 {@link ProviderResult#unavailable()}，
 * 不得向 ReAct 主循环抛异常。
 */
public interface DiagnosticsProvider {

    /** provider 名称，与 agent.diagnostics.provider 配置值对应 */
    String name();

    /** 是否处理该文件；默认全部处理，javac 只处理 .java */
    default boolean supports(Path file) {
        return true;
    }

    /**
     * 批量采集诊断。
     *
     * @param workspaceRoot 工作区根目录（绝对路径）
     * @param files         待分析文件（已过滤存在性）
     * @param timeoutMs     超时上限
     */
    ProviderResult diagnose(Path workspaceRoot, List<Path> files, long timeoutMs);

    /** provider 执行结果；available=false 表示本次不可用，调用方应降级而不是当作“无错误” */
    record ProviderResult(boolean available, List<Diagnostic> diagnostics) {

        public static ProviderResult unavailable() {
            return new ProviderResult(false, List.of());
        }

        public static ProviderResult of(List<Diagnostic> diagnostics) {
            return new ProviderResult(true, diagnostics == null ? List.of() : List.copyOf(diagnostics));
        }
    }
}
