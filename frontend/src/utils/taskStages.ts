/**
 * R6c：执行阶段与失败分级的纯函数。
 *
 * <p>从 `stores/chat` 抽出：发送编排服务需要 `stageForTool`，而 store 又会调用服务，
 * 放在 utils 里避免 store ↔ service 互相引用。原 `stores/chat` 继续 re-export，
 * 视图与测试的导入路径保持不变。
 */
import type {
  CompletionInfo,
  FailureInfo,
  TaskStageItem,
  TaskStageKey,
  TaskStageStatus
} from '../types/chat';

/** 五个执行阶段的固定顺序与展示名 */
export const TASK_STAGES: { key: TaskStageKey; label: string }[] = [
  { key: 'understand', label: '理解' },
  { key: 'read', label: '读取' },
  { key: 'modify', label: '修改' },
  { key: 'verify', label: '验证' },
  { key: 'summarize', label: '汇总' }
];

const READ_TOOLS = new Set([
  'read_file', 'list_files', 'list_dir', 'list_directory', 'grep', 'glob',
  'search_code', 'search_files', 'repo_map', 'read_document'
]);
const WRITE_TOOLS = new Set(['write_file', 'edit_file', 'apply_patch', 'delete_file', 'create_file']);

/** P5-10-3：工具调用属于哪个阶段；未知工具不推进，避免误判 */
export function stageForTool(tool: string, modified: boolean): TaskStageKey | null {
  const name = (tool || '').toLowerCase();
  if (WRITE_TOOLS.has(name)) return 'modify';
  if (READ_TOOLS.has(name)) return 'read';
  if (name.includes('lsp') || name === 'diagnostics') return 'verify';
  if (name === 'run_command' || name === 'execute_command') return modified ? 'verify' : 'read';
  return null;
}

/**
 * P5-10-3：把阶段首次到达时间折叠成可展示的时间线。
 * 未到达但已被后续阶段越过的阶段标记为「跳过」，不伪造耗时。
 */
export function buildStageTimeline(
  marks: Partial<Record<TaskStageKey, number>>,
  now: number,
  endedAt?: number
): TaskStageItem[] {
  const lastReached = [...TASK_STAGES].reverse().find((s) => !!marks[s.key])?.key;
  return TASK_STAGES.map((stage, i) => {
    const startedAt = marks[stage.key];
    if (!startedAt) {
      const laterReached = TASK_STAGES.slice(i + 1).some((s) => !!marks[s.key]);
      return { ...stage, status: (laterReached ? 'skipped' : 'pending') as TaskStageStatus };
    }
    const nextStartedAt = TASK_STAGES.slice(i + 1)
      .map((s) => marks[s.key])
      .find((v): v is number => typeof v === 'number');
    const durationMs = Math.max(0, (nextStartedAt ?? endedAt ?? now) - startedAt);
    const status: TaskStageStatus = stage.key === lastReached && endedAt == null ? 'active' : 'done';
    return { ...stage, status, startedAt, durationMs };
  });
}

/** P5-10-3：把失败原因归到四类，并给出对应动作 */
export function classifyFailure(errorText?: string, completion?: CompletionInfo | null): FailureInfo {
  const reason = completion?.reason || '';
  const text = `${errorText || ''} ${reason} ${completion?.message || ''}`;

  if (reason === 'APPROVAL_REJECTED' || /审批|拒绝|approval|被拦截/i.test(text)) {
    return {
      class: 'approval',
      label: '需审批',
      hint: '上次审批被拒绝或未通过，重新发起会再次请求审批。',
      primaryAction: 'retry'
    };
  }
  if (
    /(api[\s_-]?key|apikey|密钥).*(未配置|缺失|无效)|未配置.*(key|密钥|模型)|unauthorized|\b401\b|\b403\b|模型不可用|model.*(not found|unavailable)|\b404\b/i.test(text)
  ) {
    return {
      class: 'config',
      label: '需配置',
      hint: '模型密钥或模型配置有问题，去设置检查后再试。',
      primaryAction: 'settings'
    };
  }
  if (
    reason === 'MAX_TOOL_ROUNDS' ||
    /\b5\d\d\b|timeout|timed out|超时|\b429\b|rate limit|network|failed to fetch|网络|连接中断/i.test(text)
  ) {
    return {
      class: 'retryable',
      label: '可重试',
      hint: reason === 'MAX_TOOL_ROUNDS'
        ? '已达到工具轮次上限，可以重试或切换到深度推理。'
        : '网络波动或模型临时不可用，可以直接重试这一轮。',
      primaryAction: 'retry'
    };
  }
  if (reason === 'TOKEN_BUDGET_EXCEEDED' || reason === 'TIME_BUDGET_EXCEEDED') {
    return {
      class: 'manual',
      label: '需人工',
      hint: '本轮预算已用完，请缩小任务范围或调高预算后再试。',
      primaryAction: 'reload'
    };
  }
  return {
    class: 'manual',
    label: '需人工',
    hint: '需要检查工作区权限、文件冲突或诊断/验证结果。',
    primaryAction: 'reload'
  };
}
