/** 命中的资料来源（后端 sources 事件下发）；content 为命中的片段原文 */
export interface SourceRef {
  id: number;
  name: string;
  score: number;
  content?: string;
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
}
