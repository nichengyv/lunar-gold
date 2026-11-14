package com.capsule.agentkey.diagnostics;

/**
 * 一条文件诊断。
 *
 * @param severity 严重级别
 * @param file     相对工作区根的路径，统一使用 '/'
 * @param line     行号，从 1 开始
 * @param column   列号，从 1 开始
 * @param message  诊断信息
 * @param source   产生诊断的 provider（javac / lsp）
 */
public record Diagnostic(Severity severity, String file, int line, int column,
                         String message, String source) {

    public enum Severity { ERROR, WARNING, INFO }
}
