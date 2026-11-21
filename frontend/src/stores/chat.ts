import { defineStore } from 'pinia';
import { reactive } from 'vue';
import { ElMessage } from 'element-plus';
import * as agentApi from '../api/agent';
import * as settingsApi from '../api/settings';
import { streamChat } from '../api/sse';
import * as fileChangesApi from '../api/fileChanges';
import { replayRunEvents } from '../services/runRecovery';
import { createFrameThrottle } from '../utils/frameThrottle';
import { useProjectStore } from './project';
import {
  beginExperienceRecovery,
  discardExperienceRecovery,
  finishExperienceRecovery,
  markExperienceRun,
  setExperienceSessionId
} from '../utils/experienceDiagnostics';
import type { ChangeSetView, FileChange, MessageTrace, PendingApproval, PendingFileChange, SessionInfo, ToolInvocation, UiMessage } from '../types/api';
import type {
  ApprovalHistoryItem,
  CompletionInfo,
  FailureInfo,
  PermissionLevel,
  PlanSnapshot,
  ReasoningLevel,
  RunUsage,
  TaskStageItem,
  TaskStageKey,
  TaskStageStatus,
  UserQuestion
} from '../types/chat';

/** 输入框上方显示的附件标签 */
export interface AttachmentChip {
  key: string;
  fileName: string;
  status: 'uploading' | 'done' | 'error';
  id?: number;
  /** 图片附件的本地预览地址（object URL），发送/移除后释放 */
  previewUrl?: string;
}

/** 已发送消息中关联的附件（用于会话内恢复 UI） */
interface SessionAttachment {
  id: number;
  fileName: string;
}

let seq = 0;
const nextId = () => ++seq;

/**
 * P5-10：断点恢复的代际标记。
 * 切换会话 / 重新发送 / 主动停止时自增，让上一轮还在跑的恢复轮询自行退出，
 * 避免它把旧轮次的内容写到新会话的气泡上。
 */
let recoveryToken = 0;

function delay(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}

/**
 * P5-10：把后端落库的运行事件折叠成可展示的内容。
 * R6：折叠逻辑抽到 `services/runRecovery`，补齐 plan / 文件变更 / 审批 / 询问四类恢复。
 */
function applyRunEvents(events: agentApi.RunEventItem[], target: UiMessage) {
  const recovered = replayRunEvents(events);
  if (recovered.text) target.content = recovered.text;
  if (recovered.tools.length) target.tools = recovered.tools;
  if (recovered.plan) target.plan = recovered.plan;
  if (recovered.changes.length) target.changes = recovered.changes;
  if (recovered.completion) target.completion = recovered.completion;
  if (recovered.diagnostics) target.diagnostics = recovered.diagnostics;
  if (recovered.verify) target.verify = recovered.verify;
  return recovered;
}

// 当前流式请求的中断句柄：用户点「停止」时直接 abort，
// 浏览器断开后后端写 SSE 会失败并置 cancelled，从而停止继续消耗 token。
let currentAbort: AbortController | null = null;

// ——— P5-10-3：阶段时间线、预算与失败分级 ———

/** 五个执行阶段的固定顺序与展示名 */
export const TASK_STAGES: { key: TaskStageKey; label: string }[] = [
  { key: 'understand', label: '理解' },
  { key: 'read', label: '读取' },
  { key: 'modify', label: '修改' },
  { key: 'verify', label: '验证' },
  { key: 'summarize', label: '汇总' }
];

/** 停止后「继续」发送的固定提示，避免用户重述任务 */
export const CONTINUE_PROMPT = '请继续完成上一条未完成的回答，从中断处接着做，不要重复已完成的部分。';

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

// 输入草稿持久化：键为会话 id 字符串（未发消息的新会话用 new-<projectId>），值为未发送的输入文字
const DRAFT_STORAGE_KEY = 'chat.drafts';

function loadDrafts(): Record<string, string> {
  try {
    const parsed = JSON.parse(localStorage.getItem(DRAFT_STORAGE_KEY) || 'null');
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, string>) : {};
  } catch {
    // 存的 JSON 被改坏时不能让输入框起不来，直接当作没有草稿
    return {};
  }
}

/** 未发送附件的元信息：文件内容在后端，前端只记 id 和文件名，刷新后据此还原 chips */
interface DraftChipMeta {
  id: number;
  fileName: string;
}

const DRAFT_CHIPS_STORAGE_KEY = 'chat.draftChips';

function loadDraftChips(): Record<string, DraftChipMeta[]> {
  try {
    const parsed = JSON.parse(localStorage.getItem(DRAFT_CHIPS_STORAGE_KEY) || 'null');
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, DraftChipMeta[]>) : {};
  } catch {
    return {};
  }
}

/** 解析后端存的轨迹 JSON；损坏或缺失都当作没有轨迹，不能让历史加载失败 */
function parseTrace(raw?: string): MessageTrace | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as MessageTrace;
  } catch {
    return null;
  }
}

/** 旧版权限值归一化：EXECUTE/WRITE → 手动审批，非法/空 → 手动审批 */
export function normalizePermission(p: string | null | undefined): PermissionLevel {
  if (p === 'MANUAL' || p === 'AUTO' || p === 'FULL' || p === 'READ_ONLY') return p;
  return 'MANUAL';
}

/** 工作区默认权限：兼容旧值（EXECUTE/WRITE → 手动审批），未设置按手动审批处理 */
function projectDefaultPermission(): PermissionLevel {
  return normalizePermission(useProjectStore().currentProject?.defaultPermission);
}

/**
 * P5-10-2：批量允许的保守白名单，与后端保持一致。
 * 只覆盖写/编辑文件（含 MCP 同名工具）；命令、删除、移动、建目录和高危操作仍逐个确认。
 */
