import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  assertCompatibleHealth,
  missingClientScopes,
  normalizeLoopbackBaseUrl,
  normalizeStoredClientToken,
  probeBackend,
  validateClientToken
} from '../src/shared/connection.ts'
import { ApiError, type HttpContext } from '../src/shared/http.ts'
import { ALL_CLIENT_SCOPES, type HealthInfo } from '../src/shared/types.ts'

const health: HealthInfo = {
  status: 'UP',
  version: '1',
  serverVersion: '1',
  apiContractVersion: 1,
  uptimeMs: 1,
  startedAt: '2026-01-01T00:00:00Z',
  timestamp: '2026-01-01T00:00:00Z'
}

function contextWith(handler: (url: string, init?: RequestInit) => Promise<Response>): HttpContext {
  return { baseUrl: 'http://127.0.0.1:8080', fetchImpl: handler }
}

test('合法 SecretStorage 客户端令牌优先，非法值进入换发流程', () => {
  assert.equal(normalizeStoredClientToken('  lgc_abc_secret  '), 'lgc_abc_secret')
  assert.equal(normalizeStoredClientToken('local-user-token'), null)
  assert.equal(normalizeStoredClientToken(''), null)
  assert.equal(normalizeStoredClientToken(null), null)
})

test('health 只接受 UP 与契约版本 1', () => {
  assert.doesNotThrow(() => assertCompatibleHealth(health))
  assert.throws(() => assertCompatibleHealth({ ...health, status: 'DOWN' }), /尚未就绪/)
  assert.throws(
    () => assertCompatibleHealth({ ...health, apiContractVersion: 2 }),
    (error: unknown) => error instanceof ApiError && error.kind === 'contract' && /契约版本不一致/.test(error.message)
  )
})

test('无令牌 health 的 401 代表后端在线，且不会发送 Authorization', async () => {
  let authorization: string | undefined
  const ctx = contextWith(async (_url, init) => {
    authorization = (init?.headers as Record<string, string>).Authorization
    return Response.json({ message: '未认证' }, { status: 401 })
  })
  await probeBackend(ctx)
  assert.equal(authorization, undefined)
})

test('health 裸响应仍执行兼容性校验', async () => {
  const ctx = contextWith(async () => Response.json({ ...health, apiContractVersion: 9 }))
  await assert.rejects(probeBackend(ctx), /契约版本不一致/)
})

test('baseUrl 仅允许 HTTP(S) 回环地址', () => {
  assert.equal(normalizeLoopbackBaseUrl('http://127.0.0.1:8080/'), 'http://127.0.0.1:8080')
  assert.equal(normalizeLoopbackBaseUrl('http://[::1]:8080'), 'http://[::1]:8080')
  assert.throws(() => normalizeLoopbackBaseUrl('http://example.com'), /回环/)
  assert.throws(() => normalizeLoopbackBaseUrl('file:///tmp/backend'), /回环/)
})

test('客户端令牌依次校验 health 与 self，并使用候选令牌', async () => {
  const paths: string[] = []
  const authorizations: string[] = []
  const ctx = contextWith(async (url, init) => {
    paths.push(new URL(url).pathname)
    authorizations.push((init?.headers as Record<string, string>).Authorization)
    if (paths.length === 1) return Response.json(health)
    return Response.json({
      code: 200,
      message: 'success',
      data: {
        clientId: 'abc', name: 'vscode-test', scopes: [...ALL_CLIENT_SCOPES],
        createdAt: '2026-01-01', lastUsedAt: null, revoked: false
      },
      timestamp: 1
    })
  })
  const client = await validateClientToken(ctx, 'lgc_abc_secret')
  assert.equal(client.clientId, 'abc')
  assert.deepEqual(paths, ['/api/health', '/api/clients/self'])
  assert.deepEqual(authorizations, ['Bearer lgc_abc_secret', 'Bearer lgc_abc_secret'])
})

test('scope 不足映射为认证引导，不进入可重试错误', async () => {
  assert.deepEqual(missingClientScopes(['chat:write']), ALL_CLIENT_SCOPES.filter((scope) => scope !== 'chat:write'))
  const ctx = contextWith(async (url) => {
    if (new URL(url).pathname === '/api/health') return Response.json(health)
    return Response.json({
      code: 200,
      message: 'success',
      data: {
        clientId: 'abc', name: 'limited', scopes: ['chat:write'],
        createdAt: '2026-01-01', lastUsedAt: null, revoked: false
      },
      timestamp: 1
    })
  })
  await assert.rejects(validateClientToken(ctx, 'lgc_abc_secret'), (error: unknown) => {
    assert.ok(error instanceof ApiError)
    assert.equal(error.kind, 'auth')
    assert.equal(error.retryable, false)
    assert.match(error.message, /缺少 scope/)
    return true
  })
})
