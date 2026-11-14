import { afterEach, describe, expect, it, vi } from 'vitest';
import { parseSSE, streamChat } from './sse';

type FakeReader = {
  read: () => Promise<{ done: boolean; value?: Uint8Array }>;
  cancel: () => Promise<void>;
};

function fakeBody(chunks: string[]): { body: ReadableStream<Uint8Array>; isCancelled: () => boolean } {
  const encoder = new TextEncoder();
  let index = 0;
  let cancelled = false;
  const reader: FakeReader = {
    read: async () => {
      if (index >= chunks.length) return { done: true };
      return { done: false, value: encoder.encode(chunks[index++]) };
    },
    cancel: async () => {
      cancelled = true;
    }
  };
  const body = { getReader: () => reader } as unknown as ReadableStream<Uint8Array>;
  return { body, isCancelled: () => cancelled };
}

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

function collect(body: ReadableStream<Uint8Array>) {
  const events: Array<[string, string]> = [];
  return parseSSE(body, (type, data) => {
    events.push([type, data]);
  }).then(() => events);
}

describe('parseSSE', () => {
  it('把同一个事件的多行 data 用换行合并', async () => {
    const { body } = fakeBody(['event: content\ndata: line-1\ndata: line-2\n\n']);
    await expect(collect(body)).resolves.toEqual([['content', 'line-1\nline-2']]);
  });

  it('兼容 CRLF 换行', async () => {
    const { body } = fakeBody(['event: content\r\ndata: hello\r\n\r\n']);
    await expect(collect(body)).resolves.toEqual([['content', 'hello']]);
  });

  it('收到 done 后立即停止读取并取消连接', async () => {
    const { body, isCancelled } = fakeBody([
      'event: done\ndata: ok\n\n',
      'event: content\ndata: should-not-arrive\n\n'
    ]);
    const events: Array<[string, string]> = [];
    await parseSSE(body, (type, data) => {
      events.push([type, data]);
      return type === 'done';
    });
    expect(events).toEqual([['done', 'ok']]);
    expect(isCancelled()).toBe(true);
  });

  it('收到 error 后立即停止并取消连接', async () => {
    const { body, isCancelled } = fakeBody(['event: error\ndata: boom\n\n']);
    const events: Array<[string, string]> = [];
    await parseSSE(body, (type, data) => {
      events.push([type, data]);
      return type === 'error';
    });
    expect(events).toEqual([['error', 'boom']]);
    expect(isCancelled()).toBe(true);
  });

  it('流被掐断但无终止事件时，仍派发缓冲区剩余事件', async () => {
    const { body, isCancelled } = fakeBody(['event: content\ndata: tail']);
    await expect(collect(body)).resolves.toEqual([['content', 'tail']]);
    expect(isCancelled()).toBe(false);
  });

  it('忽略 id、retry 与注释行', async () => {
    const { body } = fakeBody(['id: 42\nretry: 500\n: keep-alive\nevent: content\ndata: x\n\n']);
    await expect(collect(body)).resolves.toEqual([['content', 'x']]);
  });

  it('data 冒号后仅去掉一个空格', async () => {
    const { body } = fakeBody(['event: content\ndata:  two-spaces\n\n']);
    await expect(collect(body)).resolves.toEqual([['content', ' two-spaces']]);
  });
});

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
});
