/** 插件实际调用的端点；client 端点必须存在于 P6-01 公开子集。 */
export interface ExtensionEndpoint {
  method: 'GET' | 'POST' | 'DELETE'
  path: string
  feature: string
  tokenKind: 'client' | 'local'
}

export const EXTENSION_ENDPOINTS: ExtensionEndpoint[] = [
  { method: 'GET', path: '/api/health', feature: 'connect', tokenKind: 'client' },
  { method: 'POST', path: '/api/clients', feature: 'connect', tokenKind: 'local' },
  { method: 'GET', path: '/api/clients/self', feature: 'connect', tokenKind: 'client' },
  { method: 'DELETE', path: '/api/clients/self', feature: 'disconnect', tokenKind: 'client' },
  { method: 'GET', path: '/api/projects', feature: 'newTask', tokenKind: 'client' },
  { method: 'GET', path: '/api/agent/sessions', feature: 'newTask', tokenKind: 'client' },
  { method: 'POST', path: '/api/agent/chat/stream', feature: 'newTask', tokenKind: 'client' },
  { method: 'POST', path: '/api/agent/chat/stop', feature: 'stop', tokenKind: 'client' },
  { method: 'GET', path: '/api/agent/runs/{runId}/events', feature: 'resume', tokenKind: 'client' },
  { method: 'GET', path: '/api/file-changes/changeset', feature: 'changes', tokenKind: 'client' },
  { method: 'POST', path: '/api/file-changes/revert-run', feature: 'revertRun', tokenKind: 'client' },
  { method: 'POST', path: '/api/file-changes/revert-session', feature: 'revertSession', tokenKind: 'client' },
  { method: 'POST', path: '/api/agent/approvals/{approvalId}', feature: 'approval', tokenKind: 'client' },
  { method: 'POST', path: '/api/agent/approvals/batch', feature: 'approval', tokenKind: 'client' },
  { method: 'GET', path: '/api/agent/sessions/{sessionId}/approvals', feature: 'approvalRestore', tokenKind: 'client' }
]
