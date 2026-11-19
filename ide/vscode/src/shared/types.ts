/** 手写 DTO，字段与后端保持一致；P6-01 未提供 schema 生成。 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

export interface HealthInfo {
  status: string
  version: string
  serverVersion: string
  apiContractVersion: number
  uptimeMs: number
  startedAt: string
  timestamp: string
}

export interface ApiClientView {
  clientId: string
  name: string
  scopes: string[]
  createdAt: string
  lastUsedAt: string | null
  revoked: boolean
}

export interface CreateClientResult {
  token: string
  client: ApiClientView
}

export interface ProjectSummary {
  id: number
  name: string
  description: string | null
  defaultFlag: boolean
  instruction: string | null
  workspacePath: string | null
  defaultPermission: string | null
  createTime: string
}

export interface SessionSummary {
  id: number
  projectId: number
  title: string
  model: string | null
  reasoning: string | null
  permission: string | null
  createTime: string
  updateTime: string
  lastRunState: string | null
}

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

export interface ChangeView {
  id: number
  runId: number | null
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

export interface ChangeSetTotals {
  files: number
  additions: number
  deletions: number
  pending: number
  accepted: number
  reverted: number
}

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

export interface RevertResult {
  ok: boolean
  count: number
  failed: number
  skippedAccepted: number
  conflicts: Array<{ id: number; filePath: string | null; reason: string }>
}

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

export interface ApprovalHistoryItem {
  approvalId: string
  tool: string
  risk: string
  state: string
  decisionSource: string | null
  decisionTime: string | null
  createTime: string
  rememberSession: boolean
}

export interface CompletionView {
  status?: string
  reason?: string
  message?: string
  filesChanged?: boolean
}

export const EXPECTED_API_CONTRACT_VERSION = 1
export const ALL_CLIENT_SCOPES = [
  'chat:write',
  'run:read',
  'changes:read',
  'changes:write',
  'approval:write',
  'project:read',
  'client:admin'
] as const
