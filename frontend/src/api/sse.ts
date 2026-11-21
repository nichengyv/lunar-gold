/**
 * SSE 流式请求封装。
 * 后端 /api/agent/chat/stream 是 POST 方式的 SSE 流，原生 EventSource 只支持 GET，
 * 因此必须用 fetch + ReadableStream 手动解析。
 */
import type { StreamPayload } from '../types/chat';

/**
 * 逐事件解析 SSE 流（支持 CRLF、多行 data）。
 *
 * 关键约定：onEvent 返回 true 表示「本次流已终结」（收到 done / error）。
 * 此时必须立刻停止读取并取消连接——不能依赖服务端关连接，
 * 否则一旦后端没写结束块，这里会永远 await 下去，界面表现为一直"正在生成"、
 * 输入框和会话切换全部被锁死。
 */
export async function parseSSE(
  body: ReadableStream<Uint8Array>,
  onEvent: (type: string, data: string) => boolean | void
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let eventType = '';
  let dataLines: string[] = [];
  let terminal = false;

  // SSE 以「空行」为一个事件结束标记：必须攒够整块再派发，
  // 不能逐行派发（逐行会把 event 行当成结束，导致数据整体错位）。
  const handleLine = (line: string) => {
    if (line === '') {
      if (eventType && onEvent(eventType, dataLines.join('\n')) === true) terminal = true;
      eventType = '';
      dataLines = [];
      return;
    }
    if (line.startsWith('event:')) {
      eventType = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      // 按规范只去掉冒号后的一个空格，其余内容原样保留
      dataLines.push(line.slice(5).replace(/^ /, ''));
    }
    // id: / retry: / 注释行（:开头）忽略
  };

  try {
    while (!terminal) {
      const { done, value } = await reader.read();
      if (done) {
        buffer += decoder.decode();
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        handleLine(line);
        if (terminal) break;
      }
    }

    // 流结束时处理残余内容：未闭合的最后一个事件也要照常派发
    if (!terminal) {
      if (buffer) handleLine(buffer);
      if (eventType) onEvent(eventType, dataLines.join('\n'));
    }
  } finally {
    if (terminal) {
      // 主动断开：既避免浏览器继续等待，也让后端尽快感知客户端已结束
      await reader.cancel().catch(() => undefined);
    }
  }
}

/** 登录态失效：由 SSEService 负责清理本地凭证并跳转登录页 */
export class SseAuthError extends Error {
  constructor(message = '登录已过期，请重新登录') {
    super(message);
    this.name = 'SseAuthError';
  }
}

/**
 * 建立聊天 SSE 连接，返回可逐块读取的响应体。
 * R6：只负责传输与鉴权；事件归一化在 `services/streamService`。
 */
export async function openChatStream(
  payload: StreamPayload,
  signal?: AbortSignal
): Promise<ReadableStream<Uint8Array>> {
  const token = localStorage.getItem('token') || '';
  const resp = await fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(payload),
    signal
  });

  if (resp.status === 401) {
    throw new SseAuthError();
  }

  if (!resp.ok || !resp.body) {
    const err = await resp.json().catch(() => ({ message: '' }));
    throw new Error((err as { message?: string }).message || '这次没能连上助手，请稍后再试');
  }

  return resp.body;
}

