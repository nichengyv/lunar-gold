/** 本轮任务的完成状态：执行结束不代表任务已经验证或完成 */
export type CompletionStatus = 'COMPLETED' | 'UNVERIFIED' | 'INCOMPLETE';

/** 后端 completion 事件下发的任务收尾信息 */
export interface CompletionInfo {
  status: CompletionStatus;
  reason?: string;
  message?: string;
  filesChanged?: boolean;
}

/** P5-05：一条编译/语言服务诊断 */
export interface DiagnosticItem {
  file: string;
  line: number;
  column: number;
  severity: 'ERROR' | 'WARNING' | 'INFO';
  message: string;
}

/** P5-05：变更文件诊断结果（diagnostics 事件 / 消息 trace） */
export interface DiagnosticsInfo {
  provider: string;
  available: boolean;
  analyzedFiles: number;
  totalCount: number;
  truncated: boolean;
  diagnostics: DiagnosticItem[];
  runId?: number;
  turnId?: string;
  fileVersion?: string;
  at?: number;
}

/** P5-06：验证状态；未配置/不可用不能被展示为通过 */
export type VerifyStatus = 'PASSED' | 'FAILED' | 'NOT_CONFIGURED' | 'UNAVAILABLE';

/** P5-06：单条验证命令结果 */
export interface VerifyCommandOutcome {
  name: string;
  command: string;
  cwd?: string;
  required: boolean;
  exitCode: number;
  timedOut: boolean;
  durationMs: number;
  skipped: boolean;
  outputTail?: string;
}

/** P5-06：项目验证结果（verify 事件 / 消息 trace） */
export interface VerifyInfo {
  runId?: number;
  turnId?: string;
  status: VerifyStatus;
  configured: boolean;
  attempt: number;
  maxAttempts: number;
  configPath?: string;
  authorization?: string;
  message?: string;
  failureFingerprint?: string;
  commands: VerifyCommandOutcome[];
  at?: number;
}

/** 命中的资料来源（后端 sources 事件下发）；content 为命中的片段原文 */
export interface SourceRef {
  id: number;
  name: string;
  score: number;
  content?: string;
  /** P5-07：命中的切片编号与序号（chunkIndex 从 0 开始） */
  chunkId?: number;
  chunkIndex?: number;
}

/** 工作区文件变更（与 UI 模型共用一份定义） */
export type { FileChange } from './api';

/** 审批卡片用的可读预览：后端已把工具入参整理成"做什么 + 哪个文件 + 改了多少行" */
export interface ApprovalPreview {
  kind: 'write' | 'edit' | 'delete' | 'command' | 'other';
  /** 相对工作区路径（文件类工具） */
  path?: string;
  /** 命令原文（run_command） */
  command?: string;
  /** 截断后的逐行 diff / 内容预览 */
  diff?: string;
  additions?: number;
  deletions?: number;
  newFile?: boolean;
  note?: string;
}

/** 第六十八批 A2：危险工具执行前的审批请求（SSE approval_required 事件） */
export interface ApprovalRequest {
  approvalId: string;
  tool: string;
  /** 可读摘要；旧后端可能仍传 args，因此两者都保留可选 */
  preview?: ApprovalPreview;
  args?: string;
  /** 风险等级：WRITE 写入文件 / EXECUTE 执行命令 / DESTRUCTIVE 递归删除目录 */
  risk: 'WRITE' | 'EXECUTE' | 'DESTRUCTIVE';
  /** 自动拒绝的时间戳（毫秒） */
  expiresAt: number;
}

/** P5-10-2：审批决策来源；旧记录为 null，展示为来源未知 */
export type ApprovalDecisionSource = 'USER' | 'BATCH' | 'TIMEOUT' | 'SESSION_STOP' | 'SYSTEM';

/** P5-10-2：会话内已处理审批历史项（不包含原始参数） */
export interface ApprovalHistoryItem {
  approvalId: string;
  tool: string;
  risk: 'WRITE' | 'EXECUTE' | 'DESTRUCTIVE';
  state: 'APPROVED' | 'REJECTED';
  decisionSource?: ApprovalDecisionSource | null;
  decisionTime?: string | null;
  createTime?: string | null;
  rememberSession: boolean;
}

/** P5-10-2：批量允许结果，只包含实际完成的审批 */
export interface ApprovalBatchResult {
  batchId: string;
  resolvedIds: string[];
}

/** 计划步骤状态：待开始 / 进行中 / 已完成 */
export type PlanStepStatus = 'pending' | 'in_progress' | 'completed' | 'aborted';

/** 计划中的一个步骤 */
export interface PlanStep {
  title: string;
  status: PlanStepStatus;
}

/** 本轮 Agent 提交的任务计划快照（SSE plan 事件 / 消息 trace） */
export interface PlanSnapshot {
  steps: PlanStep[];
  /** 最后一次更新时间（毫秒时间戳） */
  updatedAt: number;
}

/** P5-10-3：本轮预算进度（usage SSE）；旧后端可能只给 token 三项 */
export interface RunUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  /** 已完成的模型轮次，恢复后可能缺省 */
  round?: number;
  /** 当前推理档位的轮次上限 */
  maxRounds?: number;
  /** token 上限，0 表示不限 */
  maxTokens?: number;
  /** 服务端已用时间 */
  elapsedMs?: number;
  /** 时间上限，0 表示不限 */
  maxElapsedMs?: number;
}

/** P5-10-3：长任务的五个执行阶段 */
export type TaskStageKey = 'understand' | 'read' | 'modify' | 'verify' | 'summarize';
export type TaskStageStatus = 'pending' | 'active' | 'done' | 'skipped';

export interface TaskStageItem {
  key: TaskStageKey;
  label: string;
  status: TaskStageStatus;
  startedAt?: number;
  durationMs?: number;
}

/** P5-10-3：失败分级；不同类别给不同动作 */
export type FailureClass = 'retryable' | 'config' | 'approval' | 'manual';

export interface FailureInfo {
  class: FailureClass;
  label: string;
  hint: string;
  primaryAction: 'retry' | 'settings' | 'reload';
}

/** 推理档位：快速 / 标准 / 深度，决定工具轮次与历史预算 */
export type ReasoningLevel = 'FAST' | 'STANDARD' | 'DEEP';

/** 工作权限：只读（不能改文件）/ 可写（可改文件）/ 可执行（可跑命令） */
export type PermissionLevel = 'MANUAL' | 'AUTO' | 'FULL' | 'READ_ONLY';

/** 发给后端的流式聊天请求体 */
export interface StreamPayload {
  prompt: string;
  sessionId: number | null;
  /** 当前项目：后端按项目隔离资料检索与会话 */
  projectId?: number | null;
  useRag: boolean;
  attachmentIds: number[];
  /** 对话模型：后端按白名单校验，非法值回落默认模型 */
  model?: string;
  reasoning?: ReasoningLevel;
  permission?: PermissionLevel;
  /** 每次发送生成一次；网络重试必须复用，后端据此阻止重复执行 */
  clientRequestId?: string;
}
