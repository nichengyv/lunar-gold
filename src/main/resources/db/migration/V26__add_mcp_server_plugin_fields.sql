-- 0.1.4 插件中心：标记从市场安装的 MCP server 的来源插件与版本。
-- 手动添加的 server 这两列为 NULL；plugin_id 用于来源标识，plugin_version 用于更新检查。
ALTER TABLE mcp_server ADD COLUMN plugin_id VARCHAR(128);
ALTER TABLE mcp_server ADD COLUMN plugin_version VARCHAR(32);
