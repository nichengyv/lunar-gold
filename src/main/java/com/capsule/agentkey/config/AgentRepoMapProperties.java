package com.capsule.agentkey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P5-04 前置：注入 system prompt 的仓库结构摘要（repo map）配置。
 *
 * <p>目录树 + 关键符号共用一份字符预算，避免大型仓库把上下文撑爆。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent.repo-map")
public class AgentRepoMapProperties {

    /** 总开关；关闭后沿用空字符串，不注入仓库结构 */
    private boolean enabled = true;

    /** 递归扫描的最大目录深度；Java 源码常见的 src/main/java/com/... 需要 7 层左右 */
    private int maxDepth = 8;

    /** 单次扫描的最大文件数；超过后标记截断 */
    private int maxFiles = 500;

    /** repo map 文本的字符预算（包含截断提示） */
    private int maxChars = 6000;

    /** 单个文件最多展示的符号数 */
    private int maxSymbolsPerFile = 6;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
    public int getMaxChars() { return maxChars; }
    public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
    public int getMaxSymbolsPerFile() { return maxSymbolsPerFile; }
    public void setMaxSymbolsPerFile(int maxSymbolsPerFile) { this.maxSymbolsPerFile = maxSymbolsPerFile; }
}
