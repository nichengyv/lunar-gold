import assert from 'node:assert/strict'
import { test } from 'node:test'
import { EXIT } from '../src/errors.ts'
import { runCommand } from '../src/commands/run.ts'
import { errorResponse, jsonResponse, sseResponse, sseText, startStub, testConfig, testContext } from './helpers.ts'

function ctxFor(stubBaseUrl: string, json = false) {
  return testContext({ config: testConfig({ baseUrl: stubBaseUrl, json }) })
}

test('run 正常 done：content 原样输出，退出 0', async () => {
  const stub = await startStub((request, response) => {
    if (request.path === '/api/agent/chat/stream') {
      sseResponse(response, sseText([
        { event: 'session_id', data: '1' },
        { event: 'run_id', data: '2' },
        { event: 'turn_id', data: 'turn-abc' },
        { event: 'content', data: '{"text":"你好，"} ' },
        { event: 'content', data: '{"text":"世界"}' },
        { event: 'done', data: '[DONE]' }
      ]))
      return
    }
    errorResponse(response, 404, 'unexpected')
  })
  try {
    const ctx = ctxFor(stub.baseUrl)
    const code = await runCommand(ctx, { prompt: 'hi' })
    assert.equal(code, EXIT.OK)
    assert.match(ctx.output.out, /你好，世界/)
    assert.match(ctx.output.out, /session: 1/)
    assert.match(ctx.output.out, /run: 2/)
    const body = JSON.parse(stub.requests[0].body) as Record<string, unknown>
    assert.deepEqual(body, { prompt: 'hi' })
  } finally {
    await stub.close()
  }
})

test('run 收到 error：退出 5，message 来自后端', async () => {
  const stub = await startStub((request, response) => {
    sseResponse(response, sseText([
      { event: 'session_id', data: '1' },
      { event: 'run_id', data: '2' },
      { event: 'error', data: '这次回答没有完成，请稍后再试一次' }
    ]))
  })
  try {
    const ctx = ctxFor(stub.baseUrl)
    const code = await runCommand(ctx, { prompt: 'hi' })
    assert.equal(code, EXIT.INCOMPLETE)
    assert.match(ctx.output.err + ctx.output.out, /这次回答没有完成/)
  } finally {
    await stub.close()
  }
})

test('run 中途断流：按 sinceSeq 续看且正文不重复', async () => {
  let eventsCalls = 0
  const stub = await startStub((request, response) => {
    if (request.path === '/api/agent/chat/stream') {
      sseResponse(response, sseText([
        { event: 'session_id', data: '1' },
        { event: 'run_id', data: '2' },
        { event: 'content', data: '{"text":"abc"}' }
      ]))
      return
    }
    if (request.path === '/api/agent/runs/2/events') {
      eventsCalls += 1
      assert.equal(request.query.get('sinceSeq'), null)
      jsonResponse(response, {
        runId: 2,
        state: 'SUCCEEDED',
        errorCode: null,
        errorMessage: null,
        promptTokens: 1,
        completionTokens: 1,
        totalTokens: 2,
        events: [
          { seq: 1, type: 'MODEL_CHUNK', payload: '{"text":"abc","length":3}', createTime: 'x' },
          { seq: 2, type: 'MODEL_CHUNK', payload: '{"text":"abcdef","length":6}', createTime: 'x' }
        ]
      })
      return
    }
    errorResponse(response, 404, 'unexpected')
  })
  try {
    const ctx = ctxFor(stub.baseUrl)
    const code = await runCommand(ctx, { prompt: 'hi' })
    assert.equal(code, EXIT.OK)
    assert.equal(eventsCalls, 1)
    assert.match(ctx.output.err, /\[resume from seq=0\]/)
    assert.equal(ctx.output.out.includes('abcabc'), false)
    assert.match(ctx.output.out, /abcdef/)
  } finally {
    await stub.close()
  }
})

test('run 遇到审批：打印可复制命令并退出 4', async () => {
  const stub = await startStub((request, response) => {
    sseResponse(response, sseText([
      { event: 'session_id', data: '1' },
      { event: 'run_id', data: '2' },
      {
        event: 'approval_required',
        data: JSON.stringify({
          approvalId: 'ap-1',
          tool: 'run_command',
          preview: { command: 'npm test' },
          risk: 'EXECUTE',
          expiresAt: 1760000000000
        })
      }
    ]))
  })
  try {
    const ctx = ctxFor(stub.baseUrl)
    const code = await runCommand(ctx, { prompt: 'hi' })
    assert.equal(code, EXIT.APPROVAL)
    assert.match(ctx.output.out, /lg approve ap-1/)
    assert.match(ctx.output.out, /lg approve ap-1 --deny/)
    assert.match(ctx.output.err, /任务仍在等待审批/)
    assert.equal(stub.requests.some((item) => item.path.startsWith('/api/agent/approvals/')), false)
  } finally {
    await stub.close()
  }
})

