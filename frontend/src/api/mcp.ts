// 第五十二批：MCP 健康状态接口
// 本批次扩展：增加全局开关接口

import http, { unwrap } from './http';
import type { ApiResponse } from '../types/api';

export interface McpServerStatus {
  status: 'UP' | 'DOWN' | 'UNKNOWN';
  consecutiveFailures: number;
  lastCheckAt?: string;
  lastSuccessAt?: string;
  lastFailureAt?: string;
  lastError?: string;
}

export interface McpStatusResponse {
  enabled: boolean;
  servers: Record<string, McpServerStatus>;
  timestamp: string;
}

export async function getMcpStatus(): Promise<McpStatusResponse> {
  return http.get<never, ApiResponse<McpStatusResponse>>('/api/mcp/status').then(unwrap);
}

/** 全局开关：关掉后所有 MCP server 不注入工具链 */
export async function setMcpEnabled(enabled: boolean): Promise<void> {
  return http.put<never, ApiResponse<void>>('/api/mcp/enabled', { enabled }).then(unwrap);
}

// ===== M1：用户自助 MCP Server（仅桌面端） =====

export interface McpServerConfig {
  id: number;
  name: string;
  command: string;
  args: string[];
  /** 只返回变量名；值永远不会回显 */
  envKeys: string[];
  enabled: boolean;
  connected: boolean;
  toolCount: number;
  lastError?: string;
  /** 来自市场安装时标记的插件 id / 版本；手填则为空 */
  pluginId?: string;
  pluginVersion?: string;
}

export interface McpServerInput {
  name: string;
  command: string;
  args: string[];
  /** null = 编辑时保持原环境变量不变；空对象 = 清空 */
  env?: Record<string, string> | null;
  enabled: boolean;
}

/** 工具清单预览项：远端工具原名 + 描述 */
export interface McpToolInfo {
  name: string;
  description: string;
}

export async function listMcpServers(): Promise<McpServerConfig[]> {
  return http.get<never, ApiResponse<McpServerConfig[]>>('/api/mcp/servers').then(unwrap);
}

export async function createMcpServer(input: McpServerInput): Promise<McpServerConfig> {
  return http.post<never, ApiResponse<McpServerConfig>>('/api/mcp/servers', input).then(unwrap);
}

export async function updateMcpServer(id: number, input: McpServerInput): Promise<McpServerConfig> {
  return http.put<never, ApiResponse<McpServerConfig>>(`/api/mcp/servers/${id}`, input).then(unwrap);
}

export async function setMcpServerEnabled(id: number, enabled: boolean): Promise<McpServerConfig> {
  return http.put<never, ApiResponse<McpServerConfig>>(`/api/mcp/servers/${id}/enabled`, { enabled })
    .then(unwrap);
}

export async function reloadMcpServer(id: number): Promise<McpServerConfig> {
  return http.post<never, ApiResponse<McpServerConfig>>(`/api/mcp/servers/${id}/reload`).then(unwrap);
}

/** 工具清单预览：当前注册到工具链的远端工具名与描述 */
export async function getMcpServerTools(id: number): Promise<McpToolInfo[]> {
  return http.get<never, ApiResponse<McpToolInfo[]>>(`/api/mcp/servers/${id}/tools`).then(unwrap);
}

export async function deleteMcpServer(id: number): Promise<void> {
  return http.delete<never, ApiResponse<void>>(`/api/mcp/servers/${id}`).then(unwrap);
}
