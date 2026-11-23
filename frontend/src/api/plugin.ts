// 0.1.4 插件中心：市场目录查询 + 一键安装（仅桌面端）

import http, { unwrap } from './http';
import type { ApiResponse } from '../types/api';
import type { Catalog } from '../types/plugin';
import type { McpServerConfig } from './mcp';

/** 获取插件市场目录（后端 classpath 内嵌 marketplace.json） */
export async function fetchCatalog(): Promise<Catalog> {
  return http.get<never, ApiResponse<Catalog>>('/api/plugins/catalog').then(unwrap);
}

/** 一键安装市场插件：返回创建后的 MCP server 配置 */
export async function installPlugin(
  pluginId: string,
  credentials: Record<string, string>,
): Promise<McpServerConfig> {
  return http
    .post<never, ApiResponse<McpServerConfig>>('/api/plugins/install', { pluginId, credentials })
    .then(unwrap);
}
