import { afterEach, describe, expect, it, vi } from 'vitest';
import { streamChat } from './streamService';

function realBody(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let index = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (index >= chunks.length) {
        controller.close();
        return;
      }
      controller.enqueue(encoder.encode(chunks[index++]));
    }
  });
}

describe('streamChat', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('解析会话、运行、轮次、正文与审批事件', async () => {
    const payloads = [
      'event: session_id\ndata: 7\n\n',
      'event: run_id\ndata: 11\n\n',
      'event: turn_id\ndata: turn-abc\n\n',
      'event: content\ndata: {"text":"A"}\n\n',
      'event: content\ndata: {"text":"B"}\n\n',
      'event: approval_required\ndata: {"approvalId":"ap-1","tool":"write_file","risk":"WRITE","expiresAt":1}\n\n',
      'event: usage\ndata: {"promptTokens":1200,"completionTokens":300,"totalTokens":1500,"round":2,"maxRounds":30,"maxTokens":200000,"elapsedMs":45210,"maxElapsedMs":600000}\n\n',
      'event: done\ndata: {}\n\n'
    ];
    vi.stubGlobal('fetch', vi.fn(async () => new Response(realBody(payloads), { status: 200 })));

    const sessionIds: number[] = [];
    const runIds: number[] = [];
    const turnIds: string[] = [];
    const contents: string[] = [];
    const approvals: string[] = [];
    const usages: Array<Record<string, number>> = [];
    let doneText = '';

    await streamChat(
      { message: 'hi' } as never,
      {
        onSessionId: (id) => sessionIds.push(id),
        onRunId: (id) => runIds.push(id),
        onTurnId: (id) => turnIds.push(id),
        onContent: (full, chunk) => contents.push(`${full}|${chunk}`),
        onApprovalRequired: (approval) => approvals.push(approval.approvalId),
        onUsage: (usage) => usages.push(usage as unknown as Record<string, number>),
        onDone: (full) => {
          doneText = full;
        }
      }
    );

    expect(sessionIds).toEqual([7]);
    expect(runIds).toEqual([11]);
    expect(turnIds).toEqual(['turn-abc']);
    expect(contents).toEqual(['A|A', 'AB|B']);
    expect(approvals).toEqual(['ap-1']);
    expect(usages).toEqual([
      {
        promptTokens: 1200,
        completionTokens: 300,
        totalTokens: 1500,
        round: 2,
        maxRounds: 30,
        maxTokens: 200000,
        elapsedMs: 45210,
        maxElapsedMs: 600000
      }
    ]);
    expect(doneText).toBe('AB');
  });

  it('P5-10-2：审批结果解析决策来源与批次', async () => {
    const payloads = [
      'event: approval_resolved\ndata: {"approvalId":"ap-1","decision":"APPROVE","source":"BATCH","batchId":"bt-1"}\n\n',
      'event: done\ndata: {}\n\n'
    ];
    vi.stubGlobal('fetch', vi.fn(async () => new Response(realBody(payloads), { status: 200 })));

    const resolved: Array<[string, string, string | undefined, string | undefined]> = [];
    await streamChat(
      { message: 'hi' } as never,
      {
        onApprovalResolved: (approvalId, decision, source, batchId) =>
          resolved.push([approvalId, decision, source, batchId])
      }
    );

    expect(resolved).toEqual([['ap-1', 'APPROVE', 'BATCH', 'bt-1']]);
  });

  it('R7c：解析子任务状态事件', async () => {
    const payloads = [
      'event: subtask\ndata: {"type":"status","parentRunId":11,"childRunId":31,"subtaskId":"s1","status":"RUNNING","message":"开始"}\n\n',
      'event: done\ndata: {}\n\n'
    ];
    vi.stubGlobal('fetch', vi.fn(async () => new Response(realBody(payloads), { status: 200 })));

    const updates: Array<Record<string, unknown>> = [];
    await streamChat(
      { message: 'hi' } as never,
      {
        onSubtask: (update) => updates.push(update as unknown as Record<string, unknown>)
      }
    );

    expect(updates).toEqual([
      expect.objectContaining({
        type: 'status',
        childRunId: 31,
        subtaskId: 's1',
        status: 'RUNNING'
      })
    ]);
  });

  it('R7c：子任务审批事件保留 childRunId 归属', async () => {
    const payloads = [
      'event: approval_required\ndata: {"approvalId":"ap-c","tool":"write_file","risk":"WRITE","runId":31,"subtaskId":"s1"}\n\n',
      'event: done\ndata: {}\n\n'
    ];
    vi.stubGlobal('fetch', vi.fn(async () => new Response(realBody(payloads), { status: 200 })));

    const approvals: Array<Record<string, unknown>> = [];
    await streamChat(
      { message: 'hi' } as never,
      {
        onApprovalRequired: (approval) =>
          approvals.push(approval as unknown as Record<string, unknown>)
      }
    );

    expect(approvals).toEqual([
      expect.objectContaining({ approvalId: 'ap-c', runId: 31, subtaskId: 's1' })
    ]);
  });
});
