/**
 * 事件渲染器：run（SSE）与 watch（落库事件回放）共用同一套输出逻辑。
 *
 * 两种输入形态不同：
 *   - SSE：content 是增量文本，事件名小写；
 *   - 回放：MODEL_CHUNK 的 payload.text 是「累计全文」，事件名大写。
 * 因此回放时按已输出长度取后缀，保证断流续看不重复正文；
 * 工具/审批/完成这类离散事件用指纹计数去重（先记录 SSE 已输出次数，回放时跳过同样次数）。
 */
import type { Output } from './context.ts'
import type { ExitCode } from './errors.ts'
import type { ApprovalRequired, RunEventItem } from './types.ts'

export interface RendererOptions {
  json: boolean
  quiet: boolean
  out: Output
  err: Output
}

export interface CompletionView {
  status?: string
  reason?: string
  message?: string
  filesChanged?: boolean
}

export interface UsageView {
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
}

export interface RenderState {
  sessionId: number | null
  runId: number | null
  turnId: string | null
  contentText: string
  /** 已输出正文长度（按 UTF-16 码元，与后端 length 字段口径一致） */
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
  /** 回放去重：key → SSE 阶段已输出的次数 */
  seen: Map<string, number>
  /** 正文是否在行尾（结束时补一个换行） */
  atLineStart: boolean
}

export interface RunResultSummary {
  exitCode: ExitCode
  status: string
  sessionId: number | null
  runId: number | null
  turnId: string | null
  filesChanged: boolean
  completion: string | null
  error: string | null
}

export function initialState(): RenderState {
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
    seen: new Map<string, number>(),
    atLineStart: true
  }
}

export class Renderer {
  readonly state: RenderState = initialState()
  private readonly options: RendererOptions

  constructor(options: RendererOptions) {
    this.options = options
  }

  /** 处理一条 SSE 事件；返回 true 表示流已终结（done / error） */
  handleSse(type: string, data: string): boolean {
    switch (type) {
      case 'session_id': {
        const id = Number.parseInt(data, 10)
        this.state.sessionId = Number.isNaN(id) ? null : id
        this.progress(`session: ${data}`)
        this.jsonLine({ type: 'session', sessionId: this.state.sessionId })
        return false
      }
      case 'run_id': {
        const id = Number.parseInt(data, 10)
        this.state.runId = Number.isNaN(id) ? null : id
        this.progress(`run: ${data}`)
        this.jsonLine({ type: 'run', runId: this.state.runId })
        return false
      }
      case 'turn_id':
        this.state.turnId = data
        this.progress(`turn: ${data}`)
        return false
      case 'content': {
        this.printContentDelta(parseText(data))
        return false
      }
      case 'tool_call': {
        const obj = parsePayload(data)
        this.state.toolCalls += 1
        this.bumpKey(toolCallKey(obj))
        this.printToolCall(obj)
        return false
      }
      case 'tool_result': {
        const obj = parsePayload(data)
        this.state.toolResults += 1
        this.bumpKey(toolResultKey(obj))
        this.printToolResult(obj)
        return false
      }
      case 'approval_required': {
        const obj = parsePayload(data) as unknown as ApprovalRequired
        this.state.pendingApproval = obj
        this.bumpKey(approvalKey(obj))
        this.printApprovalCard(obj)
        return false
      }
      case 'approval_resolved': {
        const obj = parsePayload(data)
        this.progress(`审批已处理：${String(obj.decision ?? '')}`)
        return false
      }
      case 'file_change': {
        const obj = parsePayload(data)
        this.state.fileChanges += 1
        this.printFileChange(obj)
        return false
      }
      case 'completion':
        this.state.completion = parsePayload(data) as CompletionView
        return false
      case 'usage': {
        this.state.usage = parsePayload(data) as UsageView
        this.progress(`tokens: prompt=${this.state.usage.promptTokens ?? 0} completion=${this.state.usage.completionTokens ?? 0}`)
        return false
      }
      case 'done':
        this.state.done = true
        return true
      case 'error':
        this.state.error = data
        return true
      case 'thinking':
      case 'plan':
      case 'no_progress':
      case 'diagnostics':
      case 'verify':
        return false
      default:
        this.options.err.write(`[warn] 未知事件类型：${type}\n`)
        return false
    }
  }

