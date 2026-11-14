package com.capsule.agentkey.verify;

import java.util.List;

/**
 * P5-06：项目级验证配置，对应工作区 `.lunargold/verify.json`。
 *
 * <p>所有字段都可缺省；缺省值在紧凑构造器里补全，避免模型或用户只写命令列表时
 * 因 Jackson 把原始类型填成 false/0 而误判为关闭或 0 次重试。
 */
public record VerifyConfig(Boolean enabled, Integer maxRetries, List<VerifyCommand> commands) {

    public VerifyConfig {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        maxRetries = maxRetries == null ? 2 : Math.max(0, Math.min(2, maxRetries));
        commands = commands == null ? List.of() : List.copyOf(commands);
    }

    /** enabled 缺省为 true；只有显式 false 才关闭 */
    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    /** 已裁剪到 0..2 的重试次数 */
    public int maxRetriesValue() {
        return maxRetries == null ? 2 : maxRetries;
    }

    /** 单条验证命令 */
    public record VerifyCommand(String name, String command, String cwd,
                                Integer timeoutSeconds, Boolean required) {

        public VerifyCommand {
            name = name == null ? "" : name.trim();
            command = command == null ? "" : command.trim();
            cwd = cwd == null ? "" : cwd.trim();
            required = required == null ? Boolean.TRUE : required;
        }

        /** required 缺省为 true */
        public boolean isRequired() {
            return !Boolean.FALSE.equals(required);
        }
    }
}
