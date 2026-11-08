import http, { unwrap } from './http';
import type { ApiResponse } from '../types/api';

/**
 * 桌面端本地令牌引导：仅 desktop 模式存在该接口，
 * 服务端只接受回环地址 + CORS 白名单来源。
 */
export function getDesktopToken(): Promise<string> {
  return http
    .get<never, ApiResponse<{ token: string }>>('/api/desktop/token')
    .then((resp) => unwrap(resp).token);
}
