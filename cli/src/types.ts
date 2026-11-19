/**
 * 手写请求/响应类型，字段以后端 DTO 为准（P6-01 未做 schema 级生成）。
 * 对应的后端落点写在每个类型上方，字段漂移由 e2e 兜底。
 */

/** dto/ApiResponse.java：所有 JSON 端点统一信封（/api/health 除外） */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

/** controller/HealthController.java：不走信封，直接返回字段 */
export interface HealthInfo {
  status: string
  version: string
  serverVersion: string
  apiContractVersion: number
  uptimeMs: number
  startedAt: string
  timestamp: string
}

/** dto/ApiClientView.java：永不含 secret */
export interface ApiClientView {
  clientId: string
  name: string
  scopes: string[]
  createdAt: string
  lastUsedAt: string | null
  revoked: boolean
}

/** ApiClientController#create 的 data */
export interface CreateClientResult {
  token: string
  client: ApiClientView
}

/** AgentChatController#runEvents：events[].type 是大写枚举 */
export interface RunEventItem {
  seq: number
  type: string
  payload: string | null
  createTime: string
}

export interface RunEventsData {
  runId: number
  state: string
  errorCode: string | null
  errorMessage: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  events: RunEventItem[]
}

/** ChangeSetService.ChangeView */
export interface ChangeView {
  id: number
  runId: number
  turnId: string | null
  filePath: string
  action: string
  source: string
  additions: number | null
  deletions: number | null
  diffText: string | null
  status: string
  createTime: string
}

/** ChangeSetService.Totals */
export interface ChangeSetTotals {
  files: number
  additions: number
  deletions: number
  pending: number
  accepted: number
  reverted: number
}

/** ChangeSetService.ChangeSetView（只声明 CLI 消费的字段，其余保留原样透传） */
export interface ChangeSetView {
  sessionId: number
  projectId: number | null
  runFilter: number | null
  status: string
  statusLabel: string
  accepted: boolean
  totals: ChangeSetTotals
  changes: ChangeView[]
  continuePrompt: string | null
  [key: string]: unknown
}

/** ChangeSetService.RevertResult */
export interface RevertResult {
  ok: boolean
  count: number
  failed: number
  skippedAccepted: number
  conflicts: Array<{ id: number; filePath: string; reason: string }>
}

/** SSE 事件（run 流） */
export interface ApprovalPreview {
  kind?: string
  path?: string
  command?: string
  diff?: string
  additions?: number
  deletions?: number
  newFile?: boolean
  note?: string
}

export interface ApprovalRequired {
  approvalId: string
  tool: string
  preview: ApprovalPreview | null
  risk: string
  expiresAt: string | number | null
}

/** 公开 API 契约版本，必须与 HealthController.API_CONTRACT_VERSION 一致 */
export const EXPECTED_API_CONTRACT_VERSION = 1
