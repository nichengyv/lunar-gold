import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CliError, EXIT } from '../src/errors.ts'
import { buildUrl, request, requestRaw } from '../src/http.ts'
import { testContext } from './helpers.ts'

function fakeFetch(status: number, body: unknown, headers: Record<string, string> = {}): typeof fetch {
  return async () => new Response(typeof body === 'string' ? body : JSON.stringify(body), { status, headers })
}

test('成功信封解出 data', async () => {
  const ctx = testContext({
    fetchImpl: fakeFetch(200, { code: 200, message: 'success', data: { ok: 1 }, timestamp: 1 })
  })
  const data = await request<{ ok: number }>(ctx, { path: '/api/x' })
  assert.deepEqual(data, { ok: 1 })
})

test('204 返回 undefined', async () => {
  const ctx = testContext({ fetchImpl: async () => new Response(null, { status: 204 }) })
  assert.equal(await request(ctx, { method: 'DELETE', path: '/api/clients/self' }), undefined)
})

test('401 → 退出码 3，message 取后端原文', async () => {
  const ctx = testContext({ fetchImpl: fakeFetch(401, { code: 401, message: '令牌无效', data: null }) })
  await assert.rejects(
    () => request(ctx, { path: '/api/agent/sessions' }),
    (error: unknown) => {
      assert.ok(error instanceof CliError)
      assert.equal(error.exitCode, EXIT.AUTH)
      assert.equal(error.message, '令牌无效')
      assert.match(error.hint ?? '', /lg client create/)
      return true
    }
  )
})

test('403 + 缺少 scope → 退出码 3 且给出补 scope 命令', async () => {
  const ctx = testContext({
    fetchImpl: fakeFetch(403, { code: 403, message: '缺少 scope: run:read', data: null })
  })
  await assert.rejects(
    () => request(ctx, { path: '/api/agent/runs/1/events' }),
    (error: unknown) => {
      assert.ok(error instanceof CliError)
      assert.equal(error.exitCode, EXIT.AUTH)
      assert.match(error.message, /缺少 scope/)
      assert.match(error.hint ?? '', /lg client create --name cli --scopes/)
      return true
    }
  )
})

test('403 非 scope → 退出码 3，无 scope 修复提示', async () => {
  const ctx = testContext({
    fetchImpl: fakeFetch(403, { code: 403, message: '该接口未对客户端开放：GET /api/settings', data: null })
  })
  await assert.rejects(
    () => request(ctx, { path: '/api/settings' }),
    (error: unknown) => {
      assert.ok(error instanceof CliError)
      assert.equal(error.exitCode, EXIT.AUTH)
      assert.equal(error.hint, null)
      return true
    }
  )
})

test('5xx → 退出码 1', async () => {
  const ctx = testContext({ fetchImpl: fakeFetch(500, { code: 500, message: 'boom', data: null }) })
  await assert.rejects(
    () => request(ctx, { path: '/api/x' }),
    (error: unknown) => {
      assert.ok(error instanceof CliError)
      assert.equal(error.exitCode, EXIT.ERROR)
      assert.match(error.message, /500/)
      return true
    }
  )
})

test('业务错误按调用方给的语义码（审批 → 4）', async () => {
  const ctx = testContext({ fetchImpl: fakeFetch(400, { code: 400, message: '审批已失效或不属于当前用户' }) })
  await assert.rejects(
    () => request(ctx, { method: 'POST', path: '/api/agent/approvals/a1', body: {}, businessExitCode: EXIT.APPROVAL }),
    (error: unknown) => {
      assert.ok(error instanceof CliError)
      assert.equal(error.exitCode, EXIT.APPROVAL)
      assert.equal(error.message, '审批已失效或不属于当前用户')
      return true
    }
  )
})

test('HTTP 200 但信封 code 非 200 → 业务错误', async () => {
  const ctx = testContext({ fetchImpl: fakeFetch(200, { code: 400, message: '参数不对' }) })
  await assert.rejects(
    () => request(ctx, { path: '/api/x' }),
    (error: unknown) => {
      assert.ok(error instanceof CliError)
      assert.equal(error.message, '参数不对')
      return true
    }
  )
})

test('网络错误 → 退出码 1，带启动提示', async () => {
  const ctx = testContext({
    fetchImpl: async () => {
      throw new TypeError('fetch failed')
    }
  })
  await assert.rejects(
    () => request(ctx, { path: '/api/x' }),
    (error: unknown) => {
      assert.ok(error instanceof CliError)
      assert.equal(error.exitCode, EXIT.ERROR)
      assert.match(error.message, /无法连接后端/)
      assert.match(error.hint ?? '', /dev-desktop\.ps1/)
      return true
    }
  )
})

test('health 不走信封，直接返回字段', async () => {
  const ctx = testContext({
    fetchImpl: fakeFetch(200, { status: 'UP', apiContractVersion: 1 })
  })
  const health = await requestRaw<{ apiContractVersion: number }>(ctx, { path: '/api/health' })
  assert.equal(health.apiContractVersion, 1)
})

test('URL 构造：跳过空查询参数', () => {
  const url = buildUrl('http://127.0.0.1:8080', '/api/file-changes/changeset', {
    sessionId: 12,
    runId: undefined,
    empty: null
  })
  assert.equal(url, 'http://127.0.0.1:8080/api/file-changes/changeset?sessionId=12')
})