export function isBatchApprovable(approval: Pick<PendingApproval, 'tool' | 'risk'>): boolean {
  if (approval.risk !== 'WRITE') return false;
  const name = (approval.tool || '').toLowerCase();
  if (name === 'write_file' || name === 'edit_file') return true;
  return name.startsWith('mcp_') && (name.endsWith('_write_file') || name.endsWith('_edit_file'));
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [] as SessionInfo[],
    // 侧栏按工作区分组展示：projectId -> 该工作区的会话列表
    sessionsByProject: {} as Record<number, SessionInfo[]>,
    currentSessionId: null as number | null,
    messages: [] as UiMessage[],
    /** 运行中的实时计划快照：SSE plan 事件直接写这里，避免依赖具体消息对象是否还在数组里 */
    livePlan: null as PlanSnapshot | null,
    /** P5-10-3：本轮预算进度；断线恢复后可能只有 token 三项 */
    liveBudget: null as RunUsage | null,
    /** P5-10-3：本轮各阶段首次到达时间 */
    liveStageMarks: {} as Partial<Record<TaskStageKey, number>>,
    /** P5-10-3：本轮开始时间，用于本地耗时推进 */
    runStartedAt: 0,
    isProcessing: false,
    /** P5-09：会话历史加载中，用于骨架屏 */
    messagesLoading: false,
    /** P5-09：会话历史加载失败原因，非空时展示统一错误态 */
    loadError: '',
    /** P5-10：当前轮次的运行 id；断线或刷新后凭它重放已落库事件 */
    activeRunId: null as number | null,
    /** P5-10：流式连接状态，驱动输入框上方的状态条 */
    streamStatus: 'idle' as 'idle' | 'streaming' | 'reconnecting' | 'recovered' | 'interrupted',
    /** P5-10：恢复出来的正文是否因为超出检查点上限而被截断 */
    recoveredTruncated: false,
    // 消息全文搜索命中后要定位到的后端消息 id；消息加载完成后由 ChatView 滚动过去并清空
    focusMessageId: null as number | null,
    // 右侧工作台打开的文件 tab（相对路径，去重）；activeFilePath 是当前显示的那个
    openFiles: [] as string[],
    activeFilePath: null as string | null,
    // path -> 是否有未保存改动（每个 tab 独立上报）
    fileDirty: {} as Record<string, boolean>,
    // 从变更列表点进来时要定位的行号；每次点击递增 focusTick，同一行重复点击也能重新定位
    fileFocusLine: null as number | null,
    fileFocusTick: 0,
    // 右侧工作台（变更 / 资料 / 当前文件）是否展开，以及当前停在哪个 tab
    dockOpen: false,
    dockTab: 'changes' as 'changes' | 'docs' | 'file' | 'trace' | 'plan',
    pendingChips: [] as AttachmentChip[],
    // sessionId -> 已随消息发送的附件（仅前端内存，用于切回会话时恢复 chips）
    sessionAttachments: {} as Record<number, SessionAttachment[]>,
    // 各会话未发送的输入草稿（localStorage 持久化），切会话 / 刷新后回填
    drafts: loadDrafts(),
    // 各会话未发送的附件元信息（localStorage 持久化），键与 drafts 相同
    draftChips: loadDraftChips(),
    // 会话头可切换的运行参数：新会话沿用当前选择，切回旧会话时恢复该会话已存的配置
    model: 'deepseek-chat' as string,
    reasoning: 'STANDARD' as ReasoningLevel,
    permission: 'MANUAL' as PermissionLevel,
    // 全局默认值（设置页"对话默认"tab 修改这里）
    // 新会话启动时用 default 字段，用户不改就沿用；改了就覆盖 default
    defaultModel: 'deepseek-chat' as string,
    defaultReasoning: 'STANDARD' as ReasoningLevel,
    defaultPermission: 'MANUAL' as PermissionLevel,
    // 文件变更审查流：项目级 pending 变更列表
    pendingChanges: [] as PendingFileChange[],
    pendingChangesLoading: false as boolean,
    // P5-14：任务级交付视图（按 Session 聚合多个 Run 的变更/审批/验证）
    changeSet: null as ChangeSetView | null,
    changeSetLoading: false as boolean,
    changeSetRunFilter: null as number | null,
    // 第六十八批 A2：等待用户确认的危险工具调用（按会话隔离）
    pendingApprovals: [] as PendingApproval[],
    /** P7-06：agent 执行中等待用户回答的决策询问（同一时刻只可能有一个） */
    pendingUserQuestion: null as (UserQuestion & { runId: number }) | null,
    /** P5-12-4：轨迹里点「待审批」步骤时递增，聊天区据此高亮并定位审批区 */
    approvalFocusToken: 0,
    /** P5-10-2：审批状态变化计数，追踪页据此刷新会话审批历史 */
    approvalRevision: 0,
    /** P5-10-2：当前会话已处理审批历史 */
    approvalHistory: [] as ApprovalHistoryItem[],
    approvalHistoryLoading: false as boolean,
    /** P5-12-2：仍在运行 / 等待审批的轮次，按会话 id 索引，侧栏与空态共用 */
    runningRunMap: {} as Record<number, string>
  }),

  getters: {
    pendingAttachmentIds(): number[] {
      return this.pendingChips.filter((c) => c.status === 'done' && c.id).map((c) => c.id as number);
    },
    /** 任意一个文件 tab 有未保存改动 */
    editorDirty(): boolean {
      return this.openFiles.some((p) => this.fileDirty[p]);
    }
  },

  actions: {
    /** C2: 从后端拉用户设置的默认值；App.vue 登录后调一次 */
    async fetchDefaults() {
      try {
        const d = await settingsApi.getDefaults();
        if (d.model) this.defaultModel = d.model;
        if (d.reasoning) this.defaultReasoning = d.reasoning as ReasoningLevel;
        if (d.permission) this.defaultPermission = d.permission as PermissionLevel;
        // 同时更新当前会话（如果还没开始发消息）
        if (this.messages.length === 0) {
          this.model = this.defaultModel;
          this.reasoning = this.defaultReasoning;
          this.permission = projectDefaultPermission() || this.defaultPermission;
        }
      } catch {
        // 用户没设置过 / 后端接口异常 → 保持前端硬编码默认值即可
      }
    },

    /** 打开文件：已开过的 tab 直接切过去，没开过的新增一个 tab；line 用于从变更列表定位到改动行 */
    openFile(path: string, line?: number) {
      if (!this.openFiles.includes(path)) this.openFiles.push(path);
      this.activeFilePath = path;
      this.dockTab = 'file';
      this.dockOpen = true;
      this.fileFocusLine = line && line > 0 ? line : null;
      this.fileFocusTick += 1;
    },

    /** 只在已打开的 tab 之间切换，不新增 tab */
    activateFile(path: string) {
      if (!this.openFiles.includes(path)) return;
      this.activeFilePath = path;
      this.dockTab = 'file';
      this.dockOpen = true;
      this.fileFocusLine = null;
    },

    /** 关闭一个文件 tab（默认关当前那个）；关掉当前 tab 后自动激活相邻 tab */
    closeFile(path?: string) {
      const target = path ?? this.activeFilePath;
      if (!target) return;
      const idx = this.openFiles.indexOf(target);
      if (idx < 0) return;
      this.openFiles.splice(idx, 1);
      delete this.fileDirty[target];
      if (this.activeFilePath === target) {
        this.activeFilePath = this.openFiles[idx] ?? this.openFiles[idx - 1] ?? null;
        this.fileFocusLine = null;
      }
      // 一个文件都不剩就退回变更列表，避免留一个空 tab
      if (!this.openFiles.length && this.dockTab === 'file') this.dockTab = 'changes';
    },

    /** 资源管理器里重命名/移动后，把工作台里指向旧路径的标签一起改到新路径 */
    renameFileTab(from: string, to: string) {
      this.openFiles = this.openFiles.map((p) => {
        if (p === from) return to;
        if (p.startsWith(from + '/')) return to + p.slice(from.length);
        return p;
      });
      const active = this.activeFilePath;
      if (active === from) this.activeFilePath = to;
      else if (active && active.startsWith(from + '/')) this.activeFilePath = to + active.slice(from.length);
    },

    /** 拖拽排序：把 from 这个 tab 插到 to 的前面或后面 */
    moveFile(from: string, to: string, after: boolean) {
      const fromIdx = this.openFiles.indexOf(from);
      if (fromIdx < 0 || from === to) return;
      this.openFiles.splice(fromIdx, 1);
      const toIdx = this.openFiles.indexOf(to);
      if (toIdx < 0) {
        this.openFiles.splice(fromIdx, 0, from);
        return;
      }
      this.openFiles.splice(after ? toIdx + 1 : toIdx, 0, from);
    },

    /** 切工作区 / 换项目时把所有文件 tab 一起关掉 */
    closeAllFiles() {
      this.openFiles = [];
      this.activeFilePath = null;
      this.fileDirty = {};
      this.fileFocusLine = null;
      if (this.dockTab === 'file') this.dockTab = 'changes';
    },

    setFileDirty(path: string, dirty: boolean) {
      if (dirty) this.fileDirty[path] = true;
      else delete this.fileDirty[path];
    },

    /** 打开右侧工作台并切到指定 tab */
    openDock(tab: 'changes' | 'docs' | 'file' | 'trace' | 'plan') {
      this.dockTab = tab;
      this.dockOpen = true;
    },

    closeDock() {
      this.dockOpen = false;
      // 收起工作台会卸载所有编辑器，磁盘之外的内容不再存在，脏标记一并清掉
      this.fileDirty = {};
    },

    async loadSessions() {
      const project = useProjectStore();
      const projectId = project.currentProjectId;
      const list = await agentApi.listSessions(projectId ?? undefined);
      this.sessions = list;
      if (projectId != null) {
        this.sessionsByProject = { ...this.sessionsByProject, [projectId]: list };
      }
    },

    /** P5-12-2：刷新仍在运行/待审批的轮次，驱动侧栏与空态的状态点 */
    async refreshRunningRuns() {
      try {
        const runs = await agentApi.listRunningRuns();
        const map: Record<number, string> = {};
        for (const r of runs) {
          if (r.sessionId != null) map[r.sessionId] = r.state;
        }
        this.runningRunMap = map;
      } catch {
        // 状态点是辅助信息，查询失败按没有运行中的任务处理
        this.runningRunMap = {};
      }
    },

    /**
     * P5-12-2：本地更新某个会话的状态点。
     * 轮次刚开始/刚结束时后端落库有毫秒级延迟，先用本地结果覆盖，避免侧栏状态点滞后一拍。
     */
    markSessionRunState(
      sessionId: number | null,
      state: 'RUNNING' | 'WAITING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
    ) {
      if (sessionId == null) return;
      const patch = (list: SessionInfo[]) =>
        list.map((s) => (s.id === sessionId ? { ...s, lastRunState: state } : s));
      this.sessions = patch(this.sessions);
      const map: Record<number, SessionInfo[]> = {};
      for (const [pid, list] of Object.entries(this.sessionsByProject)) {
        map[Number(pid)] = patch(list);
      }
      this.sessionsByProject = map;
      const runs = { ...this.runningRunMap };
      if (state === 'RUNNING') runs[sessionId] = 'RUNNING';
      else delete runs[sessionId];
      this.runningRunMap = runs;
    },

    /** 侧栏需要展示所有工作区下的会话，逐个工作区拉一次（工作区数量少，可接受） */
    async loadAllSessions() {
      const project = useProjectStore();
      const results = await Promise.all(
        project.projects.map((p) => agentApi.listSessions(p.id).catch(() => [] as SessionInfo[]))
      );
      const map: Record<number, SessionInfo[]> = {};
      project.projects.forEach((p, i) => {
        map[p.id] = results[i];
      });
      this.sessionsByProject = map;
      const current = project.currentProjectId;
      this.sessions = current != null ? (map[current] ?? []) : [];
    },

    /** 切换项目：清空当前会话与被切走的会话列表，避免不同项目的上下文串味 */
    async onProjectChanged() {
      this.startNewSession();
      this.sessions = [];
      this.sessionAttachments = {};
      // 切换项目时重新拉审查流（新项目的 PendingFileChange）
      const projectStore = useProjectStore();
      this.pendingChanges = [];
      this.changeSet = null;
      this.changeSetRunFilter = null;
      if (projectStore.currentProjectId != null) {
        await this.fetchPendingChanges(projectStore.currentProjectId);
      }
    },
    startNewSession() {
      // 新会话：作废上一会话可能还在跑的断点恢复
      recoveryToken++;
      this.streamStatus = 'idle';
      this.activeRunId = null;
      this.currentSessionId = null;
      this.messages = [];
      this.pendingChips = [];
      // P7-06：决策询问属于上一会话的 Run，切会话/新会话时清掉，避免答到别的会话
      this.pendingUserQuestion = null;
      this.approvalHistory = [];
      this.changeSet = null;
      this.changeSetRunFilter = null;
      // 新会话：参数继承全局默认值（设置页可改）
      this.model = this.defaultModel;
      this.reasoning = this.defaultReasoning;
      // 权限取项目级默认（优先），没有则用全局默认
      this.permission = projectDefaultPermission() || this.defaultPermission;
    },

    /** 项目设置里改了默认权限后调用：只影响还没开始的会话，不动已在进行中的会话 */
    syncDefaultPermission() {
      if (this.currentSessionId != null) return;
      this.permission = projectDefaultPermission();
    },

    /** 会话头切换权限：同步本地缓存并立即落库，否则切走再切回会恢复成旧值 */
    setPermission(level: PermissionLevel) {
      this.permission = level;
      const sessionId = this.currentSessionId;
      if (sessionId == null) return;
      const info = this.findSession(sessionId);
      if (info) info.permission = level;
      agentApi.updateSessionPermission(sessionId, level).catch(() => undefined);
    },

    /** 在工作区/单项目两个缓存里找会话，找不到返回 undefined */
    findSession(sessionId: number): SessionInfo | undefined {
      return (
        Object.values(this.sessionsByProject)
          .flat()
          .find((s) => s.id === sessionId) || this.sessions.find((s) => s.id === sessionId)
      );
    },

    async switchSession(sessionId: number) {
      if (this.isProcessing || sessionId === this.currentSessionId) return;
      // 切走时作废旧会话还在跑的恢复轮询，避免内容写到新会话
      recoveryToken++;
      this.streamStatus = 'idle';
      this.activeRunId = null;
      this.currentSessionId = sessionId;
      // P5-10-2：历史按会话隔离，切会话先清掉旧数据，等追踪页按需重拉
      this.approvalHistory = [];
      // 恢复该会话已存的运行参数；老会话没存过则回落到默认档
      const info = this.findSession(sessionId) || this.sessions.find((s) => s.id === sessionId);
      if (info) {
        this.model = info.model || 'deepseek-chat';
        this.reasoning = (info.reasoning as ReasoningLevel) || 'STANDARD';
        this.permission = normalizePermission(info.permission);
      }
      this.pendingChips = (this.sessionAttachments[sessionId] || []).map((a) => ({
        key: `s${a.id}`,
        fileName: a.fileName,
        status: 'done' as const,
        id: a.id
      }));
      await this.loadSessionMessages(sessionId);
    },

    /** 拉取并还原某个会话的历史消息；加载中与失败分别交给骨架屏和错误态展示 */
    async loadSessionMessages(sessionId: number, silent = false) {
      setExperienceSessionId(sessionId);
      if (!silent) this.messagesLoading = true;
      this.loadError = '';
      try {
        const history = await agentApi.getSessionMessages(sessionId);
        this.livePlan = null;
        this.messages = history.map((m) => {
          // 助手消息的思考/工具/来源/改动从 trace 还原，切回会话仍能展开查看
          const trace = parseTrace(m.trace);
          return {
            id: nextId(),
            sourceId: m.id,
            role: m.role === 'USER' ? ('user' as const) : ('assistant' as const),
            content: m.content,
            thinking: trace?.thinking,
            tools: trace?.tools ?? [],
            sources: trace?.sources,
            changes: trace?.changes,
            plan: trace?.plan,
            turnId: trace?.turnId,
            durationMs: trace?.durationMs,
            completion: trace?.completion,
            diagnostics: trace?.diagnostics,
            verify: trace?.verify
          };
        });
        // 后端只在本轮完整结束时才存助手回答：历史停在用户消息上，说明那一轮被刷新/断线/停止打断了。
        // P5-10：先按 runId 尝试恢复（可能仍在生成，也可能只留下半截检查点），恢复不了再标成中断。
        if (this.messages.length && this.messages[this.messages.length - 1].role === 'user') {
          const bubble = reactive<UiMessage>({
            id: nextId(),
            role: 'assistant',
            content: '',
            thinking: '',
            tools: []
          });
          this.messages.push(bubble);
          const target = bubble as UiMessage;
          this.restoreSessionRun(sessionId, target).then((restored) => {
            if (restored || this.messages[this.messages.length - 1] !== target) return;
            target.failed = true;
            target.errorText = '这一轮还没有回答（可能已中断或页面刷新）';
          });
        }
      } catch (e) {
        // 保留空列表而不是旧会话内容，避免把上一个会话的消息当成当前会话展示
        this.messages = [];
        this.loadError = (e as Error).message || '会话加载失败';
      } finally {
        this.messagesLoading = false;
        // P5-14：刷新/切回会话时由后端重建同一交付视图
        if (this.currentSessionId === sessionId) void this.fetchChangeSet();
      }
    },

    /** 错误态上的「重试」：重新拉取当前会话历史 */
    async reloadCurrentSession() {
      if (this.currentSessionId != null) {
        await this.loadSessionMessages(this.currentSessionId);
      }
    },

    /**
     * P5-10：轮询运行事件，把断线/刷新后的内容续上。
     * 运行中就一直跟着（1.5s 一次），网络抖动时退避重试，终态或重试耗尽后收尾。
     */
    async recoverRun(runId: number, target: UiMessage, maxAttempts = 5) {
      const token = ++recoveryToken;
      beginExperienceRecovery(runId, this.currentSessionId, token);
      this.streamStatus = 'reconnecting';
      let failedAttempts = 0;
      let pollAttempts = 0;
      let firstPoll = true;
      while (token === recoveryToken) {
        // 首次很快接上；正常跟随时 1.5s 一次；连续失败按 1s→2s→4s 退避
        const wait = firstPoll
          ? 600
          : failedAttempts > 0
            ? Math.min(8000, 1000 * 2 ** (failedAttempts - 1))
            : 1500;
        firstPoll = false;
        await delay(wait);
        if (token !== recoveryToken) {
          discardExperienceRecovery(token);
          return;
        }
        pollAttempts += 1;
        try {
          const result = await agentApi.getRunEvents(runId);
          if (token !== recoveryToken) {
            discardExperienceRecovery(token);
            return;
          }
          failedAttempts = 0;
          const applied = applyRunEvents(result.events, target);
          this.recoveredTruncated = applied.truncated;
          // R6：计划与审批也要在断线恢复后回到同一状态
          if (applied.plan) {
            this.livePlan = applied.plan;
          }
          const active =
            result.state === 'QUEUED' ||
            result.state === 'RUNNING' ||
            result.state === 'WAITING_APPROVAL' ||
            result.state === 'WAITING_USER_INPUT';
          const approvalId = applied.approval?.approvalId;
          if (approvalId) {
            if (!active || applied.approval?.resolved) {
              // 已结束或已处理：不把过期的审批卡片带回界面
              this.pendingApprovals = this.pendingApprovals.filter((a) => a.approvalId !== approvalId);
            } else if (!this.pendingApprovals.some((a) => a.approvalId === approvalId)) {
              this.pendingApprovals.push({
                approvalId,
                tool: applied.approval!.tool,
                risk: applied.approval!.risk,
                expiresAt: 0,
                sessionId: this.currentSessionId
              });
            }
          }
          // P7-06：还原/清除等待中的决策询问卡片（刷新、断线重连都要能回到同一状态）
          if (applied.userQuestion) {
            this.pendingUserQuestion = { ...applied.userQuestion, runId };
          } else if (this.pendingUserQuestion?.runId === runId) {
            this.pendingUserQuestion = null;
          }
          // P5-10-3：usage 不落库，恢复后至少用 run 的 token 汇总兜底；轮次保持未知
          if (!this.liveBudget && (result.totalTokens ?? 0) > 0) {
            this.liveBudget = {
              promptTokens: result.promptTokens ?? 0,
              completionTokens: result.completionTokens ?? 0,
              totalTokens: result.totalTokens as number
            };
          }
          if (
            result.state === 'QUEUED' ||
            result.state === 'RUNNING' ||
            result.state === 'WAITING_APPROVAL' ||
            result.state === 'WAITING_USER_INPUT'
          ) {
            target.streaming = true;
            target.failed = false;
            this.streamStatus = 'reconnecting';
            continue;
          }
          target.streaming = false;
          // 已收到终态并完成事件重放即代表传输恢复成功；模型/工具终态另由现有 UI 表达。
          finishExperienceRecovery(token, 'recovered', pollAttempts);
          if (result.state === 'SUCCEEDED') {
            this.streamStatus = 'recovered';
            // 以落库的完整回答为准：检查点对超长回答会截断
            if (this.currentSessionId != null) {
              await this.loadSessionMessages(this.currentSessionId, true);
              // 历史 trace 可能还没有计划字段，重载后把刚恢复的快照补回去
              if (applied.plan) {
                this.livePlan = applied.plan;
              }
            }
          } else if (result.state === 'CANCELLED') {
            this.streamStatus = 'interrupted';
            target.stopped = true;
            if (!target.content) {
              target.content = '（已停止生成）';
            }
          } else {
            this.streamStatus = 'interrupted';
            target.failed = true;
            target.errorText = result.errorMessage || '这一轮没有完成';
          }
          return;
        } catch {
          failedAttempts += 1;
          if (failedAttempts >= maxAttempts) {
            target.streaming = false;
            if (!target.content) {
              target.failed = true;
              target.errorText = '网络连接中断了，恢复失败，请重新发送';
            }
            this.streamStatus = 'interrupted';
            finishExperienceRecovery(token, 'failed', pollAttempts);
            return;
          }
          this.streamStatus = 'reconnecting';
        }
      }
      discardExperienceRecovery(token);
    },

    /**
     * P5-10：刷新或切回会话时，如果这一轮后端还在跑，就挂上它的 runId 继续收内容。
     * 返回 true 表示已经接管（调用方不要再把气泡标成失败）。
     */
    async restoreSessionRun(sessionId: number, target: UiMessage): Promise<boolean> {
      try {
        const runs = await agentApi.listRunningRuns();
        // 查询期间用户可能已经切走，别把旧会话的轮次接管到当前界面
        if (this.currentSessionId !== sessionId) return false;
        const run = runs
          .filter((r) => r.sessionId === sessionId)
          .sort((a, b) => b.runId - a.runId)[0];
        if (!run) return false;
        this.activeRunId = run.runId;
        target.failed = false;
        target.errorText = undefined;
        target.streaming = true;
        void this.recoverRun(run.runId, target);
        return true;
      } catch {
        // 查询失败就按「没有在跑的轮次」处理，交给调用方标中断
        return false;
      }
    },

    /** 保存输入草稿：空串即删除该键，避免 localStorage 里堆积空值 */
    saveDraft(key: string, text: string) {
      if (text) {
        if (this.drafts[key] === text) return;
        this.drafts[key] = text;
      } else {
        if (!this.drafts[key]) return;
        delete this.drafts[key];
      }
      localStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify(this.drafts));
    },

    draftFor(key: string): string {
      return this.drafts[key] ?? '';
    },

    clearDraft(key: string) {
      this.saveDraft(key, '');
    },

    /**
     * 把当前输入区里已上传成功、尚未发送的附件按会话键落盘。
     * 文件内容已在后端（附件 id 就是句柄），刷新后重新调用接口即可继续发送，不需要重传。
     */
    saveDraftChips(key: string) {
      const chips: DraftChipMeta[] = this.pendingChips
        .filter((c) => c.status === 'done' && c.id)
        .map((c) => ({ id: c.id as number, fileName: c.fileName }));
      if (chips.length) {
        this.draftChips[key] = chips;
      } else {
        if (!this.draftChips[key]) return;
        delete this.draftChips[key];
      }
      localStorage.setItem(DRAFT_CHIPS_STORAGE_KEY, JSON.stringify(this.draftChips));
    },

    /**
     * 回填某个会话未发送的附件。没有持久化记录时保留当前 chips（如 sessionAttachments 带来的已发送附件），
     * 避免"切回会话反而把附件清空"。
     */
    restoreDraftChips(key: string) {
      const saved = this.draftChips[key];
      if (!saved || saved.length === 0) return;
      this.pendingChips.forEach((c) => c.previewUrl && URL.revokeObjectURL(c.previewUrl));
      this.pendingChips = saved.map((a) => ({
        key: `d${a.id}`,
        fileName: a.fileName,
        status: 'done' as const,
        id: a.id
      }));
    },

    clearDraftChips(key: string) {
      if (!this.draftChips[key]) return;
      delete this.draftChips[key];
      localStorage.setItem(DRAFT_CHIPS_STORAGE_KEY, JSON.stringify(this.draftChips));
    },

    async removeSession(sessionId: number) {
      await agentApi.deleteSession(sessionId);
      this.clearDraft(String(sessionId));
      this.clearDraftChips(String(sessionId));
      if (this.currentSessionId === sessionId) this.startNewSession();
      await this.loadAllSessions();
    },

    /** 停止生成：先通知后端中止（长命令执行时没有 SSE 写出，只断开连接后端感知不到），再中断本地流 */
    stopGenerating() {
      const sessionId = this.currentSessionId;
      // P5-10：同时停掉断点恢复轮询，用户已经明确不要这一轮了
      recoveryToken++;
      this.streamStatus = 'idle';
      this.activeRunId = null;
      if (sessionId != null) {
        agentApi.stopGeneration(sessionId).catch(() => undefined);
      }
      currentAbort?.abort();
      currentAbort = null;
      // 停止生成时后端会拒绝该会话所有审批，前端同步清空卡片
      if (sessionId != null) {
        this.pendingApprovals = this.pendingApprovals.filter((a) => a.sessionId !== sessionId);
        this.approvalRevision += 1;
      }
      // P7-06：Run 已停止，后端 ask_user_question 会在 500ms 内收到取消标记退出等待
      this.pendingUserQuestion = null;
    },

    /** 第六十八批 A2：提交工具审批结果；不再在这里删卡片——等后端 approval_resolved 事件后切 resolved 态，或用户手动关叉 */
    async respondApproval(approvalId: string, decision: 'APPROVE' | 'REJECT', rememberSession = false) {
      try {
        await agentApi.respondApproval(approvalId, decision, rememberSession ? 'SESSION' : 'NONE');
      } catch (e) {
        // P5-10-2：提交失败时保留待审批，不能在本地伪造已处理
        ElMessage.error('审批提交失败: ' + (e as Error).message);
        return;
      }
      // 乐观更新：后端大概率会发 approval_resolved，但 SSE 事件有延迟；先标个 resolved 占位，避免用户看到"已点了但还没状态"
      const idx = this.pendingApprovals.findIndex((a) => a.approvalId === approvalId);
      if (idx >= 0 && !this.pendingApprovals[idx].resolved) {
        // 来源先按 USER 占位，服务端事件到达后以真实 source 为准
        this.pendingApprovals[idx] = { ...this.pendingApprovals[idx], resolved: decision, resolvedSource: 'USER' };
      }
      this.approvalRevision += 1;
    },

    // -----------------------------------------------------------------
    // P7-06：ask_user_question 处理
    // -----------------------------------------------------------------

    /** SSE user_question 事件到达：agent 暂停等用户回答决策询问 */
    handleUserQuestion(question: UserQuestion) {
      if (!question.runId) {
        // 没有 runId 的旧后端消息忽略
        console.warn('handleUserQuestion: 缺少 runId', question);
        return;
      }
      this.pendingUserQuestion = { ...question, runId: question.runId };
    },

    /** 提交用户回答；成功后清除 pending question */
    async answerUserQuestion(runId: number, questionId: string, selected: string[] = [], custom?: string) {
      await agentApi.answerUserQuestion(runId, questionId, selected, custom);
      // 清除本地 pending——后端恢复 Run 后会继续 SSE 流，不需要额外事件
      this.pendingUserQuestion = null;
    },

    /** P5-10-2：批量允许低风险写入；只按服务端返回的 resolvedIds 标记，不夸大成功 */
    async approveBatch(approvalIds: string[]) {
      const sessionId = this.currentSessionId;
      if (sessionId == null || !approvalIds.length) return;
      try {
        const { batchId, resolvedIds } = await agentApi.approveBatch(sessionId, approvalIds);
        const done = new Set(resolvedIds);
        this.pendingApprovals = this.pendingApprovals.map((a) =>
          done.has(a.approvalId) && !a.resolved
            ? { ...a, resolved: 'APPROVE' as const, resolvedSource: 'BATCH' as const, batchId }
            : a
        );
        this.approvalRevision += 1;
      } catch (e) {
        ElMessage.error('批量允许失败: ' + (e as Error).message);
      }
    },

    /** P5-10-2：加载会话内已处理审批历史；返回时校验会话没被切走，避免串数据 */
    async loadApprovalHistory(sessionId?: number | null) {
      const sid = sessionId === undefined ? this.currentSessionId : sessionId;
      if (sid == null) {
        this.approvalHistory = [];
        return;
      }
      this.approvalHistoryLoading = true;
      try {
        const items = await agentApi.getSessionApprovals(sid);
        if (this.currentSessionId === sid) this.approvalHistory = items;
      } catch {
        if (this.currentSessionId === sid) this.approvalHistory = [];
      } finally {
        this.approvalHistoryLoading = false;
      }
    },

    /** 审批超时 / 过期：仅移除卡片，后端到时间会自动拒绝 */
    dismissApproval(approvalId: string) {
      this.pendingApprovals = this.pendingApprovals.filter((a) => a.approvalId !== approvalId);
    },

    /** P5-12-4：从轨迹面板点待审批步骤，定位到审批区 */
    requestApprovalFocus() {
      this.approvalFocusToken += 1;
    },

    async renameSession(sessionId: number, title: string) {
      await agentApi.renameSession(sessionId, title.trim());
      await this.loadAllSessions();
    },

    async clearAllSessions() {
      for (const s of this.sessions) {
        await agentApi.deleteSession(s.id);
        this.clearDraft(String(s.id));
        this.clearDraftChips(String(s.id));
      }
      this.startNewSession();
      await this.loadSessions();
    },

    removeChip(key: string) {
      const chip = this.pendingChips.find((c) => c.key === key);
      if (chip?.previewUrl) URL.revokeObjectURL(chip.previewUrl);
      this.pendingChips = this.pendingChips.filter((c) => c.key !== key);
    },

    /** 重新生成：截掉指定回答及其后面的消息，用之前那条用户消息重新发送 */
    async regenerate(messageId: number) {
      if (this.isProcessing) return;
      const idx = this.messages.findIndex((m) => m.id === messageId);
      if (idx < 0) return;
      let userIdx = -1;
      for (let i = idx; i >= 0; i--) {
        if (this.messages[i].role === 'user') {
          userIdx = i;
          break;
        }
      }
      if (userIdx < 0) return;
      const text = this.messages[userIdx].content;
      this.messages = this.messages.slice(0, userIdx);
      await this.sendMessage(text);
    },

    /** P5-10-3：记录阶段首次到达；只前进不后退，重复到达不刷新时间 */
    markLiveStage(key: TaskStageKey) {
      if (!this.isProcessing || this.liveStageMarks[key]) return;
      const latest = TASK_STAGES.reduce(
        (max, stage, i) => (this.liveStageMarks[stage.key] ? Math.max(max, i) : max),
        -1
      );
      if (TASK_STAGES.findIndex((stage) => stage.key === key) < latest) return;
      this.liveStageMarks[key] = Date.now();
    },

    /** P5-10-3：停止后继续——把上一条未完成的回答接上，不要求用户重述任务 */
    async continueTask(messageId: number) {
      if (this.isProcessing) return;
      const target = this.messages.find((m) => m.id === messageId);
      if (!target || !target.stopped) return;
      await this.sendMessage(CONTINUE_PROMPT);
    },

    async uploadAttachment(file: File) {
      if (file.size > 20 * 1024 * 1024) {
        ElMessage.error('文件大小超过 20MB 限制');
        return;
      }
      // 同样需要响应式：上传状态要能从"上传中"切到"已完成"
      const chip = reactive<AttachmentChip>({
        key: `c${++seq}`,
        fileName: file.name,
        status: 'uploading',
        // 图片直接给本地缩略图，不用等后端返回
        previewUrl: file.type.startsWith('image/') ? URL.createObjectURL(file) : undefined
      });
      this.pendingChips.push(chip);
      try {
        const formData = new FormData();
        formData.append('file', file);
        const att = await agentApi.uploadChatAttachment(formData);
        chip.status = 'done';
        chip.id = att.id;
      } catch (e) {
        chip.status = 'error';
        ElMessage.error('上传失败: ' + (e as Error).message);
      }
    },

    mergeSessionAttachments(sessionId: number, attachments: SessionAttachment[]) {
      const existing = this.sessionAttachments[sessionId] || [];
      const map = new Map<number, SessionAttachment>();
      [...existing, ...attachments].forEach((a) => map.set(a.id, a));
      this.sessionAttachments[sessionId] = [...map.values()];
    },

    async sendMessage(text: string) {
      if (this.isProcessing) return;
      const trimmed = text.trim();
      const attachmentIds = this.pendingAttachmentIds;
      if (!trimmed && attachmentIds.length === 0) return;

      // 发送时后端会把本轮策略写回会话：同步本地缓存，切走再切回时显示才不会落后
      const currentInfo = this.currentSessionId != null ? this.findSession(this.currentSessionId) : undefined;
      if (currentInfo) {
        currentInfo.model = this.model;
        currentInfo.reasoning = this.reasoning;
        currentInfo.permission = this.permission;
      }

      // 快照本次要发送的附件，然后清空输入区 chips
      const sentAttachments = this.pendingChips
        .filter((c) => c.status === 'done' && c.id)
        .map((c) => ({ id: c.id as number, fileName: c.fileName }));
      const isNewSession = this.currentSessionId == null;
      if (!isNewSession && sentAttachments.length > 0) {
        this.mergeSessionAttachments(this.currentSessionId as number, sentAttachments);
      }
      this.pendingChips.forEach((c) => c.previewUrl && URL.revokeObjectURL(c.previewUrl));
      this.pendingChips = [];

      if (trimmed) {
        this.messages.push({ id: nextId(), role: 'user', content: trimmed, tools: [] });
      }
      if (sentAttachments.length > 0) {
        this.messages.push({
          id: nextId(),
          role: 'hint',
          content: `已上传 ${sentAttachments.length} 个文件`,
          tools: []
        });
      }

      // 必须是响应式对象：push 之后再改原始对象的字段，Vue 不会追踪到，
      // 表现就是流式回答已经返回、气泡却一直停在"输入中"动画上，刷新后才出现内容。
      const aiMsg = reactive<UiMessage>({
        id: nextId(),
        role: 'assistant',
        content: '',
        thinking: '',
        tools: [],
        streaming: true
      });
      const startedAt = Date.now();
      this.messages.push(aiMsg);
      // P5-11-3：SSE 每个 chunk 只保留最新全量文本，实际写响应式状态按帧合并（最多 20 次/秒）。
      // 结束、失败、停止与 finally 都会 flush，保证正文最终与后端全量文本逐字一致。
      const contentThrottle = createFrameThrottle<string>((full) => {
        aiMsg.content = full;
      });
      const thinkingThrottle = createFrameThrottle<string>((thought) => {
        aiMsg.thinking = thought;
      });
      const flushStreamedText = () => {
        contentThrottle.flush();
        thinkingThrottle.flush();
      };
      this.isProcessing = true;
      this.livePlan = null;
      this.liveBudget = null;
      this.liveStageMarks = {};
      this.runStartedAt = startedAt;
      this.markLiveStage('understand');

      const controller = new AbortController();
      currentAbort = controller;
      // 新一轮开始：清掉上一轮的恢复状态
      recoveryToken++;
      this.activeRunId = null;
      this.recoveredTruncated = false;
      this.streamStatus = 'streaming';

      try {
        await streamChat(
          {
            prompt: trimmed,
            sessionId: this.currentSessionId,
            // 当前项目：后端据此决定检索哪个项目的资料
            projectId: useProjectStore().currentProjectId ?? undefined,
            // 个人助手定位：资料检索默认开启且不暴露开关，用户无需理解 RAG
            useRag: true,
            attachmentIds,
            // 会话头当前选择的模型 / 推理档位 / 工作权限，每轮都随请求下发
            model: this.model,
            reasoning: this.reasoning,
            permission: this.permission,
            clientRequestId: crypto.randomUUID()
          },
          {
            onSessionId: (id) => {
              this.currentSessionId = id;
              setExperienceSessionId(id);
              // 新建会话：等后端返回 sessionId 后，把本次附件正式关联
              if (isNewSession && sentAttachments.length > 0) {
                this.mergeSessionAttachments(id, sentAttachments);
              }
              // P5-12-2：新任务一建立就在侧栏点亮运行态，不用等列表接口回来
              this.markSessionRunState(id, 'RUNNING');
              this.loadSessions();
            },
            // 第六十八批 A3：本轮 id 用于「撤回本轮」文件改动
            onTurnId: (turnId) => {
              aiMsg.turnId = turnId;
            },
            // P5-10：记下运行 id，连接断了可以凭它续上这一轮
            onRunId: (runId) => {
              this.activeRunId = runId;
              markExperienceRun(runId, this.currentSessionId);
            },
            onThinking: (thought) => {
              thinkingThrottle.push(thought);
            },
            onSources: (sources) => {
              aiMsg.sources = sources;
            },
            // Agent 提交/更新任务计划：同一轮内覆盖最新的计划快照
            onPlan: (plan) => {
              aiMsg.plan = plan;
              // 同时记到 store 级别：即使消息数组被刷新替换，计划面板仍能实时显示
              this.livePlan = plan;
            },
            // 危险工具执行前挂起：把审批项挂到当前会话，切换会话时互不干扰
            onApprovalRequired: (req) => {
              this.pendingApprovals = this.pendingApprovals.filter((a) => a.approvalId !== req.approvalId);
              this.pendingApprovals.push({ ...req, sessionId: this.currentSessionId });
              // P5-12-2：侧栏状态点同步成「待审批」，和审批卡片一致
              this.markSessionRunState(this.currentSessionId, 'WAITING_APPROVAL');
            },
            // 审批被处理（用户点击 / 超时 / 停止会话）：标记为 resolved，卡片切到"已处理"态并给叉号
            onApprovalResolved: (approvalId, decision, source, batchId) => {
              const idx = this.pendingApprovals.findIndex((a) => a.approvalId === approvalId);
              if (idx >= 0) {
                this.pendingApprovals[idx] = {
                  ...this.pendingApprovals[idx],
                  resolved: decision,
                  resolvedSource: source,
                  batchId
                };
              }
              // P5-10-2：通知追踪页刷新审批历史
              this.approvalRevision += 1;
              // P5-12-2：处理完就回到运行中，终态由本轮收尾统一落
              this.markSessionRunState(this.currentSessionId, 'RUNNING');
            },
            // P7-06：agent 暂停等用户回答决策询问
            onUserQuestion: (question) => {
              this.handleUserQuestion(question);
            },
            //同一文件被多次改动时只保留最新一条，避免面板里重复堆叠
            onFileChange: (change: FileChange) => {
              if (!aiMsg.changes) aiMsg.changes = [];
              const idx = aiMsg.changes.findIndex((c) => c.path === change.path);
              if (idx >= 0) aiMsg.changes[idx] = change;
              else aiMsg.changes.push(change);
              this.markLiveStage('modify');
            },
            onToolCall: (tool, args) => {
              aiMsg.tools.push({ name: tool, args, status: 'running', startedAt: Date.now() } as ToolInvocation);
              const stage = stageForTool(tool, !!this.liveStageMarks.modify);
              if (stage) this.markLiveStage(stage);
            },
            onToolResult: (tool, data) => {
              const target =
                [...aiMsg.tools].reverse().find((t) => t.name === tool && t.status === 'running') ||
                aiMsg.tools[aiMsg.tools.length - 1];
              if (target) {
                target.status = data.success ? 'success' : 'error';
                target.result = data.content;
                target.error = data.error;
                target.costMs = data.costMs;
              }
            },
            onContent: (full) => {
              contentThrottle.push(full);
            },
            onCompletion: (completion) => {
              aiMsg.completion = completion;
            },
            onDiagnostics: (diagnostics) => {
              aiMsg.diagnostics = diagnostics;
              this.markLiveStage('verify');
            },
            onVerify: (verify) => {
              aiMsg.verify = verify;
              this.markLiveStage('verify');
            },
            // P5-10-3：每轮实时预算进度；旧后端不会发 usage 时保持 null 降级展示
            onUsage: (usage) => {
              this.liveBudget = usage;
              // 气泡上展示本轮用量：usage 是 Run 累计值，取最新一次即可
              aiMsg.usage = {
                promptTokens: usage.promptTokens,
                completionTokens: usage.completionTokens,
                totalTokens: usage.totalTokens
              };
            },
            onDone: () => {
              // 先提交最后一段文本，再切到完成态：避免合帧丢掉结尾或最后一帧仍是流式渲染
              flushStreamedText();
              this.markLiveStage('summarize');
              aiMsg.streaming = false;
              this.streamStatus = 'idle';
              this.activeRunId = null;
              // P7-06：Run 已结束，等待中的询问不可能再被回答（超时/取消后后端会拉回 RUNNING）
              this.pendingUserQuestion = null;
              // 思考内容保留：完成后折叠成「思考过程」入口，切回会话也仍能看到
            },
            onError: (message) => {
              flushStreamedText();
              aiMsg.streaming = false;
              aiMsg.failed = true;
              this.streamStatus = 'idle';
              this.activeRunId = null;
              // P7-06：Run 已失败，残留的询问卡片不再可用
              this.pendingUserQuestion = null;
              // 只记失败原因，不覆盖已经流出来的半截回答
              aiMsg.errorText = message || '这一轮没有完成';
            }
          },
          controller.signal
        );
        // 流正常结束（无论是否收到 done）先把合帧中的最后一段提交出去
        flushStreamedText();
        // P5-10：流先结束了却没收到 done（后端收尾前连接被掐断）——按 runId 把这一轮补完
        if (aiMsg.streaming && !controller.signal.aborted) {
          if (this.activeRunId != null) {
            await this.recoverRun(this.activeRunId, aiMsg);
          } else {
            aiMsg.streaming = false;
            aiMsg.failed = true;
            aiMsg.errorText = '连接提前结束了，请稍后再试';
            this.streamStatus = 'idle';
          }
        }
      } catch (e) {
        flushStreamedText();
        aiMsg.streaming = false;
        if (controller.signal.aborted) {
          // 用户主动停止：保留已生成的部分，不标成失败
          aiMsg.stopped = true;
          this.streamStatus = 'idle';
          if (!aiMsg.content) aiMsg.content = '（已停止生成）';
        } else if (this.activeRunId != null) {
          // P5-10：连接断了但后端还在跑——按 runId 续上已生成内容和后续输出
          await this.recoverRun(this.activeRunId, aiMsg);
        } else {
          aiMsg.failed = true;
          this.streamStatus = 'idle';
          const raw = (e as Error).message || '';
          // 浏览器网络层错误只会说 "Failed to fetch"，对普通用户没有意义
          aiMsg.errorText = /failed to fetch|networkerror|load failed/i.test(raw)
            ? '网络连接中断了'
            : raw || '这一轮没有完成';
        }
      } finally {
        flushStreamedText();
        // 成功、失败、主动停止都记一次耗时；完成后的折叠摘要和执行轨迹共用这个值
        aiMsg.durationMs = Date.now() - startedAt;
        if (currentAbort === controller) currentAbort = null;
        this.isProcessing = false;
        // P5-12-2：本轮收尾，侧栏状态点落到终态（失败 / 停止 / 完成）
        this.markSessionRunState(
          this.currentSessionId,
          aiMsg.failed ? 'FAILED' : aiMsg.stopped ? 'CANCELLED' : 'SUCCEEDED'
        );
        // 生成结束后刷新审查流与任务交付视图
        void this.refreshChanges();
      }
    },

    // ——— 文件变更审查流 ———

    async fetchPendingChanges(projectId: number) {
      this.pendingChangesLoading = true;
      try {
        this.pendingChanges = await fileChangesApi.fetchPendingChanges(projectId);
      } catch {
        this.pendingChanges = [];
      } finally {
        this.pendingChangesLoading = false;
      }
    },

    // ——— P5-14 任务级交付 ———

    /** 拉取当前会话的 ChangeSet；刷新后由后端事件重建同一视图 */
    async fetchChangeSet() {
      const sessionId = this.currentSessionId;
      if (sessionId == null) {
        this.changeSet = null;
        return;
      }
      this.changeSetLoading = true;
      try {
        const view = await fileChangesApi.fetchChangeSet(
          sessionId,
          this.changeSetRunFilter ?? undefined
        );
        if (this.currentSessionId === sessionId) this.changeSet = view;
      } catch {
        if (this.currentSessionId === sessionId) this.changeSet = null;
      } finally {
        this.changeSetLoading = false;
      }
    },

    /** 同时刷新项目级待审列表与任务交付视图 */
    async refreshChanges() {
      const projectStore = useProjectStore();
      if (projectStore.currentProjectId != null) {
        await this.fetchPendingChanges(projectStore.currentProjectId);
      }
      await this.fetchChangeSet();
    },

    /** 按单个 Run 过滤交付视图；传 null 恢复全部 */
    async setChangeSetRunFilter(runId: number | null) {
      this.changeSetRunFilter = runId;
      await this.fetchChangeSet();
    },

    /** 任务级整体撤回：逆序回滚，外部修改返回冲突 */
    async revertTask(): Promise<boolean> {
      const sessionId = this.currentSessionId;
      if (sessionId == null) {
        ElMessage.warning('当前没有可撤回的任务');
        return false;
      }
      try {
        const resp = await fileChangesApi.revertSession(sessionId);
        this.reportRevert(resp, '已撤回');
        await this.refreshChanges();
        return resp.count > 0;
      } catch (e: any) {
        ElMessage.error(e?.message || '整体撤回失败');
        return false;
      }
    },

    /** 按 Run 撤回：只回滚该 Run 对应的 PENDING 变更 */
    async revertRunChanges(runId: number): Promise<boolean> {
      const sessionId = this.currentSessionId;
      if (sessionId == null) {
        ElMessage.warning('当前没有可撤回的任务');
        return false;
      }
      try {
        const resp = await fileChangesApi.revertRun(sessionId, runId);
        this.reportRevert(resp, `已撤回 #${runId}`);
        await this.refreshChanges();
        return resp.count > 0;
      } catch (e: any) {
        ElMessage.error(e?.message || '按 Run 撤回失败');
        return false;
      }
    },

    /** 继续修复：沿用当前会话，自动携带失败上下文与未完成计划 */
    async continueFix(): Promise<boolean> {
      const prompt = this.changeSet?.continuePrompt?.trim();
      if (!prompt) {
        ElMessage.warning('暂无可继续修复的失败上下文');
        return false;
      }
      await this.sendMessage(prompt);
      return true;
    },

    reportRevert(
      resp: { count: number; failed: number; skippedAccepted: number; conflicts: { reason: string }[] },
      prefix: string
    ) {
      const parts: string[] = [];
      if (resp.count > 0) parts.push(`${prefix} ${resp.count} 个文件变更`);
      if (resp.skippedAccepted > 0) parts.push(`${resp.skippedAccepted} 个已接受变更保留审计`);
      if (resp.failed > 0) parts.push(`${resp.failed} 个失败`);
      const conflictCount = resp.conflicts?.length ?? 0;
      if (conflictCount > 0) parts.push(`${conflictCount} 个冲突未覆盖：${resp.conflicts[0].reason}`);
      if (resp.count > 0) {
        ElMessage.success(parts.join('，'));
      } else if (conflictCount > 0) {
        ElMessage.warning(parts.join('，'));
      } else {
        ElMessage.info(parts.join('，') || '没有可撤回的变更');
      }
    },

    async revertChange(id: number): Promise<boolean> {
      try {
        const resp = await fileChangesApi.revertChange(id);
        if (resp.ok) {
          this.pendingChanges = this.pendingChanges.filter((c) => c.id !== id);
          ElMessage.success('已撤销该文件变更');
          void this.fetchChangeSet();
          return true;
        }
        ElMessage.error(resp.conflicts?.[0]?.reason || '撤销失败');
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '撤销请求失败');
        return false;
      }
    },

    async acceptChange(id: number): Promise<boolean> {
      try {
        const resp = await fileChangesApi.acceptChange(id);
        if (resp.ok) {
          this.pendingChanges = this.pendingChanges.filter((c) => c.id !== id);
          ElMessage.success('已保留该文件变更');
          void this.fetchChangeSet();
          return true;
        }
        ElMessage.error('保留失败');
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '保留请求失败');
        return false;
      }
    },

    async acceptAll(projectId: number): Promise<boolean> {
      try {
        const resp = await fileChangesApi.acceptAll(projectId);
        if (resp.ok) {
          const n = this.pendingChanges.length;
          this.pendingChanges = [];
          ElMessage.success(`已全部保留 ${n} 个文件变更`);
          void this.fetchChangeSet();
          return true;
        }
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '批量保留失败');
        return false;
      }
    },

    /**
     * P6-08：只保留传入的这些变更（卡片里列出的文件）。
     * 不走项目级 accept-all，避免把其他会话的 PENDING 一起接受；失败合并成一条提示。
     */
    async acceptChanges(ids: number[]): Promise<boolean> {
      if (!ids.length) return false;
      const results = await Promise.allSettled(ids.map((id) => fileChangesApi.acceptChange(id)));
      const accepted = new Set<number>();
      results.forEach((result, idx) => {
        if (result.status === 'fulfilled' && result.value?.ok) accepted.add(ids[idx]);
      });
      if (accepted.size === 0) {
        ElMessage.error('保留失败，请稍后重试');
        return false;
      }
      this.pendingChanges = this.pendingChanges.filter((c) => !accepted.has(c.id));
      const failed = ids.length - accepted.size;
      ElMessage.success(
        `已保留 ${accepted.size} 个文件变更${failed ? `（${failed} 个失败）` : ''}`
      );
      void this.fetchChangeSet();
      return true;
    },

    async revertAll(projectId: number): Promise<boolean> {
      try {
        const resp = await fileChangesApi.revertAll(projectId);
        if (resp.ok) {
          this.pendingChanges = [];
          ElMessage.success(`已撤销 ${resp.count} 个文件变更${resp.failed ? `（${resp.failed} 个失败）` : ''}`);
          void this.fetchChangeSet();
          return true;
        }
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '批量撤销失败');
        return false;
      }
    },

    /** 第六十八批 A3：撤回最近一轮的改动（按 sessionId + turnId 精确定位） */
    async revertTurn(): Promise<boolean> {
      const sessionId = this.currentSessionId;
      let turnId: string | undefined;
      for (let i = this.messages.length - 1; i >= 0; i--) {
        const m = this.messages[i];
        if (m.role === 'assistant' && m.turnId) {
          turnId = m.turnId;
          break;
        }
      }
      if (sessionId == null || !turnId) {
        ElMessage.warning('暂无可撤回的本轮改动');
        return false;
      }
      try {
        const resp = await fileChangesApi.revertTurn(sessionId, turnId);
        if (resp.ok) {
          this.pendingChanges = this.pendingChanges.filter(
            (c) => c.sessionId !== sessionId || c.turnId !== turnId
          );
          ElMessage.success(
            `已撤回本轮 ${resp.count} 个文件变更${resp.failed ? `（${resp.failed} 个失败）` : ''}`
          );
          void this.fetchChangeSet();
          return true;
        }
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '按轮次撤回失败');
        return false;
      }
    }
  }
});
