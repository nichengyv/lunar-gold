import { describe, expect, it } from 'vitest';
import { parseSSE } from './sse';

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

