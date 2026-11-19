/**
 * HTTP 客户端：注入 Bearer、解 ApiResponse 信封、把 HTTP 状态映射成稳定退出码。
 * 所有命令都通过这里发请求，错误文案与退出码只有这一处来源。
 */
import type { CommandContext } from './context.ts'
import { CliError, EXIT, type ExitCode } from './errors.ts'
import type { ApiEnvelope } from './types.ts'

export type QueryValue = string | number | boolean | null | undefined

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE'
  path: string
  query?: Record<string, QueryValue>
  body?: unknown
  token?: string | null
  /** 业务错误（4xx 非 401/403）的退出码，默认 1 */
  businessExitCode?: ExitCode
  signal?: AbortSignal
  /** null 表示不设超时；流式请求必须用 null */
  timeoutMs?: number | null
}

const SCOPE_HINT =
  '补齐后重签：lg client create --name cli --scopes "chat:write,run:read,changes:read,changes:write,approval:write,project:read,client:admin"'

export function buildUrl(baseUrl: string, path: string, query?: Record<string, QueryValue>): string {
  const url = new URL(baseUrl + path)
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined || value === null || value === '') continue
      url.searchParams.set(key, String(value))
    }
  }
  return url.toString()
}

function buildHeaders(token: string | null, hasBody: boolean, accept?: string): Record<string, string> {
  const headers: Record<string, string> = { Accept: accept ?? 'application/json' }
  if (hasBody) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}

function signalFor(options: RequestOptions): AbortSignal | undefined {
  if (options.signal) return options.signal
  if (options.timeoutMs === null) return undefined
  const timeout = options.timeoutMs ?? 30_000
  return AbortSignal.timeout(timeout)
}

/** 发请求并返回原始 Response；网络错误统一转成退出码 1 */
export async function send(ctx: CommandContext, options: RequestOptions): Promise<Response> {
  const url = buildUrl(ctx.config.baseUrl, options.path, options.query)
  const token = options.token !== undefined ? options.token : ctx.config.token
  try {
    return await ctx.fetchImpl(url, {
      method: options.method ?? 'GET',
      headers: buildHeaders(token ?? null, options.body !== undefined, options.path.includes('/stream') ? 'text/event-stream' : undefined),
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: signalFor(options)
    })
  } catch (error) {
    throw networkError(ctx, error)
  }
}

export function networkError(ctx: CommandContext, error: unknown): CliError {
  const detail = error instanceof Error ? error.message : String(error)
  return new CliError(
    `无法连接后端 ${ctx.config.baseUrl}：${detail}`,
    EXIT.ERROR,
    '先启动桌面端后端（scripts/dev-desktop.ps1），或用 --base-url 指定地址'
  )
}

/** 非 2xx → CliError；message 取后端信封原文，便于原样透出 */
export async function httpError(response: Response, businessExitCode: ExitCode = EXIT.ERROR): Promise<CliError> {
  const text = await response.text().catch(() => '')
  const message = extractMessage(text) || `后端返回 HTTP ${response.status}`
  if (response.status === 401) {
    return new CliError(message, EXIT.AUTH, '令牌无效或已撤销：运行 lg client create --name cli 重新签发')
  }
  if (response.status === 403) {
    return new CliError(message, EXIT.AUTH, message.includes('缺少 scope') ? SCOPE_HINT : null)
  }
  if (response.status >= 500) {
    return new CliError(`后端返回 HTTP ${response.status}：${message}`, EXIT.ERROR)
  }
  return new CliError(message, businessExitCode)
}

function extractMessage(text: string): string | null {
  if (!text) return null
  try {
    const parsed = JSON.parse(text) as { message?: unknown }
    if (parsed && typeof parsed.message === 'string' && parsed.message.length > 0) return parsed.message
  } catch {
    // 非 JSON 错误体：用原文（截断，避免整页 HTML 糊满终端）
  }
  const trimmed = text.trim()
  return trimmed.length > 500 ? `${trimmed.slice(0, 500)}…` : trimmed
}

/** 解析 ApiResponse 信封并返回 data；业务码非 200 按错误处理 */
export async function request<T>(ctx: CommandContext, options: RequestOptions): Promise<T> {
  const response = await send(ctx, options)
  if (!response.ok) throw await httpError(response, options.businessExitCode)
  if (response.status === 204) return undefined as T
  const text = await response.text()
  if (!text) return undefined as T
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new CliError(`后端响应不是合法 JSON：${text.slice(0, 200)}`, EXIT.ERROR)
  }
  const envelope = parsed as ApiEnvelope<T>
  if (envelope && typeof envelope.code === 'number' && typeof envelope.message === 'string') {
    if (envelope.code !== 200) {
      throw new CliError(envelope.message, options.businessExitCode ?? EXIT.ERROR)
    }
    return envelope.data
  }
  // 少数端点（如 /api/health）不走信封，直接返回对象
  return parsed as T
}

export async function requestRaw<T>(ctx: CommandContext, options: RequestOptions): Promise<T> {
  const response = await send(ctx, options)
  if (!response.ok) throw await httpError(response, options.businessExitCode)
  return (await response.json()) as T
}
