import assert from 'node:assert/strict'
import { test } from 'node:test'
import { EXIT } from '../src/errors.ts'
import { approveCommand } from '../src/commands/approve.ts'
import { errorResponse, jsonResponse, startStub, testConfig, testContext } from './helpers.ts'

async function runAgainst(handler: Parameters<typeof startStub>[0], options: { deny?: boolean; remember?: boolean; json?: boolean }) {
  const stub = await startStub(handler)
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl, json: options.json === true }) })
    const code = await approveCommand(ctx, { approvalId: 'ap-1', deny: options.deny, remember: options.remember })
    return { code, ctx, requests: stub.requests }
  } finally {
    await stub.close()
  }
}

test('approve 成功：默认 APPROVE + NONE，退出 0', async () => {
  const { code, ctx, requests } = await runAgainst((request, response) => {
    jsonResponse(response, null)
  }, {})
  assert.equal(code, EXIT.OK)
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].path, '/api/agent/approvals/ap-1')
  assert.deepEqual(JSON.parse(requests[0].body), { decision: 'APPROVE', remember: 'NONE' })
  assert.match(ctx.output.out, /审批已提交/)
})

test('--deny / --remember 映射 REJECT / SESSION', async () => {
  const { code, requests } = await runAgainst((request, response) => {
    jsonResponse(response, null)
  }, { deny: true, remember: true })
  assert.equal(code, EXIT.OK)
  assert.deepEqual(JSON.parse(requests[0].body), { decision: 'REJECT', remember: 'SESSION' })
})

test('审批失效（业务错误）退出 4，message 原样透出', async () => {
  const stub = await startStub((request, response) => {
    errorResponse(response, 400, '审批已失效或不属于当前用户')
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl }) })
    await assert.rejects(
      () => approveCommand(ctx, { approvalId: 'ap-9' }),
      (error: unknown) => {
        assert.equal((error as { exitCode?: number }).exitCode, EXIT.APPROVAL)
        assert.equal((error as Error).message, '审批已失效或不属于当前用户')
        return true
      }
    )
  } finally {
    await stub.close()
  }
})

test('approve --json：输出 result NDJSON', async () => {
  const { code, ctx } = await runAgainst((request, response) => {
    jsonResponse(response, null)
  }, { json: true })
  assert.equal(code, EXIT.OK)
  const line = JSON.parse(ctx.output.out.trim()) as Record<string, unknown>
  assert.equal(line.type, 'result')
  assert.equal(line.exitCode, 0)
  assert.equal(line.decision, 'APPROVE')
})
