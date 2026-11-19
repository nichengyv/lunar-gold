import http, { unwrap } from './http';
import type { ApiResponse } from '../types/api';

// ========== 设置中心相关接口 ==========

export interface ChatDefaults {
  model: string;
  reasoning: 'FAST' | 'STANDARD' | 'DEEP';
  permission: 'MANUAL' | 'AUTO' | 'FULL' | 'READ_ONLY';
}

/** 全局默认对话参数（新会话启动时用） */
export function getDefaults(): Promise<ChatDefaults> {
  return http.get<never, ApiResponse<ChatDefaults>>('/api/settings/defaults').then(unwrap);
}

export function updateDefaults(body: ChatDefaults): Promise<ChatDefaults> {
  return http.put<never, ApiResponse<ChatDefaults>>('/api/settings/defaults', body).then(unwrap);
}

export interface ModelInfo {
  id: string;
  name: string;
  provider: string;
  /** 是否当前可用（有配置好的 API Key） */
  available: boolean;
}

/** 可切换的模型列表：后端优先用已存 Key 实时拉取 provider 实际可用模型，失败回退静态目录 */
export function listModels(): Promise<ModelInfo[]> {
  return http.get<never, ApiResponse<ModelInfo[]>>('/api/settings/models').then(unwrap);
}

export interface HealthInfo {
  status: 'UP' | 'DOWN';
  version: string;
  uptimeMs: number;
}

/** 后端健康检查 + 版本信息 */
export function getHealth(): Promise<HealthInfo> {
  return http.get<never, ApiResponse<HealthInfo>>('/api/health').then(unwrap);
}
