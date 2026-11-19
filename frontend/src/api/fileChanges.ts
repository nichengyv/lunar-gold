import http from './http';
import type { ChangeSetView, PendingFileChange, RevertResult } from '../types/api';

/** 拉项目 PENDING 变更 */
export function fetchPendingChanges(projectId: number): Promise<PendingFileChange[]> {
  return http.get('/api/file-changes', { params: { projectId, status: 'PENDING' } });
}

/** 单条撤销；响应结构与批量撤回统一为 RevertResult */
export function revertChange(id: number): Promise<RevertResult> {
  return http.post(`/api/file-changes/${id}/revert`);
}

/** 单条保留 */
export function acceptChange(id: number): Promise<{ ok: boolean }> {
  return http.post(`/api/file-changes/${id}/accept`);
}

/** 批量全部撤销 */
export function revertAll(projectId: number): Promise<RevertResult> {
  return http.post('/api/file-changes/revert-all', null, { params: { projectId } });
}

/** 第六十八批 A3：撤回某一轮的全部改动 */
export function revertTurn(sessionId: number, turnId: string): Promise<RevertResult> {
  return http.post('/api/file-changes/revert-turn', null, { params: { sessionId, turnId } });
}

/** 批量全部保留 */
export function acceptAll(projectId: number): Promise<{ ok: boolean; count: number }> {
  return http.post('/api/file-changes/accept-all', null, { params: { projectId } });
}

/** P5-14：任务级交付视图；runId 可选，用于按单个 Run 过滤 */
export function fetchChangeSet(sessionId: number, runId?: number): Promise<ChangeSetView> {
  return http.get('/api/file-changes/changeset', { params: { sessionId, runId } });
}

/** P5-14：整体撤回（按逆序回滚，外部修改返回冲突） */
export function revertSession(sessionId: number): Promise<RevertResult> {
  return http.post('/api/file-changes/revert-session', null, { params: { sessionId } });
}

/** P5-14：按 Run 撤回 */
export function revertRun(sessionId: number, runId: number): Promise<RevertResult> {
  return http.post('/api/file-changes/revert-run', null, { params: { sessionId, runId } });
}
