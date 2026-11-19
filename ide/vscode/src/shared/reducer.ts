import type { ApprovalRequired, CompletionView, RunEventItem } from './types.ts'

export interface UsageView {
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
}

export type PanelEvent =
  | { type: 'session'; sessionId: number | null }
  | { type: 'run'; runId: number | null }
  | { type: 'turn'; turnId: string }
  | { type: 'content'; text: string }
  | { type: 'tool'; phase: 'call'; tool: string; args: string }
  | { type: 'tool'; phase: 'result'; tool: string; success: boolean; costMs: number | null; error: string | null }
  | { type: 'approval'; approval: ApprovalRequired }
  | { type: 'approval_resolved'; approvalId: string | null; decision: string }
  | { type: 'file_change'; path: string; additions: number; deletions: number }
  | { type: 'done'; completion: CompletionView | null }
  | { type: 'error'; message: string }

export interface PanelState {
  sessionId: number | null
  runId: number | null
  turnId: string | null
  contentText: string
  contentLength: number
  toolCalls: number
  toolResults: number
  fileChanges: number
  pendingApproval: ApprovalRequired | null
  completion: CompletionView | null
  usage: UsageView | null
  error: string | null
  done: boolean
  lastSeq: number
  /** SSE 已消费离散事件指纹及次数，供落库回放去重。 */
  seen: Map<string, number>
}

export function initialPanelState(): PanelState {
  return {
    sessionId: null,
    runId: null,
    turnId: null,
    contentText: '',
    contentLength: 0,
    toolCalls: 0,
    toolResults: 0,
    fileChanges: 0,
    pendingApproval: null,
    completion: null,
    usage: null,
    error: null,
    done: false,
    lastSeq: 0,
    seen: new Map<string, number>()
  }
}

/** SSE 与落库回放共用归约；每次调用只返回本次新增的面板事件。 */
export class PanelReducer {
  readonly state: PanelState = initialPanelState()

  handleSse(type: string, data: string): PanelEvent[] {
    switch (type) {
      case 'session_id': {
        const id = Number.parseInt(data, 10)
        this.state.sessionId = Number.isNaN(id) ? null : id
        return [{ type: 'session', sessionId: this.state.sessionId }]
      }
      case 'run_id': {
        const id = Number.parseInt(data, 10)
        this.state.runId = Number.isNaN(id) ? null : id
        return [{ type: 'run', runId: this.state.runId }]
      }
      case 'turn_id':
        this.state.turnId = data
        return [{ type: 'turn', turnId: data }]
      case 'content':
        return this.appendContent(parseText(data))
      case 'tool_call': {
        const payload = parsePayload(data)
        this.state.toolCalls += 1
        this.bumpKey(toolCallKey(payload))
        return [{
          type: 'tool',
          phase: 'call',
          tool: String(payload.tool ?? payload.name ?? 'tool'),
          args: previewArgs(payload.args)
        }]
      }
      case 'tool_result': {
        const payload = parsePayload(data)
        this.bumpKey(toolResultKey(payload))
        return [this.toolResult(payload)]
      }
      case 'approval_required': {
        const approval = parsePayload(data) as unknown as ApprovalRequired
        this.state.pendingApproval = approval
        this.bumpKey(approvalKey(approval as unknown as Record<string, unknown>))
        return [{ type: 'approval', approval }]
      }
      case 'approval_resolved':
        return [this.approvalResolved(parsePayload(data))]
      case 'file_change': {
        const payload = parsePayload(data)
        this.state.fileChanges += 1
        return [{
          type: 'file_change',
          path: String(payload.path ?? payload.filePath ?? ''),
          additions: numberOrZero(payload.additions),
          deletions: numberOrZero(payload.deletions)
        }]
      }
      case 'completion':
        this.state.completion = parsePayload(data) as CompletionView
        this.bumpKey('completion')
        return []
      case 'usage':
        this.state.usage = parsePayload(data) as UsageView
        return []
      case 'done':
        this.state.done = true
        return [{ type: 'done', completion: this.state.completion }]
      case 'error':
        this.state.error = data
        this.bumpKey('error')
        return [{ type: 'error', message: data }]
      default:
        return []
    }
  }

