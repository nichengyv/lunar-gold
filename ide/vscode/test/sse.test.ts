import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import { parseSSE, streamFromText, type SseEvent } from '../src/shared/sse.ts'

// 只读引用 CLI fixture，不复制协议样例。
const FIXTURES = join(import.meta.dirname, '..', '..', '..', 'cli', 'test', 'fixtures', 'sse')

function read(name: string): string {
  return readFileSync(join(FIXTURES, `${name}.txt`), 'utf8')
}

async function collect(text: string): Promise<SseEvent[]> {
  const events: SseEvent[] = []
  await parseSSE(streamFromText(text), (event) => {
    events.push(event)
    if (event.type === 'done') return true
  })
  return events
}

test('basic：事件名与 data 原样，done 终结', async () => {
  assert.deepEqual(await collect(read('basic')), [
    { type: 'session_id', data: '12' },
    { type: 'run_id', data: '34' },
    { type: 'content', data: '{"text":"hello"}' },
    { type: 'done', data: '[DONE]' }
  ])
})

test('crlf：CRLF 行尾一样能解析', async () => {
  const events = await collect(read('crlf'))
  assert.deepEqual(events.map((event) => event.type), ['session_id', 'content', 'done'])
  assert.equal(events[0].data, '9')
  assert.equal(events[1].data, '{"text":"crlf"}')
})

test('multiline-data：多行 data 用换行拼接', async () => {
  const events = await collect(read('multiline-data'))
  assert.deepEqual(events, [{ type: 'content', data: 'hello\nworld' }])
})

test('heartbeat：注释帧不产生事件', async () => {
  assert.deepEqual(await collect(read('heartbeat')), [{ type: 'session_id', data: '7' }])
})

test('tail-without-blank-line：流尾残余事件照常派发', async () => {
  assert.deepEqual(await collect(read('tail-without-blank-line')), [
    { type: 'content', data: '{"text":"tail"}' }
  ])
})

test('done：收到 done 立即停止，不派发后续事件', async () => {
  assert.deepEqual(await collect(read('done')), [{ type: 'done', data: '[DONE]' }])
})

test('data: 只去掉冒号后的一个空格', async () => {
  const events = await collect('event: content\ndata:  two spaces\n\n')
  assert.equal(events[0].data, ' two spaces')
})

test('没有 event 行的 data 不产生事件', async () => {
  assert.deepEqual(await collect('data: orphan\n\n'), [])
})
