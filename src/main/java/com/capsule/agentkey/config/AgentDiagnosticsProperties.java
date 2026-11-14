package com.capsule.agentkey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P5-05：变更文件诊断配置。
 *
 * <p>嵌套结构统一由 Binder 绑定，禁止用 {@code @Value} 读取 {@code lsp.*}。
 */
@Component
@ConfigurationProperties(prefix = "agent.diagnostics")
public class AgentDiagnosticsProperties {

    /** 总开关；关闭后不采集、不注入、不下发诊断事件 */
    private boolean enabled = true;

    /** javac / lsp / none；未知值回落 javac */
    private String provider = "javac";

    /** 进入下一轮上下文的最大诊断条数 */
    private int maxItems = 20;

    /** 诊断文本最大字符数 */
    private int maxChars = 8192;

    /** 单次采集超时（毫秒）；javac 首次冷启动可能较慢，默认留足余量 */
    private long timeoutMs = 20_000;

    /** 单个批次最多分析的文件数 */
    private int maxFiles = 5;

    private Lsp lsp = new Lsp();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public int getMaxItems() { return maxItems; }
    public void setMaxItems(int maxItems) { this.maxItems = maxItems; }
    public int getMaxChars() { return maxChars; }
    public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
    public Lsp getLsp() { return lsp; }
    public void setLsp(Lsp lsp) { this.lsp = lsp; }

    public static class Lsp {
        /** 语言服务器启动命令；为空表示未配置，provider 不可用 */
        private String command = "";
        /** 启动参数，按空白拆分 */
        private String args = "";
        /** didOpen 使用的 languageId */
        private String languageId = "java";

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public String getArgs() { return args; }
        public void setArgs(String args) { this.args = args; }
        public String getLanguageId() { return languageId; }
        public void setLanguageId(String languageId) { this.languageId = languageId; }
    }
}
