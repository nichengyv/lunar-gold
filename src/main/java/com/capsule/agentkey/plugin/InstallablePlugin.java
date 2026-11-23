package com.capsule.agentkey.plugin;

import java.util.List;
import java.util.Map;

/**
 * 可安装插件的运行时抽象。0.1.4 先有 {@link McpInstaller} 一个实现；
 * 未来加指令模板/模型预设时新增实现，不改现有代码。
 */
public interface InstallablePlugin<R> {

    /** 插件元数据（来自 catalog） */
    PluginManifest getManifest();

    /** 返回安装表单需要用户填的凭证字段，驱动前端 schema-driven 渲染 */
    List<CredentialField> getConfigSchema();

    /**
     * 安装：用用户填的凭证生成完整配置并落库、启动。
     *
     * @param userId    当前用户
     * @param userInput 用户填写的凭证值（key=字段名，value=用户输入）
     * @return 安装结果（对 MCP 即 McpServerResponse，含实例 id）
     */
    R install(Long userId, Map<String, String> userInput);

    /** 卸载：停用并删除配置 */
    void uninstall(Long userId, Long instanceId);
}
