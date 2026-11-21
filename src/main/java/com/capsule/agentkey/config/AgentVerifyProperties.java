package com.capsule.agentkey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P5-06：项目验证回路配置。
 *
 * <p>验证命令本身来自工作区 `.lunargold/verify.json`；这里只放全局开关、
 * 配置路径、重试上限与输出/超时兜底，便于不同机器调整而不用改项目文件。
 *
 * <p>R7c L5 起，缺少 verify.json 时会按构建文件（pom.xml / build.gradle / package.json /
 * go.mod / Cargo.toml / tsconfig.json）自动探测一条验证命令，避免「未配置 → 只能模型自报」。
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

    /** R7c L5：没有 verify.json 时按构建文件自动探测验证命令 */
    private boolean autoDetect = true;

    /** 自动探测命令的超时（秒）；编译类命令通常比普通命令更久 */
    private int autoDetectTimeoutSeconds = 300;

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
    public boolean isAutoDetect() { return autoDetect; }
    public void setAutoDetect(boolean autoDetect) { this.autoDetect = autoDetect; }
    public int getAutoDetectTimeoutSeconds() { return autoDetectTimeoutSeconds; }
    public void setAutoDetectTimeoutSeconds(int autoDetectTimeoutSeconds) {
        this.autoDetectTimeoutSeconds = autoDetectTimeoutSeconds;
    }
}
