import assert from 'node:assert/strict'
import { test } from 'node:test'
import { EXIT } from '../src/errors.ts'
import { watchCommand } from '../src/commands/watch.ts'
import { errorResponse, jsonResponse, startStub, testConfig, testContext } from './helpers.ts'

function eventsBody(state: string, events: Array<Record<string, unknown>>, errorMessage: string | null = null) {
  return {
    runId: 7,
    state,
    errorCode: state === 'FAILED' ? 'AGENT_EXECUTION_FAILED' : null,
    errorMessage,
    promptTokens: 1,
    completionTokens: 2,
    totalTokens: 3,
    events
  }
}

test('watch：按 seq 续看，不重复输出已消费事件', async () => {
  let call = 0
  const stub = await startStub((request, response) => {
    if (request.path !== '/api/agent/runs/7/events') {
      errorResponse(response, 404, 'unexpected')
      return
    }
    call += 1
    if (call === 1) {
      jsonResponse(response, eventsBody('RUNNING', [
        { seq: 1, type: 'MODEL_CHUNK', payload: '{"text":"ab","length":2}', createTime: 'x' },
        { seq: 2, type: 'TOOL_CALL', payload: '{"tool":"read_file","args":"{}"}', createTime: 'x' },
        { seq: 3, type: 'TOOL_RESULT', payload: '{"tool":"read_file","success":true}', createTime: 'x' }
      ]))
      return
    }
    assert.equal(request.query.get('sinceSeq'), '3')
    jsonResponse(response, eventsBody('SUCCEEDED', [
      { seq: 4, type: 'MODEL_CHUNK', payload: '{"text":"abcd","length":4}', createTime: 'x' },
      { seq: 5, type: 'COMPLETION', payload: '{"status":"COMPLETED","filesChanged":false}', createTime: 'x' }
    ]))
  })
  try {
    const ctx = testContext({
      config: testConfig({ baseUrl: stub.baseUrl, json: true }),
      pollMs: 1
    })
    const code = await watchCommand(ctx, { runId: 7 })
    assert.equal(code, EXIT.OK)
    assert.equal(call, 2)
    const lines = ctx.output.out.trim().split('\n').map((line) => JSON.parse(line) as Record<string, unknown>)
    const content = lines.filter((line) => line.type === 'content').map((line) => String(line.text)).join('')
    assert.equal(content, 'abcd')
    const tools = lines.filter((line) => line.type === 'tool')
    assert.deepEqual(tools.map((line) => line.event), ['call', 'result'])
    assert.equal(ctx.output.err.includes('[resume from'), false)
  } finally {
    await stub.close()
  }
})

test('watch：成功且文件有变更 → 退出 6', async () => {
  const stub = await startStub((request, response) => {
    jsonResponse(response, eventsBody('SUCCEEDED', [
      { seq: 1, type: 'MODEL_CHUNK', payload: '{"text":"done","length":4}', createTime: 'x' },
      { seq: 2, type: 'COMPLETION', payload: '{"status":"COMPLETED","filesChanged":true}', createTime: 'x' }
    ]))
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl }), pollMs: 1 })
    assert.equal(await watchCommand(ctx, { runId: 7 }), EXIT.UNVERIFIED)
    assert.match(ctx.output.out, /文件变更/)
  } finally {
    await stub.close()
  }
})

test('watch：失败终态退出 5，并透出 errorMessage', async () => {
  const stub = await startStub((request, response) => {
    jsonResponse(response, eventsBody('FAILED', [], '模型不可用'))
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl }), pollMs: 1 })
    const code = await watchCommand(ctx, { runId: 7 })
    assert.equal(code, EXIT.INCOMPLETE)
    assert.match(ctx.output.out, /模型不可用/)
  } finally {
    await stub.close()
  }
})

test('watch：连接失败重试并打印 resume，恢复后继续', async () => {
  let call = 0
  const stub = await startStub((request, response) => {
    call += 1
    if (call === 1) {
      jsonResponse(response, eventsBody('RUNNING', [
        { seq: 1, type: 'MODEL_CHUNK', payload: '{"text":"a","length":1}', createTime: 'x' }
      ]))
      return
    }
    if (call === 2) {
      response.socket?.destroy()
      return
    }
    assert.equal(request.query.get('sinceSeq'), '1')
    jsonResponse(response, eventsBody('SUCCEEDED', [
      { seq: 2, type: 'MODEL_CHUNK', payload: '{"text":"ab","length":2}', createTime: 'x' }
    ]))
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl }), pollMs: 1 })
    const code = await watchCommand(ctx, { runId: 7 })
    assert.equal(code, EXIT.OK)
    assert.match(ctx.output.err, /\[resume from seq=1\]/)
    assert.ok(ctx.sleeps.includes(1000), `应等待 1s，实际：${ctx.sleeps.join(',')}`)
    assert.match(ctx.output.out, /ab/)
  } finally {
    await stub.close()
  }
})

test('watch：401 不重试，退出 3', async () => {
  let call = 0
  const stub = await startStub((request, response) => {
    call += 1
    errorResponse(response, 401, '令牌无效')
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl }), pollMs: 1 })
    await assert.rejects(
      () => watchCommand(ctx, { runId: 7 }),
      (error: unknown) => {
        assert.equal((error as { exitCode?: number }).exitCode, EXIT.AUTH)
        return true
      }
    )
    assert.equal(call, 1)
  } finally {
    await stub.close()
  }
})
