import http from './http';
import type { PendingFileChange } from '../types/api';

/** 拉项目 PENDING 变更 */
export function fetchPendingChanges(projectId: number): Promise<PendingFileChange[]> {
  return http.get('/api/file-changes', { params: { projectId, status: 'PENDING' } });
}

/** 单条撤销 */
export function revertChange(id: number): Promise<{ ok: boolean; error?: string }> {
  return http.post(`/api/file-changes/${id}/revert`);
}

/** 单条保留 */
export function acceptChange(id: number): Promise<{ ok: boolean }> {
  return http.post(`/api/file-changes/${id}/accept`);
}

/** 批量全部撤销 */
export function revertAll(projectId: number): Promise<{ ok: boolean; count: number; failed: number }> {
  return http.post('/api/file-changes/revert-all', null, { params: { projectId } });
}

/** 第六十八批 A3：撤回某一轮的全部改动 */
export function revertTurn(
  sessionId: number,
  turnId: string
): Promise<{ ok: boolean; count: number; failed: number }> {
  return http.post('/api/file-changes/revert-turn', null, { params: { sessionId, turnId } });
}

/** 批量全部保留 */
export function acceptAll(projectId: number): Promise<{ ok: boolean; count: number }> {
  return http.post('/api/file-changes/accept-all', null, { params: { projectId } });
}
