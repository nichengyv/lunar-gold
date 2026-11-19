import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiError, buildUrl, request, send, type HttpContext } from '../src/shared/http.ts'

function contextWith(status: number, body: unknown): HttpContext {
  return {
    baseUrl: 'http://127.0.0.1:8080',
    token: 'lgc_test',
    fetchImpl: async () => new Response(body === null ? null : typeof body === 'string' ? body : JSON.stringify(body), { status })
  }
}

test('成功信封解出 data 并注入 Bearer', async () => {
  let authorization = ''
  const ctx: HttpContext = {
    baseUrl: 'http://127.0.0.1:8080/',
    token: 'lgc_test',
    fetchImpl: async (_url, init) => {
      authorization = String((init?.headers as Record<string, string>).Authorization)
      return Response.json({ code: 200, message: 'success', data: { ok: 1 }, timestamp: 1 })
    }
  }
  assert.deepEqual(await request<{ ok: number }>(ctx, { path: '/api/x' }), { ok: 1 })
  assert.equal(authorization, 'Bearer lgc_test')
})

test('204 与 health 裸对象均可处理', async () => {
  assert.equal(await request(contextWith(204, null), { method: 'DELETE', path: '/api/clients/self' }), undefined)
  const health = await request<{ status: string }>(contextWith(200, { status: 'UP', apiContractVersion: 1 }), { path: '/api/health' })
  assert.equal(health.status, 'UP')
})

test('401 映射认证错误并保留后端 message', async () => {
  await assert.rejects(request(contextWith(401, { message: '令牌无效' }), { path: '/api/x' }), (error: unknown) => {
    assert.ok(error instanceof ApiError)
    assert.equal(error.kind, 'auth')
    assert.equal(error.message, '令牌无效')
    assert.equal(error.retryable, false)
    assert.match(error.hint ?? '', /连接本机后端/)
    return true
  })
})

test('403 scope 错误给出换发提示且不重试', async () => {
  await assert.rejects(request(contextWith(403, { message: '缺少 scope: run:read' }), { path: '/api/x' }), (error: unknown) => {
    assert.ok(error instanceof ApiError)
    assert.equal(error.kind, 'auth')
    assert.match(error.hint ?? '', /全量 scope/)
    return true
  })
})

test('5xx 可重试，业务错误原样透出', async () => {
  await assert.rejects(request(contextWith(500, { message: 'boom' }), { path: '/api/x' }), (error: unknown) => {
    assert.ok(error instanceof ApiError)
    assert.equal(error.kind, 'server')
    assert.equal(error.retryable, true)
    return true
  })
  await assert.rejects(request(contextWith(400, { message: '审批已失效' }), { path: '/api/x' }), (error: unknown) => {
    assert.ok(error instanceof ApiError)
    assert.equal(error.kind, 'business')
    assert.equal(error.message, '审批已失效')
    return true
  })
})

test('HTTP 200 但信封 code 非 200 仍是业务错误', async () => {
  await assert.rejects(request(contextWith(200, { code: 400, message: '参数不对', data: null }), { path: '/api/x' }), /参数不对/)
})

test('网络错误带后端启动提示', async () => {
  const ctx: HttpContext = {
    baseUrl: 'http://127.0.0.1:8080',
    fetchImpl: async () => { throw new TypeError('fetch failed') }
  }
  await assert.rejects(send(ctx, { path: '/api/x' }), (error: unknown) => {
    assert.ok(error instanceof ApiError)
    assert.equal(error.kind, 'network')
    assert.match(error.hint ?? '', /dev-desktop\.ps1/)
    return true
  })
})

test('URL 跳过空参数并保留撤回查询参数语义', async () => {
  const url = buildUrl('http://127.0.0.1:8080', '/api/file-changes/revert-run', { sessionId: 12, runId: 34, empty: null })
  assert.equal(url, 'http://127.0.0.1:8080/api/file-changes/revert-run?sessionId=12&runId=34')
  let body: BodyInit | null | undefined
  const ctx: HttpContext = {
    baseUrl: 'http://127.0.0.1:8080',
    fetchImpl: async (_url, init) => {
      body = init?.body
      return new Response(null, { status: 204 })
    }
  }
  await request(ctx, { method: 'POST', path: '/api/file-changes/revert-run', query: { sessionId: 12, runId: 34 } })
  assert.equal(body, undefined)
})
