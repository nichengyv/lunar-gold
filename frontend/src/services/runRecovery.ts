import type { RunEventItem } from '../api/agent';
import type { FileChange, ToolInvocation } from '../types/api';
import type {
  CompletionInfo,
  DiagnosticsInfo,
  PlanSnapshot,
  PlanStepStatus,
  UserQuestion,
  VerifyInfo
} from '../types/chat';

/** 恢复出的审批需求：事件里只有 approvalId + tool，风险等级按工具名推断 */
export interface RecoveredApproval {
  approvalId: string;
  tool: string;
  risk: 'WRITE' | 'EXECUTE' | 'DESTRUCTIVE';
  resolved: boolean;
}

/** 一次运行的全部可恢复状态；由 `replayRunEvents` 从落库事件折叠得到 */
export interface RecoveredRun {
  text: string;
  truncated: boolean;
  tools: ToolInvocation[];
  plan?: PlanSnapshot;
  changes: FileChange[];
  approval?: RecoveredApproval;
  userQuestion?: Omit<UserQuestion, 'runId'>;
  completion?: CompletionInfo;
  diagnostics?: DiagnosticsInfo;
  verify?: VerifyInfo;
}

const WRITE_TOOLS = new Set(['write_file', 'edit_file', 'apply_patch', 'delete_file', 'create_file']);
const PLAN_STATUSES = new Set<PlanStepStatus>(['pending', 'in_progress', 'completed', 'aborted']);

/**
 * P5-10 / R6：把后端落库的运行事件折叠成可展示状态。
 *
 * <p>纯函数、不依赖 store，便于覆盖 plan / file_change / approval / question
 * 四类断线恢复场景。正文取最后一个 MODEL_CHUNK 检查点；文件变更由
 * TOOL_CALL + TOOL_RESULT 配对推导（file_change 是实时流事件，不落库）。
 */
export function replayRunEvents(events: RunEventItem[]): RecoveredRun {
  let text = '';
  let declaredLength = 0;
  const tools: ToolInvocation[] = [];
  const changes: FileChange[] = [];
  let plan: PlanSnapshot | undefined;
  let approval: RecoveredApproval | undefined;
  let userQuestion: Omit<UserQuestion, 'runId'> | undefined;
  let completion: CompletionInfo | undefined;
  let diagnostics: DiagnosticsInfo | undefined;
  let verify: VerifyInfo | undefined;

  for (const event of events) {
    const payload = parsePayload(event);
    if (!payload) continue;

    if (event.type === 'MODEL_CHUNK') {
      if (typeof payload.text === 'string' && payload.text.length >= text.length) {
        text = payload.text;
        declaredLength = Number(payload.length) || payload.text.length;
      }
    } else if (event.type === 'TOOL_CALL') {
      tools.push({ name: payload.tool, args: payload.args ?? '{}', status: 'running' });
    } else if (event.type === 'TOOL_RESULT') {
      const match = [...tools].reverse().find((t) => t.name === payload?.tool && t.status === 'running');
      if (match) {
        match.status = payload.success ? 'success' : 'error';
        match.result = payload.content;
        match.error = payload.error;
        if (payload.success) {
          applyToolChange(match, changes);
        }
      }
    } else if (event.type === 'PLAN_UPDATED') {
      const steps = Array.isArray(payload.steps) ? payload.steps : [];
      plan = {
        steps: steps
          .filter((step: any) => step && typeof step.title === 'string')
          .map((step: any) => ({
            title: step.title,
            status: PLAN_STATUSES.has(step.status) ? step.status : 'pending'
          })),
        updatedAt: Date.parse(event.createTime ?? '') || Date.now()
      };
    } else if (event.type === 'APPROVAL_REQUESTED') {
      approval = {
        approvalId: String(payload.approvalId ?? ''),
        tool: String(payload.tool ?? ''),
        risk: inferRisk(String(payload.tool ?? '')),
        resolved: false
      };
    } else if (event.type === 'APPROVAL_RESOLVED') {
      if (approval) approval.resolved = true;
    } else if (event.type === 'COMPLETION') {
      completion = payload as CompletionInfo;
    } else if (event.type === 'DIAGNOSTICS') {
      diagnostics = payload as DiagnosticsInfo;
    } else if (event.type === 'VERIFY') {
      verify = payload as VerifyInfo;
    } else if (event.type === 'USER_QUESTION') {
      if (payload.questionId) {
        userQuestion = {
          questionId: String(payload.questionId),
          question: String(payload.question ?? ''),
          header: payload.header,
          options: payload.options,
          multiSelect: !!payload.multiSelect
        };
      }
    } else if (event.type === 'USER_ANSWERED') {
      if (userQuestion && userQuestion.questionId === payload.questionId) {
        userQuestion = undefined;
      }
    }
  }

  return {
    text,
    truncated: declaredLength > text.length,
    tools,
    plan,
    changes,
    approval,
    userQuestion,
    completion,
    diagnostics,
    verify
  };
}

function parsePayload(event: RunEventItem): Record<string, any> | null {
  try {
    return event.payload ? (JSON.parse(event.payload) as Record<string, any>) : null;
  } catch {
    return null;
  }
}

/** 写/编辑/删除成功后登记变更；同一路径只保留一条，避免重放时重复计数 */
function applyToolChange(tool: ToolInvocation, changes: FileChange[]) {
  if (!WRITE_TOOLS.has(tool.name)) return;
  const path = extractPath(tool.args);
  if (!path) return;
  const action = tool.name === 'delete_file' ? 'delete' : 'modify';
  const existing = changes.find((c) => c.path === path);
  if (existing) {
    existing.source = tool.name;
    if (action === 'delete') existing.action = 'delete';
    return;
  }
  changes.push({ path, action, source: tool.name, additions: 0, deletions: 0, diff: '' });
}

function extractPath(args: string): string {
  try {
    const parsed = JSON.parse(args || '{}');
    return String(parsed.path ?? parsed.file_path ?? parsed.filePath ?? '');
  } catch {
    return '';
  }
}

function inferRisk(tool: string): RecoveredApproval['risk'] {
  if (tool === 'delete_file') return 'DESTRUCTIVE';
  if (tool === 'run_command' || tool === 'execute_command') return 'EXECUTE';
  return 'WRITE';
}
