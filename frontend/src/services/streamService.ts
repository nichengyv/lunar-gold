/**
 * R6：聊天流服务（SSEService）。
 *
 * <p>`api/sse` 只保留 SSE 协议解析与建连；这里负责事件归一化（把后端原始事件
 * 转成 `StreamHooks` 回调）以及登录态失效的收尾。store 因此只关心业务状态，
 * 不再自己解析事件名与 JSON。
 */
import { openChatStream, parseSSE, SseAuthError } from '../api/sse';
import type {
  ApprovalDecisionSource,
  ApprovalRequest,
  CompletionInfo,
  DiagnosticsInfo,
  FileChange,
  PlanSnapshot,
  RunUsage,
  SourceRef,
  StreamPayload,
  UserQuestion,
  VerifyInfo
} from '../types/chat';

export interface StreamHooks {
  onSessionId?: (id: number) => void;
  /** P5-10：本轮运行的持久化 id，断线/刷新后凭它拉取已落库事件恢复内容 */
  onRunId?: (runId: number) => void;
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
  onApprovalResolved?: (
    approvalId: string,
    decision: 'APPROVE' | 'REJECT',
    source?: ApprovalDecisionSource,
    batchId?: string
  ) => void;
  /** P7-06：agent 暂停等用户回答决策询问（ask_user_question 工具） */
  onUserQuestion?: (question: UserQuestion) => void;
  onContent?: (fullText: string, chunk: string) => void;
  onSources?: (sources: SourceRef[]) => void;
  onFileChange?: (change: FileChange) => void;
  /** 本轮任务的完成、未验证或未完成状态 */
  onCompletion?: (completion: CompletionInfo) => void;
  /** P5-05：变更文件诊断结果 */
  onDiagnostics?: (diagnostics: DiagnosticsInfo) => void;
  /** P5-06：项目验证结果 */
  onVerify?: (verify: VerifyInfo) => void;
  /** P5-10-3：本轮 token、轮次与时间预算进度 */
  onUsage?: (usage: RunUsage) => void;
  onDone?: (fullText: string) => void;
  onError?: (message: string) => void;
}

/** 登录过期：清本地凭证并回登录页，与旧行为保持一致 */
function handleAuthExpired(hooks: StreamHooks, message: string) {
  localStorage.removeItem('token');
  localStorage.removeItem('username');
  hooks.onError?.(message);
  setTimeout(() => {
    window.location.href = '/login';
  }, 600);
}

export async function streamChat(
  payload: StreamPayload,
  hooks: StreamHooks,
  signal?: AbortSignal
): Promise<void> {
  let body: ReadableStream<Uint8Array>;
  try {
    body = await openChatStream(payload, signal);
  } catch (e) {
    if (e instanceof SseAuthError) {
      handleAuthExpired(hooks, e.message);
      return;
    }
    throw e;
  }

  let fullText = '';
  await parseSSE(body, (type, data) => {
    switch (type) {
      case 'session_id':
        hooks.onSessionId?.(parseInt(data, 10));
        break;
      case 'run_id':
        hooks.onRunId?.(parseInt(data, 10));
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
          hooks.onApprovalResolved?.(obj.approvalId, obj.decision, obj.source, obj.batchId);
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'user_question':
        try {
          hooks.onUserQuestion?.(JSON.parse(data) as UserQuestion);
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
      case 'completion':
        try {
          hooks.onCompletion?.(JSON.parse(data) as CompletionInfo);
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'no_progress':
        break;
      case 'diagnostics':
        try {
          hooks.onDiagnostics?.(JSON.parse(data) as DiagnosticsInfo);
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'verify':
        try {
          hooks.onVerify?.(JSON.parse(data) as VerifyInfo);
        } catch {
          /* 忽略解析失败 */
        }
        break;
      case 'usage':
        try {
          hooks.onUsage?.(JSON.parse(data) as RunUsage);
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
