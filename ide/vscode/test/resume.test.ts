import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiError, type HttpContext } from '../src/shared/http.ts'
import { PanelReducer, type PanelEvent } from '../src/shared/reducer.ts'
import { resumeRun } from '../src/shared/resume.ts'
import type { RunEventItem } from '../src/shared/types.ts'

function item(seq: number, type: string, payload: unknown): RunEventItem {
  return { seq, type, payload: JSON.stringify(payload), createTime: 'x' }
}

function body(state: string, events: RunEventItem[]) {
  return {
    code: 200,
    message: 'success',
    data: {
      runId: 7, state, errorCode: null, errorMessage: null,
      promptTokens: null, completionTokens: null, totalTokens: null, events
    },
    timestamp: 1
  }
}

test('续传只消费 seq > lastSeq，下一次轮询携带最新 sinceSeq', async () => {
  let call = 0
  const queries: Array<string | null> = []
  const http: HttpContext = {
    baseUrl: 'http://127.0.0.1:8080',
    token: 'lgc_test',
    fetchImpl: async (input) => {
      call += 1
      const url = new URL(String(input))
      queries.push(url.searchParams.get('sinceSeq'))
      if (call === 1) {
        return Response.json(body('RUNNING', [
          item(1, 'MODEL_CHUNK', { text: 'abc' }),
          item(2, 'TOOL_CALL', { tool: 'read_file', args: '{}' })
        ]))
      }
      return Response.json(body('SUCCEEDED', [
        item(2, 'TOOL_CALL', { tool: 'read_file', args: '{}' }),
        item(3, 'MODEL_CHUNK', { text: 'abcdef' }),
        item(4, 'COMPLETION', { status: 'COMPLETED' })
      ]))
    }
  }
  const sleeps: number[] = []
  const events: PanelEvent[] = []
  const reducer = new PanelReducer()
  const outcome = await resumeRun(
    { http, pollMs: 5, sleep: async (ms) => { sleeps.push(ms) } },
    reducer,
    { runId: 7, onEvents: (batch) => events.push(...batch) }
  )
  assert.equal(outcome.state, 'SUCCEEDED')
  assert.equal(outcome.lastSeq, 4)
  assert.deepEqual(queries, [null, '2'])
  assert.deepEqual(sleeps, [5])
  assert.equal(events.filter((event) => event.type === 'content').map((event) => event.text).join(''), 'abcdef')
  assert.equal(events.filter((event) => event.type === 'tool').length, 1)
})

test('网络错误最多重试三次，假时钟验证 1/2/4 秒退避', async () => {
  let calls = 0
  const http: HttpContext = {
    baseUrl: 'http://127.0.0.1:8080',
    fetchImpl: async () => {
      calls += 1
      if (calls <= 3) throw new TypeError('socket closed')
      return Response.json(body('SUCCEEDED', []))
    }
  }
  const sleeps: number[] = []
  const retries: Array<[number, number]> = []
  const outcome = await resumeRun(
    { http, sleep: async (ms) => { sleeps.push(ms) } },
    new PanelReducer(),
    { runId: 7, initialSeq: 9, onRetry: (seq, attempt) => retries.push([seq, attempt]) }
  )
  assert.equal(outcome.state, 'SUCCEEDED')
  assert.equal(calls, 4)
  assert.deepEqual(sleeps, [1000, 2000, 4000])
  assert.deepEqual(retries, [[9, 1], [9, 2], [9, 3]])
})

test('第四次连续失败不再重试', async () => {
  let calls = 0
  const http: HttpContext = {
    baseUrl: 'http://127.0.0.1:8080',
    fetchImpl: async () => {
      calls += 1
      throw new TypeError('offline')
    }
  }
  const sleeps: number[] = []
  await assert.rejects(
    resumeRun({ http, sleep: async (ms) => { sleeps.push(ms) } }, new PanelReducer(), { runId: 7 }),
    (error: unknown) => error instanceof ApiError && error.kind === 'network'
  )
  assert.equal(calls, 4)
  assert.deepEqual(sleeps, [1000, 2000, 4000])
})

test('401/403 与业务错误不重试', async () => {
  for (const status of [401, 403, 400]) {
    let calls = 0
    let sleeps = 0
    const http: HttpContext = {
      baseUrl: 'http://127.0.0.1:8080',
      fetchImpl: async () => {
        calls += 1
        return Response.json({ message: status === 403 ? '缺少 scope: run:read' : '不能续传' }, { status })
      }
    }
    await assert.rejects(
      resumeRun({ http, sleep: async () => { sleeps += 1 } }, new PanelReducer(), { runId: 7 }),
      (error: unknown) => error instanceof ApiError && error.retryable === false
    )
    assert.equal(calls, 1)
    assert.equal(sleeps, 0)
  }
})
