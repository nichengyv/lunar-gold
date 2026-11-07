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
