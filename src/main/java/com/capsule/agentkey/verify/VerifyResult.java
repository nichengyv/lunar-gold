package com.capsule.agentkey.verify;

import java.util.List;

/**
 * P5-06：一次项目验证的汇总结果。
 *
 * <p>状态必须区分「未配置」「环境不可用」「命令失败」和「通过」：只有 {@link Status#PASSED}
 * 才能作为完成证据；其余状态都要在用户界面与模型上下文里如实呈现。
 *
 * @param status            验证状态
 * @param configPath        验证配置相对路径
 * @param configured        是否成功加载到有效命令列表
 * @param attempt           本次是当前 run 的第几次验证（从 1 开始）
 * @param maxAttempts       本次 run 允许的最大验证次数
 * @param commands           各命令执行结果
 * @param failureFingerprint 失败指纹；非失败状态为空串
 * @param message           面向用户的简短结论
 */
public record VerifyResult(Status status, String configPath, boolean configured,
                           int attempt, int maxAttempts, List<CommandOutcome> commands,
                           String failureFingerprint, String message) {

    public enum Status { PASSED, FAILED, NOT_CONFIGURED, UNAVAILABLE }

    public record CommandOutcome(String name, String command, String cwd, boolean required,
                                 int exitCode, boolean timedOut, long durationMs,
                                 boolean skipped, String outputTail) {
    }

    public VerifyResult {
        status = status == null ? Status.NOT_CONFIGURED : status;
        configPath = configPath == null ? "" : configPath;
        commands = commands == null ? List.of() : List.copyOf(commands);
        failureFingerprint = failureFingerprint == null ? "" : failureFingerprint;
        message = message == null ? "" : message;
    }

    public boolean passed() {
        return status == Status.PASSED;
    }

    public boolean failed() {
        return status == Status.FAILED;
    }

    /** 供下一轮模型上下文使用的紧凑文本；明确禁止把未验证说成通过 */
    public String formatForPrompt() {
        StringBuilder sb = new StringBuilder("【验证结果】\n");
        switch (status) {
            case PASSED -> sb.append("验证命令全部通过（第 ").append(attempt).append('/')
                    .append(maxAttempts).append(" 次）。不要重复执行验证，直接总结结果。");
            case FAILED -> {
                sb.append("验证失败（第 ").append(attempt).append('/').append(maxAttempts)
                        .append(" 次）。\n");
                for (CommandOutcome command : commands) {
                    if (command.skipped() || (command.exitCode() == 0 && !command.timedOut())) {
                        continue;
                    }
                    sb.append("- ").append(command.name().isBlank() ? command.command() : command.name())
                            .append(command.required() ? "（必跑）" : "（可选）")
                            .append(" exit=").append(command.exitCode())
                            .append(command.timedOut() ? " 超时" : "")
                            .append(" 耗时=").append(command.durationMs()).append("ms\n");
                    if (command.outputTail() != null && !command.outputTail().isBlank()) {
                        sb.append(command.outputTail()).append('\n');
                    }
                }
                sb.append("请修复上述失败后再次修改文件；验证通过前不要声称任务已完成。");
            }
            case NOT_CONFIGURED -> sb.append("项目未配置验证命令（").append(configPath)
                    .append("）。不要声称验证已通过；如条件允许，请向用户说明需要配置验证或人工审查变更。");
            case UNAVAILABLE -> sb.append("验证无法执行：").append(message)
                    .append("。不要声称验证已通过；请修复环境或改用可用的验证方式。");
        }
        return sb.toString();
    }
}
