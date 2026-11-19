import assert from 'node:assert/strict'
import { test } from 'node:test'
import { PanelReducer, type PanelEvent } from '../src/shared/reducer.ts'
import type { RunEventItem } from '../src/shared/types.ts'

function item(seq: number, type: string, payload: unknown): RunEventItem {
  return { seq, type, payload: typeof payload === 'string' ? payload : JSON.stringify(payload), createTime: 'x' }
}

function content(events: PanelEvent[]): string {
  return events.filter((event) => event.type === 'content').map((event) => event.text).join('')
}

test('SSE 归约为面板事件并保存完成状态', () => {
  const reducer = new PanelReducer()
  assert.deepEqual(reducer.handleSse('session_id', '12'), [{ type: 'session', sessionId: 12 }])
  assert.deepEqual(reducer.handleSse('run_id', '34'), [{ type: 'run', runId: 34 }])
  assert.deepEqual(reducer.handleSse('turn_id', 'turn-a'), [{ type: 'turn', turnId: 'turn-a' }])
  assert.deepEqual(reducer.handleSse('content', '{"text":"hello"}'), [{ type: 'content', text: 'hello' }])
  assert.deepEqual(reducer.handleSse('tool_call', '{"tool":"read_file","args":"{}"}'), [
    { type: 'tool', phase: 'call', tool: 'read_file', args: '{}' }
  ])
  assert.deepEqual(reducer.handleSse('file_change', '{"path":"a.ts","additions":2,"deletions":1}'), [
    { type: 'file_change', path: 'a.ts', additions: 2, deletions: 1 }
  ])
  reducer.handleSse('completion', '{"status":"COMPLETED","filesChanged":true}')
  assert.deepEqual(reducer.handleSse('done', '[DONE]'), [
    { type: 'done', completion: { status: 'COMPLETED', filesChanged: true } }
  ])
  assert.equal(reducer.state.contentText, 'hello')
  assert.equal(reducer.state.done, true)
})

test('断流回放按累计正文长度取后缀，不重复正文', () => {
  const reducer = new PanelReducer()
  const live = reducer.handleSse('content', '{"text":"abc"}')
  assert.equal(content(live), 'abc')
  assert.deepEqual(reducer.handleReplay(item(1, 'MODEL_CHUNK', { text: 'abc', length: 3 })), [])
  const replayed = reducer.handleReplay(item(2, 'MODEL_CHUNK', { text: 'abcdef', length: 6 }))
  assert.equal(content(replayed), 'def')
  assert.equal(reducer.state.contentText, 'abcdef')
  assert.equal(reducer.state.lastSeq, 2)
})

test('工具与审批按指纹计数去重，重复次数用尽后仍可渲染新事件', () => {
  const reducer = new PanelReducer()
  const call = '{"tool":"read_file","args":"{}"}'
  reducer.handleSse('tool_call', call)
  reducer.handleSse('tool_call', call)
  assert.deepEqual(reducer.handleReplay(item(1, 'TOOL_CALL', call)), [])
  assert.deepEqual(reducer.handleReplay(item(2, 'TOOL_CALL', call)), [])
  assert.equal(reducer.handleReplay(item(3, 'TOOL_CALL', call)).length, 1)

  const approval = '{"approvalId":"a1","tool":"write_file","risk":"WRITE","preview":null,"expiresAt":null}'
  reducer.handleSse('approval_required', approval)
  assert.deepEqual(reducer.handleReplay(item(4, 'APPROVAL_REQUESTED', approval)), [])
  assert.equal(reducer.handleReplay(item(5, 'APPROVAL_REQUESTED', approval)).length, 1)
})

test('工具结果与完成事件在 SSE 阶段记录指纹，回放不重复', () => {
  const reducer = new PanelReducer()
  reducer.handleSse('tool_result', '{"tool":"read_file","success":true,"costMs":5}')
  reducer.handleSse('completion', '{"status":"COMPLETED"}')
  assert.deepEqual(reducer.handleReplay(item(1, 'TOOL_RESULT', { tool: 'read_file', success: true })), [])
  assert.deepEqual(reducer.handleReplay(item(2, 'COMPLETION', { status: 'COMPLETED' })), [])
  assert.equal(reducer.handleReplay(item(3, 'TOOL_RESULT', { tool: 'read_file', success: true })).length, 1)
  assert.equal(reducer.state.toolResults, 2)
  assert.equal(reducer.state.completion?.status, 'COMPLETED')
})

test('审批处理清除对应待审批卡片', () => {
  const reducer = new PanelReducer()
  reducer.handleSse('approval_required', '{"approvalId":"a1","tool":"write_file","risk":"WRITE","preview":null,"expiresAt":null}')
  assert.deepEqual(reducer.handleSse('approval_resolved', '{"approvalId":"a1","decision":"APPROVE"}'), [
    { type: 'approval_resolved', approvalId: 'a1', decision: 'APPROVE' }
  ])
  assert.equal(reducer.state.pendingApproval, null)
})

test('精简回放不会覆盖历史接口恢复的审批元数据', () => {
  const reducer = new PanelReducer()
  reducer.state.pendingApproval = {
    approvalId: 'a1',
    tool: 'write_file',
    risk: 'WRITE',
    preview: { path: 'note.txt' },
    expiresAt: 123
  }
  assert.deepEqual(reducer.handleReplay(item(1, 'APPROVAL_REQUESTED', {
    approvalId: 'a1', tool: 'write_file'
  })), [{
    type: 'approval',
    approval: {
      approvalId: 'a1',
      tool: 'write_file',
      risk: 'WRITE',
      preview: { path: 'note.txt' },
      expiresAt: 123
    }
  }])
})
