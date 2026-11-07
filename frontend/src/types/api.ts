import type { ApprovalRequest, PlanSnapshot } from './chat';

/** 待审批项：SSE 事件 + 归属会话，用于切会话时不串卡片；resolved 存在表示已被处理（用户/超时/停止） */
export interface PendingApproval extends ApprovalRequest {
  sessionId: number | null;
  /** 已被处理：APPROVE = 用户批准，REJECT = 用户拒绝 / 超时 / 停止生成。undefined = 还在等决策 */
  resolved?: 'APPROVE' | 'REJECT';
}

/** 后端统一响应信封 */
export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data: T;
}

export interface SessionInfo {
  id: number;
  title?: string;
  projectId?: number;
  updateTime?: string;
  /** 会话已存的运行参数：模型 / 推理档位 / 工作权限 */
  model?: string;
  reasoning?: string;
  permission?: string;
}

/** 项目（工作区）：资料与对话的归属容器 */
export interface ProjectInfo {
  id: number;
  name: string;
  description?: string;
  defaultFlag?: boolean;
  instruction?: string;
  workspacePath?: string;
  /** 项目默认工作权限：READ_ONLY / WRITE / EXECUTE，缺省表示未设置 */
  defaultPermission?: string;
  createTime?: string;
}

/** 工作区文件节点 */
export interface FileNode {
  name: string;
  isDirectory: boolean;
  size: number;
  modifiedTime: number;
  relativePath: string;
}

/** 助手消息的执行轨迹（后端 trace JSON 解析后的结构） */
export interface MessageTrace {
  thinking?: string;
  tools?: ToolInvocation[];
  sources?: { id: number; name: string; score: number; content?: string }[];
  changes?: FileChange[];
  /** 本轮 Agent 提交的任务计划快照（切回会话时还原计划面板） */
  plan?: PlanSnapshot;
  /** 第六十八批 A3：本轮执行 id，用于按轮次撤回文件改动 */
  turnId?: string;
  /** 本轮从开始到完成回答的耗时（毫秒），用于「已完成，用时…」 */
  durationMs?: number;
}

/** 会话历史消息（后端返回） */
export interface HistoryMessage {
  /** 后端 chat_message.id，用于搜索后定位到具体消息 */
  id?: number;
  role: 'USER' | 'ASSISTANT';
  content: string;
  /** 助手消息的执行轨迹 JSON 字符串；普通消息为空 */
  trace?: string;
  createTime?: string;
}

/** 消息全文搜索命中（同一会话只返回最新一条命中消息） */
export interface MessageSearchHit {
  messageId: number;
  sessionId: number;
  projectId: number;
  sessionTitle?: string;
  role?: string;
  snippet: string;
  createTime?: string;
}

export interface KeyInfo {
  id: number;
  provider: string;
  alias?: string;
  createTime?: string;
}

export interface DocumentInfo {
  id: number;
  fileName: string;
  fileType: string;
  fileSize?: number;
  status: string;
  chunkCount?: number;
  createTime?: string;
  description?: string;
}

export interface UserInfo {
  id?: number;
  username?: string;
  email?: string;
}

/** 工具调用展示状态 */
export interface ToolInvocation {
  name: string;
  args: string;
  status: 'running' | 'success' | 'error';
  result?: string;
  error?: string;
  costMs?: number;
}

/** 一次生成中工作区文件的变更（后端 file_change 事件） */
export interface FileChange {
  /** 相对工作区根目录的路径 */
  path: string;
  /** create 新建 / modify 修改 / delete 删除 / command 由终端命令产生 */
  action: 'create' | 'modify' | 'delete' | 'command';
  /** 产生变更的工具：write_file / edit_file / run_command */
  source: string;
  additions: number;
  deletions: number;
  /** 逐行 diff 文本（"+ " / "- " / "@@" 前缀），无 diff 时为空串 */
  diff: string;
}

/** 文件变更审查流实体（来自后端 PendingFileChange JPA） */
export interface PendingFileChange {
  id: number;
  projectId: number;
  sessionId?: number | null;
  /** 第六十八批 A3：所属轮次 id */
  turnId?: string | null;
  userId: number;
  filePath: string;
  action: string; // create / modify / delete
  source: string;
  oldContent: string | null;
  newContent: string;
  diffText: string | null;
  additions: number;
  deletions: number;
  status: 'PENDING' | 'REVERTED' | 'ACCEPTED';
  createTime: string;
}

/** 聊天界面消息（前端 UI 模型）；hint 为附件提示条 */
export interface UiMessage {
  id: number;
  /** 后端消息 id（仅历史消息有，搜索定位用） */
  sourceId?: number;
  role: 'user' | 'assistant' | 'hint';
  content: string;
  thinking?: string;
  tools: ToolInvocation[];
  attachmentCount?: number;
  streaming?: boolean;
  failed?: boolean;
  /** 失败原因（网络中断 / 后端报错），与已生成内容分开存，避免半截答案被覆盖 */
  errorText?: string;
  /** 用户主动停止生成（保留已生成内容，不算失败） */
  stopped?: boolean;
  /** 本次回答参考了哪些资料 */
  sources?: { id: number; name: string; score: number; content?: string }[];
  /** 本次回答改动了哪些工作区文件 */
  changes?: FileChange[];
  /** 本轮 Agent 提交的任务计划（实时更新；为空表示没有计划） */
  plan?: PlanSnapshot;
  /** 第六十八批 A3：本轮执行 id，用于「撤回本轮」 */
  turnId?: string;
  /** 本轮耗时（毫秒）：生成中用开始时间计时，完成后固定，历史消息从 trace 还原 */
  durationMs?: number;
}

/**
 * 第五十四批：会话追踪面板的聚合统计类型。
 * 后端从 ChatMessage.trace 按需 parse + 聚合，不新建表。
 */
export interface SessionStats {
  overview: {
    totalRounds: number;
    totalToolCalls: number;
    toolSuccessRate: number;
    uniqueTools: number;
    uniqueFilesChanged: number;
    totalDurationMs: number;
    reasoningPolicy: string;
    permission: string;
    startedAt: string | null;
    lastActiveAt: string | null;
  };
  /** 第五十六批：耗时口径拆分（后端有 segments 的新会话才有） */
  timeBreakdown?: {
    setupMs: number;
    toolsMs: number;
    answerMs: number;
    roundsWithSegments: number;
  };
  tools: {
    name: string;
    source: 'builtin' | 'mcp';
    calls: number;
    success: number;
    failure: number;
    successRate: number;
    avgCostMs: number;
    p95CostMs: number;
    maxCostMs: number;
  }[];
  files: {
    path: string;
    action: string;
    source: string;
    additions: number;
    deletions: number;
  }[];
  timeline: {
    offsetMs: number;
    name: string;
    source: 'builtin' | 'mcp';
    status: string;
    costMs: number;
    argsSummary: string;
  }[];
}
