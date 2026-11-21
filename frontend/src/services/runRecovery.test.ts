import { describe, expect, it } from 'vitest';
import type { RunEventItem } from '../api/agent';
import { replayRunEvents } from './runRecovery';

function event(type: string, payload: unknown, seq = 1, createTime = '2026-11-21T10:00:00Z'): RunEventItem {
  return { seq, type, payload: JSON.stringify(payload), createTime };
}

describe('replayRunEvents', () => {
  it('取最后一个 MODEL_CHUNK 检查点并标记截断', () => {
    const recovered = replayRunEvents([
      event('MODEL_CHUNK', { text: '第一段', length: 3 }, 1),
      event('MODEL_CHUNK', { text: '第一段第二段', length: 20 }, 2)
    ]);

    expect(recovered.text).toBe('第一段第二段');
    expect(recovered.truncated).toBe(true);
  });

  it('按调用顺序重建工具并回填成功/失败结果', () => {
    const recovered = replayRunEvents([
      event('TOOL_CALL', { tool: 'read_file', args: '{"path":"a.txt"}' }, 1),
      event('TOOL_RESULT', { tool: 'read_file', success: true, content: '内容' }, 2),
      event('TOOL_CALL', { tool: 'run_command', args: '{"command":"ls"}' }, 3),
      event('TOOL_RESULT', { tool: 'run_command', success: false, error: '退出码 1' }, 4)
    ]);

    expect(recovered.tools.map((t) => [t.name, t.status, t.result, t.error])).toEqual([
      ['read_file', 'success', '内容', undefined],
      ['run_command', 'error', undefined, '退出码 1']
    ]);
  });

  it('文件变更由成功的写工具推导，同一路径只登记一次', () => {
    const recovered = replayRunEvents([
      event('TOOL_CALL', { tool: 'write_file', args: '{"path":"src/a.ts"}' }, 1),
      event('TOOL_RESULT', { tool: 'write_file', success: true }, 2),
      event('TOOL_CALL', { tool: 'edit_file', args: '{"path":"src/a.ts"}' }, 3),
      event('TOOL_RESULT', { tool: 'edit_file', success: true }, 4),
      event('TOOL_CALL', { tool: 'write_file', args: '{"path":"src/b.ts"}' }, 5),
      event('TOOL_RESULT', { tool: 'write_file', success: false }, 6),
      event('TOOL_CALL', { tool: 'delete_file', args: '{"path":"src/c.ts"}' }, 7),
      event('TOOL_RESULT', { tool: 'delete_file', success: true }, 8)
    ]);

    expect(recovered.changes).toEqual([
      { path: 'src/a.ts', action: 'modify', source: 'edit_file', additions: 0, deletions: 0, diff: '' },
      { path: 'src/c.ts', action: 'delete', source: 'delete_file', additions: 0, deletions: 0, diff: '' }
    ]);
  });

  it('计划事件恢复最新快照并规范非法状态', () => {
    const recovered = replayRunEvents([
      event('PLAN_UPDATED', {
        steps: [
          { title: '读取项目', status: 'completed' },
          { title: '修改接口', status: 'in_progress' },
          { title: '异常状态', status: 'unknown' }
        ]
      }, 1, '2026-11-21T02:00:00Z')
    ]);

    expect(recovered.plan?.steps).toEqual([
      { title: '读取项目', status: 'completed' },
      { title: '修改接口', status: 'in_progress' },
      { title: '异常状态', status: 'pending' }
    ]);
    expect(recovered.plan?.updatedAt).toBe(Date.parse('2026-11-21T02:00:00Z'));
  });

  it('审批请求与处理结果成对恢复，风险按工具推断', () => {
    const pending = replayRunEvents([
      event('APPROVAL_REQUESTED', { approvalId: 'ap-1', tool: 'delete_file' }, 1)
    ]);
    expect(pending.approval).toEqual({
      approvalId: 'ap-1',
      tool: 'delete_file',
      risk: 'DESTRUCTIVE',
      resolved: false
    });

    const resolved = replayRunEvents([
      event('APPROVAL_REQUESTED', { approvalId: 'ap-1', tool: 'write_file' }, 1),
      event('APPROVAL_RESOLVED', { decision: 'APPROVE' }, 2)
    ]);
    expect(resolved.approval?.resolved).toBe(true);
    expect(resolved.approval?.risk).toBe('WRITE');
  });

  it('未回答的询问保留，回答后清除', () => {
    const waiting = replayRunEvents([
      event('USER_QUESTION', {
        questionId: 'q-1',
        question: '选哪个？',
        options: [{ label: 'A' }, { label: 'B' }],
        multiSelect: false
      }, 1)
    ]);
    expect(waiting.userQuestion?.questionId).toBe('q-1');

    const answered = replayRunEvents([
      event('USER_QUESTION', { questionId: 'q-1', question: '选哪个？' }, 1),
      event('USER_ANSWERED', { questionId: 'q-1', answer: 'A' }, 2)
    ]);
    expect(answered.userQuestion).toBeUndefined();
  });

  it('完成、诊断、验证事件原样回填', () => {
    const recovered = replayRunEvents([
      event('COMPLETION', { status: 'SUCCESS', reason: 'DONE' }, 1),
      event('DIAGNOSTICS', { ok: true, issues: [] }, 2),
      event('VERIFY', { passed: true }, 3)
    ]);

    expect(recovered.completion).toMatchObject({ status: 'SUCCESS', reason: 'DONE' });
    expect(recovered.diagnostics).toMatchObject({ ok: true });
    expect(recovered.verify).toMatchObject({ passed: true });
  });

  it('坏 JSON 事件被跳过，不影响后续恢复', () => {
    const recovered = replayRunEvents([
      { seq: 1, type: 'PLAN_UPDATED', payload: '{bad json' },
      event('MODEL_CHUNK', { text: '正常内容', length: 4 }, 2)
    ]);

    expect(recovered.text).toBe('正常内容');
    expect(recovered.plan).toBeUndefined();
  });
});