  /** 处理一条落库事件（GET /api/agent/runs/{runId}/events） */
  handleReplay(item: RunEventItem): void {
    if (typeof item.seq === 'number' && item.seq > this.state.lastSeq) this.state.lastSeq = item.seq
    const payload = parsePayload(item.payload)
    switch (item.type) {
      case 'MODEL_CHUNK': {
        const text = typeof payload.text === 'string' ? payload.text : ''
        this.printCumulative(text)
        return
      }
      case 'TOOL_CALL': {
        if (this.consumeKey(toolCallKey(payload))) return
        this.state.toolCalls += 1
        this.printToolCall(payload)
        return
      }
      case 'TOOL_RESULT': {
        if (this.consumeKey(toolResultKey(payload))) return
        this.state.toolResults += 1
        this.printToolResult(payload)
        return
      }
      case 'APPROVAL_REQUESTED': {
        if (this.consumeKey(approvalKey(payload))) return
        const card: ApprovalRequired = {
          approvalId: String(payload.approvalId ?? ''),
          tool: String(payload.tool ?? 'unknown'),
          preview: null,
          risk: typeof payload.risk === 'string' ? payload.risk : '',
          expiresAt: null
        }
        this.state.pendingApproval = card
        this.printApprovalCard(card)
        return
      }
      case 'COMPLETION': {
        if (this.consumeKey('completion')) return
        this.state.completion = payload as CompletionView
        return
      }
      case 'ERROR': {
        if (this.consumeKey('error')) return
        this.state.error = typeof payload.message === 'string' ? payload.message : JSON.stringify(payload)
        return
      }
      case 'APPROVAL_RESOLVED':
      case 'STATE_CHANGED':
      case 'PROMPT_LOADED':
      case 'NO_PROGRESS':
      case 'DIAGNOSTICS':
      case 'VERIFY':
        return
      default:
        return
    }
  }

  /** 结束输出：正文未换行时补一个，避免 shell 提示符黏在最后一行 */
  finishOutput(): void {
    if (!this.options.json && !this.state.atLineStart) {
      this.options.out.write('\n')
      this.state.atLineStart = true
    }
  }

  filesChanged(): boolean {
    if (this.state.completion?.filesChanged === true) return true
    return this.state.fileChanges > 0
  }

  printResult(summary: RunResultSummary): void {
    if (this.options.json) {
      this.jsonLine({
        type: 'result',
        exitCode: summary.exitCode,
        status: summary.status,
        sessionId: summary.sessionId,
        runId: summary.runId,
        turnId: summary.turnId,
        filesChanged: summary.filesChanged,
        completion: summary.completion,
        error: summary.error
      })
      return
    }
    const parts: string[] = [`完成：${summary.status}`]
    if (summary.filesChanged) parts.push(`文件变更 ${this.state.fileChanges || '若干'} 个`)
    if (summary.completion) parts.push(summary.completion)
    if (summary.error) parts.push(`错误：${summary.error}`)
    if (summary.sessionId !== null) parts.push(`session=${summary.sessionId}`)
    if (summary.runId !== null) parts.push(`run=${summary.runId}`)
    this.options.out.write(`${parts.join('；')}\n`)
  }

  /** 供 T5 打印「任务仍在等待审批」提示 */
  printPendingApprovalHint(): void {
    this.options.err.write('任务仍在等待审批，可用 lg watch 续看；批准后任务会继续执行。\n')
  }

  /** 断线续看提示：人读写 stderr，--json 写一行 resume 事件 */
  printResume(sinceSeq: number): void {
    if (this.options.json) {
      this.jsonLine({ type: 'resume', sinceSeq })
      return
    }
    this.options.err.write(`[resume from seq=${sinceSeq}]\n`)
  }

  // ------------------------------------------------------------------
  // 输出原语
  // ------------------------------------------------------------------

  private printContentDelta(text: string): void {
    if (!text) return
    this.state.contentText += text
    this.state.contentLength = this.state.contentText.length
    this.writeContent(text)
  }

  /** 回放：payload.text 是累计全文，只输出尚未输出的后缀 */
  private printCumulative(text: string): void {
    if (!text) return
    if (text.length <= this.state.contentLength) return
    const suffix = text.slice(this.state.contentLength)
    this.state.contentText += suffix
    this.state.contentLength = text.length
    this.writeContent(suffix)
  }

  private writeContent(text: string): void {
    if (this.options.json) {
      this.jsonLine({ type: 'content', text })
      return
    }
    this.options.out.write(text)
    this.state.atLineStart = text.endsWith('\n')
  }