  handleReplay(item: RunEventItem): PanelEvent[] {
    if (typeof item.seq === 'number' && item.seq > this.state.lastSeq) this.state.lastSeq = item.seq
    const payload = parsePayload(item.payload)
    switch (item.type) {
      case 'MODEL_CHUNK': {
        const text = typeof payload.text === 'string' ? payload.text : ''
        // 回放 text 是累计全文，只追加尚未渲染的 UTF-16 后缀。
        if (!text || text.length <= this.state.contentLength) return []
        const suffix = text.slice(this.state.contentLength)
        this.state.contentText += suffix
        this.state.contentLength = text.length
        return [{ type: 'content', text: suffix }]
      }
      case 'TOOL_CALL': {
        if (this.consumeKey(toolCallKey(payload))) return []
        this.state.toolCalls += 1
        return [{
          type: 'tool',
          phase: 'call',
          tool: String(payload.tool ?? payload.name ?? 'tool'),
          args: previewArgs(payload.args)
        }]
      }
      case 'TOOL_RESULT':
        return this.consumeKey(toolResultKey(payload)) ? [] : [this.toolResult(payload)]
      case 'APPROVAL_REQUESTED': {
        if (this.consumeKey(approvalKey(payload))) return []
        const approvalId = String(payload.approvalId ?? '')
        const previous = this.state.pendingApproval?.approvalId === approvalId
          ? this.state.pendingApproval
          : null
        const approval: ApprovalRequired = {
          approvalId,
          tool: String(payload.tool ?? 'unknown'),
          preview: previous?.preview ?? null,
          risk: typeof payload.risk === 'string' ? payload.risk : previous?.risk ?? '',
          expiresAt: previous?.expiresAt ?? null
        }
        this.state.pendingApproval = approval
        return [{ type: 'approval', approval }]
      }
      case 'APPROVAL_RESOLVED':
        return [this.approvalResolved(payload)]
      case 'COMPLETION':
        if (this.consumeKey('completion')) return []
        this.state.completion = payload as CompletionView
        return []
      case 'ERROR': {
        if (this.consumeKey('error')) return []
        const message = typeof payload.message === 'string' ? payload.message : JSON.stringify(payload)
        this.state.error = message
        return [{ type: 'error', message }]
      }
      default:
        return []
    }
  }

  private appendContent(text: string): PanelEvent[] {
    if (!text) return []
    this.state.contentText += text
    this.state.contentLength = this.state.contentText.length
    return [{ type: 'content', text }]
  }

  private toolResult(payload: Record<string, unknown>): PanelEvent {
    this.state.toolResults += 1
    return {
      type: 'tool',
      phase: 'result',
      tool: String(payload.tool ?? 'tool'),
      success: payload.success !== false,
      costMs: typeof payload.costMs === 'number' ? payload.costMs : null,
      error: typeof payload.error === 'string' ? payload.error : null
    }
  }

  private approvalResolved(payload: Record<string, unknown>): PanelEvent {
    const approvalId = payload.approvalId === undefined ? null : String(payload.approvalId)
    const decision = String(payload.decision ?? '')
    if (!approvalId || this.state.pendingApproval?.approvalId === approvalId) this.state.pendingApproval = null
    return { type: 'approval_resolved', approvalId, decision }
  }

  private bumpKey(key: string): void {
    this.state.seen.set(key, (this.state.seen.get(key) ?? 0) + 1)
  }

  private consumeKey(key: string): boolean {
    const count = this.state.seen.get(key) ?? 0
    if (count <= 0) return false
    this.state.seen.set(key, count - 1)
    return true
  }
}

function toolCallKey(payload: Record<string, unknown>): string {
  return `tool_call|${String(payload.tool ?? payload.name ?? '')}|${typeof payload.args === 'string' ? payload.args : JSON.stringify(payload.args ?? '')}`
}

function toolResultKey(payload: Record<string, unknown>): string {
  return `tool_result|${String(payload.tool ?? '')}|${payload.success !== false}`
}

function approvalKey(payload: Record<string, unknown>): string {
  return `approval|${String(payload.approvalId ?? '')}`
}

function parsePayload(input: unknown): Record<string, unknown> {
  if (input === null || input === undefined) return {}
  if (typeof input === 'object') return input as Record<string, unknown>
  try {
    const parsed: unknown = JSON.parse(String(input))
    return parsed !== null && typeof parsed === 'object' ? parsed as Record<string, unknown> : {}
  } catch {
    return { text: String(input) }
  }
}

function parseText(data: string): string {
  const parsed = parsePayload(data)
  return typeof parsed.text === 'string' ? parsed.text : data
}

function previewArgs(args: unknown): string {
  if (args === undefined || args === null || args === '') return ''
  const text = typeof args === 'string' ? args : JSON.stringify(args)
  return text.replace(/\s+/g, ' ').trim().split('\n')[0].slice(0, 160)
}

function numberOrZero(value: unknown): number {
  return typeof value === 'number' ? value : 0
}
