package com.capsule.agentkey.plugin;

/**
 * 插件类型。0.1.4 首批全部为 {@link #MCP}；预留指令模板/模型预设，未来新增实现类不改现有代码。
 */
public enum PluginType {
    MCP,
    PROMPT_TEMPLATE,
    MODEL_PRESET
}
