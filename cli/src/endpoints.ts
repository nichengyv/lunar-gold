/**
 * CLI 实际调用到的端点清单。
 *
 * 这份清单是「只用公开子集」的静态防线：测试会把它与
 * src/test/resources/contract/public-api.json 比对，出现清单外调用即失败。
 * 新增调用必须先走 P6-01 的「注解 + 清单 + 快照」流程。
 */
export interface CliEndpoint {
  method: 'GET' | 'POST' | 'DELETE'
  path: string
  command: string
  /**
   * client：走客户端令牌，必须出现在公开子集清单里；
   * local：只允许本地用户令牌（client create 换令牌），故意不在公开子集。
   */
  tokenKind: 'client' | 'local'
}

export const CLI_ENDPOINTS: CliEndpoint[] = [
  { method: 'POST', path: '/api/agent/chat/stream', command: 'run', tokenKind: 'client' },
  { method: 'POST', path: '/api/agent/chat/stop', command: 'stop', tokenKind: 'client' },
  { method: 'GET', path: '/api/agent/runs/{runId}/events', command: 'watch', tokenKind: 'client' },
  { method: 'GET', path: '/api/file-changes/changeset', command: 'changes', tokenKind: 'client' },
  { method: 'POST', path: '/api/file-changes/revert-run', command: 'revert', tokenKind: 'client' },
  { method: 'POST', path: '/api/file-changes/revert-session', command: 'revert', tokenKind: 'client' },
  { method: 'POST', path: '/api/agent/approvals/{approvalId}', command: 'approve', tokenKind: 'client' },
  { method: 'POST', path: '/api/clients', command: 'client', tokenKind: 'local' },
  { method: 'GET', path: '/api/clients/self', command: 'client', tokenKind: 'client' },
  { method: 'DELETE', path: '/api/clients/self', command: 'client', tokenKind: 'client' },
  { method: 'GET', path: '/api/health', command: 'doctor', tokenKind: 'client' }
]

/** 命令 → 端点 的反查，测试与 --help 共用 */
export const COMMAND_ENDPOINTS: Record<string, string[]> = {
  run: ['POST /api/agent/chat/stream'],
  watch: ['GET /api/agent/runs/{runId}/events'],
  changes: ['GET /api/file-changes/changeset'],
  approve: ['POST /api/agent/approvals/{approvalId}'],
  revert: ['POST /api/file-changes/revert-run', 'POST /api/file-changes/revert-session'],
  stop: ['POST /api/agent/chat/stop'],
  client: ['POST /api/clients', 'GET /api/clients/self', 'DELETE /api/clients/self'],
  doctor: ['GET /api/health']
}
