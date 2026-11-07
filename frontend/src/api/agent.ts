import http, { unwrap } from './http';
import type { ApiResponse, HistoryMessage, MessageSearchHit, SessionInfo, SessionStats } from '../types/api';

export function listSessions(projectId?: number): Promise<SessionInfo[]> {
  return http
    .get<never, ApiResponse<SessionInfo[]>>('/api/agent/sessions', {
      params: projectId ? { projectId } : undefined
    })
    .then(unwrap);
}

export function getSessionMessages(sessionId: number): Promise<HistoryMessage[]> {
  return http.get<never, ApiResponse<HistoryMessage[]>>(`/api/agent/sessions/${sessionId}/messages`).then(unwrap);
}

/** 消息全文搜索：按会话归并返回正文命中片段 */
export function searchMessages(keyword: string): Promise<MessageSearchHit[]> {
  return http
    .get<never, ApiResponse<MessageSearchHit[]>>('/api/agent/messages/search', { params: { keyword } })
    .then(unwrap);
}

/** 删除会话 */
export function deleteSession(sessionId: number): Promise<void> {
  return http.delete<never, ApiResponse<void>>(`/api/agent/sessions/${sessionId}`).then(unwrap);
}

/** 通知后端停止生成：让 ReAct 循环和运行中的终端命令及时中止 */
export function stopGeneration(sessionId: number): Promise<void> {
  return http
    .post<never, ApiResponse<void>>('/api/agent/chat/stop', null, { params: { sessionId } })
    .then(unwrap);
}

export function renameSession(sessionId: number, title: string): Promise<void> {
  return http.patch<never, ApiResponse<void>>(`/api/agent/sessions/${sessionId}`, { title }).then(unwrap);
}

/** 修改当前会话的工作权限：立即落库，切走再切回不会丢 */
export function updateSessionPermission(sessionId: number, permission: string): Promise<void> {
  return http
    .patch<never, ApiResponse<void>>(`/api/agent/sessions/${sessionId}/permission`, { permission })
    .then(unwrap);
}

/** 聊天附件上传（multipart，让浏览器自动设置 Content-Type） */
export function uploadChatAttachment(formData: FormData): Promise<{ id: number; fileName: string }> {
  return http.post<never, ApiResponse<{ id: number; fileName: string }>>('/api/agent/chat/attachment', formData).then(unwrap);
}

/** 第五十四批：前端侧边栏"会话追踪"面板的聚合统计接口 */
export function getSessionStats(sessionId: number): Promise<SessionStats> {
  return http.get<never, ApiResponse<SessionStats>>(`/api/agent/sessions/${sessionId}/stats`).then(unwrap);
}

/** D3 可观测性：全局累计的 Agent 工具调用统计（MeterRegistry Timer 聚合） */
export function getMetrics(): Promise<any> {
  return http.get<never, ApiResponse<any>>('/api/agent/metrics').then(unwrap);
}

/** 第六十八批 A2：提交危险工具调用的审批结果 */
export function respondApproval(
  approvalId: string,
  decision: 'APPROVE' | 'REJECT',
  remember: 'NONE' | 'SESSION' = 'NONE'
): Promise<void> {
  return http
    .post<never, ApiResponse<void>>(`/api/agent/approvals/${approvalId}`, { decision, remember })
    .then(unwrap);
}

// ========== 第六十五批：Agent Rules 指令库 ==========

export interface AgentRulePayload {
  id?: number;
  name: string;
  description?: string;
  content: string;
  scope: 'GLOBAL' | 'PROJECT' | 'SESSION';
  userId?: number;
  projectId?: number;
  sessionId?: number;
  enabled?: boolean;
  builtin?: boolean;
}

export interface AgentRuleParams {
  userId?: number;
  projectId?: number;
  sessionId?: number;
}

export interface AgentRule {
  id: number;
  name: string;
  description?: string;
  content: string;
  scope: 'GLOBAL' | 'PROJECT' | 'SESSION';
  userId?: number;
  projectId?: number;
  sessionId?: number;
  enabled: boolean;
  builtin: boolean;
  createdAt?: string;
  updatedAt?: string;
}

/** 按 scope 参数拉规则列表；三个参数三选一 */
export function getRules(params: AgentRuleParams): Promise<AgentRule[]> {
  return http.get<never, ApiResponse<AgentRule[]>>('/api/agent/rules', { params }).then(unwrap);
}

/** 新建规则：scope 由 body 显式传，或由 userId/projectId/sessionId 哪个非空自动推断 */
export function createRule(body: AgentRulePayload): Promise<AgentRule> {
  return http.post<never, ApiResponse<AgentRule>>('/api/agent/rules', body).then(unwrap);
}

/** 修改规则：builtin 规则只允许改 enabled 字段 */
export function updateRule(id: number, body: Partial<AgentRulePayload>): Promise<AgentRule> {
  return http.put<never, ApiResponse<AgentRule>>(`/api/agent/rules/${id}`, body).then(unwrap);
}

/** 切换开关：PATCH /{id}/toggle */
export function toggleRule(id: number, enabled: boolean): Promise<AgentRule> {
  return http.patch<never, ApiResponse<AgentRule>>(`/api/agent/rules/${id}/toggle`, { enabled }).then(unwrap);
}

/** 删除规则：builtin 规则返回 403 */
export function deleteRule(id: number): Promise<void> {
  return http.delete<never, ApiResponse<void>>(`/api/agent/rules/${id}`).then(unwrap);
}
