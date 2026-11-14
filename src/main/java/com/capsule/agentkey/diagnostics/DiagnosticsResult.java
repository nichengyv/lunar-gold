package com.capsule.agentkey.diagnostics;

import java.util.List;

/**
 * 一次变更批次诊断的汇总结果。
 *
 * @param provider      实际使用的 provider
 * @param available     provider 是否可用；false 时不能把“没有诊断”当成通过
 * @param analyzedFiles 实际分析的文件数；0 表示本批变更没有可分析文件
 * @param diagnostics   已按条数/字符上限截断后的诊断
 * @param totalCount    截断前的诊断总数
 * @param truncated     是否发生截断
 */
public record DiagnosticsResult(String provider, boolean available, int analyzedFiles,
                                List<Diagnostic> diagnostics, int totalCount, boolean truncated) {

    public DiagnosticsResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }

    public boolean hasDiagnostics() {
        return !diagnostics.isEmpty();
    }

    /** 供下一轮模型上下文使用的紧凑文本；固定在本方法内输出，便于测试断言与字符上限校验 */
    public String formatForPrompt() {
        StringBuilder sb = new StringBuilder("【变更文件诊断】\n");
        if (!available) {
            sb.append("诊断不可用（provider=").append(provider)
                    .append("）。不要据此判断编译已通过；如条件允许，请运行项目自带的构建或测试命令确认。");
            return sb.toString();
        }
        if (analyzedFiles <= 0) {
            return sb.toString() + "本批变更没有可分析的文件。";
        }
        if (diagnostics.isEmpty()) {
            sb.append("已分析 ").append(analyzedFiles).append(" 个变更文件，未发现编译错误。");
            return sb.toString();
        }
        sb.append("以下为本批变更文件的 ")
                .append(diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count())
                .append(" 个错误 / ").append(diagnostics.size()).append(" 条诊断（已限量）：\n");
        for (Diagnostic d : diagnostics) {
            sb.append("- ").append(d.file()).append(':').append(d.line()).append(':').append(d.column())
                    .append(' ').append(d.severity().name().toLowerCase())
                    .append(": ").append(d.message()).append('\n');
        }
        sb.append("（诊断由 ").append(provider).append(" 提供")
                .append(truncated ? "；结果已截断，仅显示前 " + diagnostics.size() + " 条" : "")
                .append("。修复后请再次修改文件以刷新诊断。）");
        return sb.toString();
    }
}
