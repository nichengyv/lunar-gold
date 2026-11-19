import { ApiError, request, send, toApiError, type HttpContext } from './http.ts'
import {
  ALL_CLIENT_SCOPES,
  EXPECTED_API_CONTRACT_VERSION,
  type ApiClientView,
  type HealthInfo
} from './types.ts'

/** 只保留格式有效的 SecretStorage 客户端令牌；其他值进入本地换发流程。 */
export function normalizeStoredClientToken(value: string | null | undefined): string | null {
  const token = value?.trim() ?? ''
  return /^lgc_[^_]+_.+/.test(token) ? token : null
}

export function normalizeLoopbackBaseUrl(value: string): string {
  let url: URL
  try {
    url = new URL(value.trim())
  } catch {
    throw new ApiError('后端地址不是合法 URL。', 'contract', null, '请检查 lunargold.baseUrl 设置。')
  }
  const loopback = url.hostname === 'localhost'
    || url.hostname === '[::1]'
    || url.hostname === '::1'
    || /^127\.\d+\.\d+\.\d+$/.test(url.hostname)
  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || !loopback) {
    throw new ApiError('首版插件只支持本机回环后端地址。', 'contract', null, '请将 lunargold.baseUrl 设置为 127.0.0.1、localhost 或 ::1。')
  }
  return value.trim().replace(/\/+$/, '')
}

export function assertCompatibleHealth(health: HealthInfo): void {
  if (health.status !== 'UP') {
    throw new ApiError(`后端尚未就绪：health.status=${health.status}。`, 'server')
  }
  if (health.apiContractVersion !== EXPECTED_API_CONTRACT_VERSION) {
    throw new ApiError(
      `插件与后端契约版本不一致：后端 ${health.apiContractVersion}，插件 ${EXPECTED_API_CONTRACT_VERSION}。`,
      'contract',
      null,
      '请升级插件或后端后重新连接。'
    )
  }
}

/** 无令牌时 401/403 也能证明后端在线；真正兼容性校验在拿到客户端令牌后进行。 */
export async function probeBackend(ctx: HttpContext): Promise<void> {
  const response = await send(ctx, { path: '/api/health', token: null })
  if (response.status === 401 || response.status === 403) return
  if (!response.ok) throw await toApiError(response)
  let health: HealthInfo
  try {
    health = await response.json() as HealthInfo
  } catch {
    throw new ApiError('后端 health 响应不是合法 JSON。', 'contract', response.status)
  }
  assertCompatibleHealth(health)
}

export function missingClientScopes(scopes: string[]): string[] {
  return ALL_CLIENT_SCOPES.filter((required) => !scopes.includes(required))
}

/** 用候选客户端令牌做完整连接校验，不在此函数中保存令牌。 */
export async function validateClientToken(ctx: HttpContext, token: string): Promise<ApiClientView> {
  const health = await request<HealthInfo>(ctx, { path: '/api/health', token })
  assertCompatibleHealth(health)
  const client = await request<ApiClientView>(ctx, { path: '/api/clients/self', token })
  const missing = missingClientScopes(client.scopes)
  if (missing.length > 0) {
    throw new ApiError(
      `客户端缺少 scope：${missing.join(', ')}`,
      'auth',
      403,
      '请重新连接并换发全量 scope 客户端。'
    )
  }
  return client
}