test('--auto-approve low：WRITE 自动批准并继续到 done', async () => {
  const stub = await startStub((request, response) => {
    if (request.path === '/api/agent/chat/stream') {
      sseResponse(response, sseText([
        { event: 'session_id', data: '1' },
        { event: 'run_id', data: '2' },
        {
          event: 'approval_required',
          data: JSON.stringify({
            approvalId: 'ap-1',
            tool: 'write_file',
            preview: { path: 'a.txt' },
            risk: 'WRITE',
            expiresAt: 1760000000000
          })
        },
        { event: 'content', data: '{"text":"完成"}' },
        { event: 'done', data: '[DONE]' }
      ]))
      return
    }
    if (request.path === '/api/agent/approvals/ap-1') {
      jsonResponse(response, null)
      return
    }
    errorResponse(response, 404, 'unexpected')
  })
  try {
    const ctx = ctxFor(stub.baseUrl)
    const code = await runCommand(ctx, { prompt: 'hi', autoApproveLow: true })
    assert.equal(code, EXIT.OK)
    const approval = stub.requests.find((item) => item.path === '/api/agent/approvals/ap-1')
    assert.ok(approval, '应当调用审批接口')
    assert.equal(approval?.method, 'POST')
    assert.deepEqual(JSON.parse(approval?.body ?? '{}'), { decision: 'APPROVE', remember: 'NONE' })
    assert.match(ctx.output.out, /完成/)
  } finally {
    await stub.close()
  }
})

test('--auto-approve low：EXECUTE 风险不自动批准，退出 4', async () => {
  const stub = await startStub((request, response) => {
    sseResponse(response, sseText([
      { event: 'session_id', data: '1' },
      { event: 'run_id', data: '2' },
      {
        event: 'approval_required',
        data: JSON.stringify({
          approvalId: 'ap-2',
          tool: 'run_command',
          preview: { command: 'rm -rf build' },
          risk: 'EXECUTE',
          expiresAt: 1760000000000
        })
      }
    ]))
  })
  try {
    const ctx = ctxFor(stub.baseUrl)
    const code = await runCommand(ctx, { prompt: 'hi', autoApproveLow: true })
    assert.equal(code, EXIT.APPROVAL)
    assert.equal(stub.requests.some((item) => item.path.startsWith('/api/agent/approvals/')), false)
  } finally {
    await stub.close()
  }
})

test('--json：NDJSON 事件与 result.exitCode', async () => {
  const stub = await startStub((request, response) => {
    sseResponse(response, sseText([
      { event: 'session_id', data: '1' },
      { event: 'run_id', data: '2' },
      { event: 'turn_id', data: 'turn-abc' },
      { event: 'tool_call', data: '{"tool":"read_file","args":"{}","status":"calling"}' },
      { event: 'content', data: '{"text":"ok"}' },
      { event: 'done', data: '[DONE]' }
    ]))
  })
  try {
    const ctx = ctxFor(stub.baseUrl, true)
    const code = await runCommand(ctx, { prompt: 'hi' })
    assert.equal(code, EXIT.OK)
    const lines = ctx.output.out.trim().split('\n').map((line) => JSON.parse(line) as Record<string, unknown>)
    assert.deepEqual(lines.map((line) => line.type), ['session', 'run', 'tool', 'content', 'result'])
    const result = lines.at(-1)
    assert.equal(result?.exitCode, EXIT.OK)
    assert.equal(result?.sessionId, 1)
    assert.equal(result?.runId, 2)
    assert.equal(result?.turnId, 'turn-abc')
    assert.equal(result?.filesChanged, false)
  } finally {
    await stub.close()
  }
})

test('--json：approval 行字段稳定', async () => {
  const stub = await startStub((request, response) => {
    sseResponse(response, sseText([
      { event: 'session_id', data: '1' },
      { event: 'run_id', data: '2' },
      {
        event: 'approval_required',
        data: JSON.stringify({
          approvalId: 'ap-9',
          tool: 'write_file',
          preview: { path: 'note.txt', additions: 1, deletions: 0 },
          risk: 'WRITE',
          expiresAt: 1760000000000
        })
      }
    ]))
  })
  try {
    const ctx = ctxFor(stub.baseUrl, true)
    const code = await runCommand(ctx, { prompt: 'hi' })
    assert.equal(code, EXIT.APPROVAL)
    const lines = ctx.output.out.trim().split('\n').map((line) => JSON.parse(line) as Record<string, unknown>)
    assert.deepEqual(lines.map((line) => line.type), ['session', 'run', 'approval', 'result'])
    assert.deepEqual(lines[2], {
      type: 'approval',
      approvalId: 'ap-9',
      tool: 'write_file',
      risk: 'WRITE',
      expiresAt: 1760000000000,
      preview: { path: 'note.txt', additions: 1, deletions: 0 }
    })
    assert.equal(lines[3]?.exitCode, EXIT.APPROVAL)
  } finally {
    await stub.close()
  }
})

test('--json：断流续看输出 resume 行', async () => {
  const stub = await startStub((request, response) => {
    if (request.path === '/api/agent/chat/stream') {
      sseResponse(response, sseText([
        { event: 'session_id', data: '1' },
        { event: 'run_id', data: '2' },
        { event: 'content', data: '{"text":"ab"}' }
      ]))
      return
    }
    if (request.path === '/api/agent/runs/2/events') {
      jsonResponse(response, {
        runId: 2,
        state: 'SUCCEEDED',
        errorCode: null,
        errorMessage: null,
        promptTokens: 1,
        completionTokens: 1,
        totalTokens: 2,
        events: [
          { seq: 1, type: 'MODEL_CHUNK', payload: '{"text":"ab","length":2}', createTime: 'x' }
        ]
      })
      return
    }
    errorResponse(response, 404, 'unexpected')
  })
  try {
    const ctx = ctxFor(stub.baseUrl, true)
    const code = await runCommand(ctx, { prompt: 'hi' })
    assert.equal(code, EXIT.OK)
    const lines = ctx.output.out.trim().split('\n').map((line) => JSON.parse(line) as Record<string, unknown>)
    assert.deepEqual(lines.map((line) => line.type), ['session', 'run', 'content', 'resume', 'result'])
    assert.deepEqual(lines[3], { type: 'resume', sinceSeq: 0 })
  } finally {
    await stub.close()
  }
})
