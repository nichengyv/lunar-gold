package com.capsule.agentkey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P5-06：项目验证回路配置。
 *
 * <p>验证命令本身来自工作区 `.lunargold/verify.json`；这里只放全局开关、
 * 配置路径、重试上限与输出/超时兜底，便于不同机器调整而不用改项目文件。
 */
@Component
@ConfigurationProperties(prefix = "agent.verify")
public class AgentVerifyProperties {

    /** 总开关；关闭后不读取配置、不执行验证，完成状态按未配置处理 */
    private boolean enabled = true;

    /** 验证配置相对工作区根的路径 */
    private String configPath = ".lunargold/verify.json";

    /** 全局重试上限，与项目配置取较小值；需求上限为 2 */
    private int maxRetries = 2;

    /** 单条命令回灌模型与展示的输出尾部字符数 */
    private int maxOutputChars = 4000;

    /** 项目配置未写 timeoutSeconds 时的单条命令超时（秒） */
    private int defaultTimeoutSeconds = 120;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getConfigPath() { return configPath; }
    public void setConfigPath(String configPath) { this.configPath = configPath; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getMaxOutputChars() { return maxOutputChars; }
    public void setMaxOutputChars(int maxOutputChars) { this.maxOutputChars = maxOutputChars; }
    public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }
}