  private printToolCall(obj: Record<string, unknown>): void {
    const tool = String(obj.tool ?? obj.name ?? 'tool')
    const args = previewArgs(obj.args)
    if (this.options.json) {
      this.jsonLine({ type: 'tool', event: 'call', tool, args })
      return
    }
    this.progress(`→ ${tool}${args ? ` ${args}` : ''}`)
  }

  private printToolResult(obj: Record<string, unknown>): void {
    const tool = String(obj.tool ?? 'tool')
    const success = obj.success !== false
    const cost = typeof obj.costMs === 'number' ? ` (${obj.costMs}ms)` : ''
    const error = typeof obj.error === 'string' && obj.error ? `：${firstLine(obj.error)}` : ''
    if (this.options.json) {
      this.jsonLine({
        type: 'tool',
        event: 'result',
        tool,
        success,
        costMs: typeof obj.costMs === 'number' ? obj.costMs : null,
        error: typeof obj.error === 'string' ? obj.error : null
      })
      return
    }
    this.progress(`← ${tool} ${success ? 'ok' : 'failed'}${cost}${error}`)
  }

  private printApprovalCard(card: ApprovalRequired): void {
    if (this.options.json) {
      this.jsonLine({
        type: 'approval',
        approvalId: card.approvalId,
        tool: card.tool,
        risk: card.risk ?? '',
        expiresAt: card.expiresAt ?? null,
        preview: card.preview ?? null
      })
      return
    }
    const risk = card.risk ? `（风险 ${card.risk}）` : ''
    this.progress(`⚠ 需要审批：${card.tool}${risk}`)
    const preview = card.preview
    if (preview) {
      if (preview.path) this.progress(`  文件：${preview.path}`)
      if (preview.command) this.progress(`  命令：${preview.command}`)
      if (preview.note) this.progress(`  说明：${preview.note}`)
      if (typeof preview.additions === 'number' || typeof preview.deletions === 'number') {
        this.progress(`  变更：+${preview.additions ?? 0} -${preview.deletions ?? 0}`)
      }
    }
    if (card.expiresAt !== null && card.expiresAt !== undefined) {
      this.progress(`  过期：${formatExpiry(card.expiresAt)}`)
    }
    this.progress(`  批准：lg approve ${card.approvalId}`)
    this.progress(`  拒绝：lg approve ${card.approvalId} --deny`)
  }

  private printFileChange(obj: Record<string, unknown>): void {
    const path = String(obj.path ?? obj.filePath ?? '')
    const additions = typeof obj.additions === 'number' ? obj.additions : 0
    const deletions = typeof obj.deletions === 'number' ? obj.deletions : 0
    if (this.options.json) {
      this.jsonLine({ type: 'tool', event: 'file_change', path, additions, deletions })
      return
    }
    this.progress(`± ${path} +${additions} -${deletions}`)
  }

  /** 人读进度：--quiet 全部抑制（正文仍输出） */
  private progress(line: string): void {
    if (this.options.json || this.options.quiet) return
    if (!this.state.atLineStart) {
      this.options.out.write('\n')
      this.state.atLineStart = true
    }
    this.options.out.write(`${line}\n`)
  }

  private jsonLine(obj: Record<string, unknown>): void {
    if (!this.options.json) return
    this.options.out.write(`${JSON.stringify(obj)}\n`)
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

function toolCallKey(obj: Record<string, unknown>): string {
  return `tool_call|${String(obj.tool ?? obj.name ?? '')}|${typeof obj.args === 'string' ? obj.args : JSON.stringify(obj.args ?? '')}`
}

function toolResultKey(obj: Record<string, unknown>): string {
  return `tool_result|${String(obj.tool ?? '')}|${obj.success !== false}`
}

function approvalKey(obj: Record<string, unknown>): string {
  return `approval|${String(obj.approvalId ?? '')}`
}

function parsePayload(input: unknown): Record<string, unknown> {
  if (input === null || input === undefined) return {}
  if (typeof input === 'object') return input as Record<string, unknown>
  try {
    const parsed: unknown = JSON.parse(String(input))
    return parsed !== null && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {}
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
  return firstLine(text.replace(/\s+/g, ' ').trim()).slice(0, 160)
}

function firstLine(text: string): string {
  const index = text.indexOf('\n')
  return index >= 0 ? text.slice(0, index) : text
}

function formatExpiry(value: string | number): string {
  if (typeof value === 'number') {
    return new Date(value).toISOString()
  }
  return value
}
