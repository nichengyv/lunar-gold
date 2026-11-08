import http from './http';
import type { MetaInfo } from '../types/api';

/** 部署元信息：公开接口，返回裸对象而不是 ApiResponse 信封 */
export function getMeta(): Promise<MetaInfo> {
  // 启动路径会等这个请求，超时不宜过长；失败时 store 会退化为「不隐藏入口」
  return http.get<never, MetaInfo>('/api/meta', { timeout: 8000 });
}
