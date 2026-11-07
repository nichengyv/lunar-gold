/**
 * SSE 流式请求封装。
 * 后端 /api/agent/chat/stream 是 POST 方式的 SSE 流，原生 EventSource 只支持 GET，
 * 因此必须用 fetch + ReadableStream 手动解析。
 */
import type { ApprovalRequest, FileChange, PlanSnapshot, SourceRef, StreamPayload } from '../types/chat';

export interface StreamHooks {
  onSessionId?: (id: number) => void;
  /** 第六十八批 A3：本轮执行 id，用于「撤回本轮」 */
  onTurnId?: (turnId: string) => void;
  onThinking?: (thought: string) => void;
  onToolCall?: (tool: string, args: string) => void;
  onToolResult?: (tool: string, data: { success: boolean; content?: string; error?: string; costMs?: number }) => void;
  /** Agent 提交或更新任务计划（update_plan 工具） */
  onPlan?: (plan: PlanSnapshot) => void;
  /** 危险工具执行前请求审批（write_file / edit_file / run_command） */
  onApprovalRequired?: (approval: ApprovalRequest) => void;
  /** 审批已被处理（用户批准/拒绝、超时自动拒绝、会话停止），前端据此把卡片切到"已处理"态 */
  onApprovalResolved?: (approvalId: string, decision: 'APPROVE' | 'REJECT') => void;
  onContent?: (fullText: string, chunk: string) => void;
  onSources?: (sources: SourceRef[]) => void;
  onFileChange?: (change: FileChange) => void;
  onDone?: (fullText: string) => void;
  onError?: (message: string) => void;
}

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

export async function streamChat(
  payload: StreamPayload,
  hooks: StreamHooks,
  signal?: AbortSignal
): Promise<void> {
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
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    hooks.onError?.('登录已过期，请重新登录');
    setTimeout(() => {
      window.location.href = '/login';
    }, 600);
    return;
  }

  if (!resp.ok || !resp.body) {
    const err = await resp.json().catch(() => ({ message: '' }));
    throw new Error((err as { message?: string }).message || '这次没能连上助手，请稍后再试');
  }

  let fullText = '';
  await parseSSE(resp.body, (type, data) => {
    switch (type) {
      case 'session_id':
        hooks.onSessionId?.(parseInt(data, 10));
        break;
      case 'turn_id':
        hooks.onTurnId?.(data);
        break;
      case 'thinking':
        try {
          hooks.onThinking?.(JSON.parse(data).thought || '');
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'tool_call':
        try {
          hooks.onToolCall?.(JSON.parse(data).tool, JSON.parse(data).args || '{}');
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'tool_result':
        try {
          hooks.onToolResult?.(JSON.parse(data).tool, JSON.parse(data));
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'content': {
        // 后端以 JSON 下发：{"text":"..."}，避免答案中的换行破坏 SSE 行协议
        let text = data;
        try {
          text = JSON.parse(data).text ?? '';
        } catch {
          /* 兼容旧格式：直接当纯文本用 */
        }
        fullText += text;
        hooks.onContent?.(fullText, text);
        break;
      }
      case 'plan':
        try {
          hooks.onPlan?.(JSON.parse(data) as PlanSnapshot);
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'approval_required':
        try {
          hooks.onApprovalRequired?.(JSON.parse(data) as ApprovalRequest);
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'approval_resolved':
        try {
          const obj = JSON.parse(data);
          hooks.onApprovalResolved?.(obj.approvalId, obj.decision);
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'sources':
        try {
          hooks.onSources?.(JSON.parse(data) as SourceRef[]);
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'file_change':
        try {
          hooks.onFileChange?.(JSON.parse(data) as FileChange);
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'done':
        hooks.onDone?.(fullText);
        return true; // 终止读取，不等服务端关连接
      case 'error':
        hooks.onError?.(data);
        return true;
      default:
        console.warn('[Chat] 未知事件类型:', type);
    }
  });
}
