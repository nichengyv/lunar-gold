import type { ApiEnvelope } from './types.ts'

export type QueryValue = string | number | boolean | null | undefined
export type ApiErrorKind = 'auth' | 'network' | 'server' | 'business' | 'contract'

export class ApiError extends Error {
  readonly kind: ApiErrorKind
  readonly status: number | null
  readonly hint: string | null

  constructor(message: string, kind: ApiErrorKind, status: number | null = null, hint: string | null = null) {
    super(message)
    this.name = 'ApiError'
    this.kind = kind
    this.status = status
    this.hint = hint
  }

  get retryable(): boolean {
    return this.kind === 'network' || this.kind === 'server'
  }
}

export interface HttpContext {
  baseUrl: string
  token?: string | null
  fetchImpl: typeof fetch
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE'
  path: string
  query?: Record<string, QueryValue>
  body?: unknown
  token?: string | null
  signal?: AbortSignal
  /** null 用于 SSE；其他请求默认 30 秒。 */
  timeoutMs?: number | null
}

export function buildUrl(baseUrl: string, path: string, query?: Record<string, QueryValue>): string {
  const url = new URL(baseUrl.replace(/\/+$/, '') + path)
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined || value === null || value === '') continue
      url.searchParams.set(key, String(value))
    }
  }
  return url.toString()
}

function requestSignal(options: RequestOptions): AbortSignal | undefined {
  if (options.signal) return options.signal
  if (options.timeoutMs === null) return undefined
  return AbortSignal.timeout(options.timeoutMs ?? 30_000)
}

export async function send(ctx: HttpContext, options: RequestOptions): Promise<Response> {
  const hasBody = options.body !== undefined
  const headers: Record<string, string> = {
    Accept: options.path.includes('/stream') ? 'text/event-stream' : 'application/json'
  }
  if (hasBody) headers['Content-Type'] = 'application/json'
  const token = options.token !== undefined ? options.token : ctx.token
  if (token) headers.Authorization = `Bearer ${token}`
  try {
    return await ctx.fetchImpl(buildUrl(ctx.baseUrl, options.path, options.query), {
      method: options.method ?? 'GET',
      headers,
      body: hasBody ? JSON.stringify(options.body) : undefined,
      signal: requestSignal(options)
    })
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error)
    throw new ApiError(
      `无法连接后端 ${ctx.baseUrl}：${detail}`,
      'network',
      null,
      '先启动桌面端后端（scripts/dev-desktop.ps1），再重新连接。'
    )
  }
}

export async function toApiError(response: Response): Promise<ApiError> {
  const text = await response.text().catch(() => '')
  const message = extractMessage(text) || `后端返回 HTTP ${response.status}`
  if (response.status === 401) {
    return new ApiError(message, 'auth', response.status, '令牌无效或已撤销，请运行「Lunar Gold: 连接本机后端」。')
  }
  if (response.status === 403) {
    const hint = message.includes('缺少 scope')
      ? '客户端 scope 不足，请重新连接并换发全量 scope 客户端。'
      : '请运行「Lunar Gold: 连接本机后端」重新校验客户端。'
    return new ApiError(message, 'auth', response.status, hint)
  }
  if (response.status >= 500) {
    return new ApiError(`后端返回 HTTP ${response.status}：${message}`, 'server', response.status)
  }
  return new ApiError(message, 'business', response.status)
}

export async function request<T>(ctx: HttpContext, options: RequestOptions): Promise<T> {
  const response = await send(ctx, options)
  if (!response.ok) throw await toApiError(response)
  if (response.status === 204) return undefined as T
  const text = await response.text()
  if (!text) return undefined as T
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new ApiError(`后端响应不是合法 JSON：${text.slice(0, 200)}`, 'contract', response.status)
  }
  const envelope = parsed as ApiEnvelope<T>
  if (envelope && typeof envelope.code === 'number' && typeof envelope.message === 'string') {
    if (envelope.code !== 200) throw new ApiError(envelope.message, 'business', response.status)
    return envelope.data
  }
  return parsed as T
}

function extractMessage(text: string): string | null {
  if (!text) return null
  try {
    const parsed = JSON.parse(text) as { message?: unknown }
    if (parsed && typeof parsed.message === 'string' && parsed.message.length > 0) return parsed.message
  } catch {
    // 非 JSON 错误体使用截断原文。
  }
  const trimmed = text.trim()
  return trimmed.length > 500 ? `${trimmed.slice(0, 500)}…` : trimmed
}
