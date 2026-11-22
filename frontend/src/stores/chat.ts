import { defineStore } from 'pinia';
import { reactive } from 'vue';
import { ElMessage } from 'element-plus';
import * as agentApi from '../api/agent';
import * as settingsApi from '../api/settings';
import { resumeChatRun, sendChatMessage } from '../services/sendMessageService';
import { streamSession } from '../services/streamSession';
import { replayRunEvents } from '../services/runRecovery';
import { TASK_STAGES } from '../utils/taskStages';
import { useProjectStore } from './project';
import { useApprovalStore } from './approvals';
import { useFileChangeStore } from './fileChanges';
import { useMessagesStore } from './messages';
import type { SessionAttachment } from './messages';
import { useQuestionStore } from './question';
import { useStreamStore } from './stream';
import {
  beginExperienceRecovery,
  discardExperienceRecovery,
  finishExperienceRecovery,
  setExperienceSessionId
} from '../utils/experienceDiagnostics';
import type { ChangeSetView, PendingApproval, PendingFileChange, SessionInfo, UiMessage } from '../types/api';
import type {
  ApprovalHistoryItem,
  PermissionLevel,
  PlanSnapshot,
  ReasoningLevel,
  RunUsage,
  TaskStageKey,
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

let seq = 0;

/**
 * P5-10：断点恢复的代际标记由 `services/streamSession` 统一管理。
 * 切换会话 / 重新发送 / 主动停止时作废代际，让上一轮还在跑的恢复轮询自行退出，
 * 避免它把旧轮次的内容写到新会话的气泡上。
 */

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
  if (recovered.subtasks.length) target.subtasks = recovered.subtasks;
  return recovered;
}

// 当前流式请求的中断句柄与恢复代际统一由 streamSession 管理：
// 用户点「停止」时直接 abort，浏览器断开后后端写 SSE 会失败并置 cancelled，
// 从而停止继续消耗 token。

// ——— P5-10-3：阶段时间线、预算与失败分级 ———
// R6c：纯函数移到 `utils/taskStages`（发送编排服务也要用），这里原样 re-export 保持导入路径不变。

/** 停止后「继续」发送的固定提示，避免用户重述任务 */
export const CONTINUE_PROMPT = '请继续完成上一条未完成的回答，从中断处接着做，不要重复已完成的部分。';

export { buildStageTimeline, classifyFailure, TASK_STAGES, stageForTool } from '../utils/taskStages';

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
    isProcessing: false,
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
    },

    // ——— R6c：消息域 store 的兼容访问器；视图继续用 chat.messages / chat.messagesLoading ———
    messages(): UiMessage[] {
      return useMessagesStore().messages;
    },
    messagesLoading(): boolean {
      return useMessagesStore().messagesLoading;
    },
    loadError(): string {
      return useMessagesStore().loadError;
    },
    sessionAttachments(): Record<number, SessionAttachment[]> {
      return useMessagesStore().sessionAttachments;
    },

    // ——— R6：域 store 的兼容访问器；视图继续用 chat.xxx，写入走下方 action ———
    livePlan(): PlanSnapshot | null {
      return useStreamStore().livePlan;
    },
    liveBudget(): RunUsage | null {
      return useStreamStore().liveBudget;
    },
    liveStageMarks(): Partial<Record<TaskStageKey, number>> {
      return useStreamStore().liveStageMarks;
    },
    runStartedAt(): number {
      return useStreamStore().runStartedAt;
    },
    streamStatus(): 'idle' | 'streaming' | 'reconnecting' | 'recovered' | 'interrupted' {
      return useStreamStore().streamStatus;
    },
    activeRunId(): number | null {
      return useStreamStore().activeRunId;
    },
    recoveredTruncated(): boolean {
      return useStreamStore().recoveredTruncated;
    },
    pendingApprovals(): PendingApproval[] {
      return useApprovalStore().pendingApprovals;
    },
    approvalFocusToken(): number {
      return useApprovalStore().approvalFocusToken;
    },
    approvalRevision(): number {
      return useApprovalStore().approvalRevision;
    },
    approvalHistory(): ApprovalHistoryItem[] {
      return useApprovalStore().approvalHistory;
    },
    approvalHistoryLoading(): boolean {
      return useApprovalStore().approvalHistoryLoading;
    },
    pendingUserQuestion(): (UserQuestion & { runId: number }) | null {
      return useQuestionStore().pendingUserQuestion;
    },
    pendingChanges(): PendingFileChange[] {
      return useFileChangeStore().pendingChanges;
    },
    pendingChangesLoading(): boolean {
      return useFileChangeStore().pendingChangesLoading;
    },
    changeSet(): ChangeSetView | null {
      return useFileChangeStore().changeSet;
    },
    changeSetLoading(): boolean {
      return useFileChangeStore().changeSetLoading;
    },
    changeSetRunFilter(): number | null {
      return useFileChangeStore().changeSetRunFilter;
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
      useMessagesStore().clearAttachments();
      // 切换项目时重新拉审查流（新项目的 PendingFileChange）
      const projectStore = useProjectStore();
      useFileChangeStore().reset();
      if (projectStore.currentProjectId != null) {
        await this.fetchPendingChanges(projectStore.currentProjectId);
      }
    },
    startNewSession() {
      // 新会话：作废上一会话可能还在跑的断点恢复
      streamSession.cancelRecovery();
      useStreamStore().resetSessionState();
      useQuestionStore().clear();
      this.currentSessionId = null;
      useMessagesStore().reset();
      this.pendingChips = [];
      // P7-06：决策询问属于上一会话的 Run，切会话/新会话时清掉，避免答到别的会话
      useApprovalStore().clearHistory();
      const files = useFileChangeStore();
      files.changeSet = null;
      files.changeSetRunFilter = null;
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
      streamSession.cancelRecovery();
      useStreamStore().resetSessionState();
      this.currentSessionId = sessionId;
      // P5-10-2：历史按会话隔离，切会话先清掉旧数据，等追踪页按需重拉
      useApprovalStore().clearHistory();
      // 恢复该会话已存的运行参数；老会话没存过则回落到默认档
      const info = this.findSession(sessionId) || this.sessions.find((s) => s.id === sessionId);
      if (info) {
        this.model = info.model || 'deepseek-chat';
        this.reasoning = (info.reasoning as ReasoningLevel) || 'STANDARD';
        this.permission = normalizePermission(info.permission);
      }
      this.pendingChips = (useMessagesStore().sessionAttachments[sessionId] || []).map((a) => ({
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
      const msgs = useMessagesStore();
      if (!silent) msgs.messagesLoading = true;
      msgs.loadError = '';
      try {
        const history = await agentApi.getSessionMessages(sessionId);
        useStreamStore().setPlan(null);
        // 后端只在本轮完整结束时才存助手回答：历史停在用户消息上，说明那一轮被刷新/断线/停止打断了。
        // P5-10：先按 runId 尝试恢复（可能仍在生成，也可能只留下半截检查点），恢复不了再标成中断。
        const pending = msgs.setHistory(history);
        if (pending) {
          this.restoreSessionRun(sessionId, pending).then(async (restored) => {
            if (restored || msgs.messages[msgs.messages.length - 1] !== pending) return;
            // R7b：进程重启后这一轮已是 FAILED，但可能留有断点——优先给「继续执行」而不是只能重发
            const resumable = await agentApi.getResumableRun(sessionId).catch(() => null);
            if (msgs.messages[msgs.messages.length - 1] !== pending) return;
            pending.failed = true;
            if (resumable?.resumable && resumable.runId != null) {
              pending.resumableRunId = resumable.runId;
              pending.errorText = '上一轮被应用重启中断，已保留断点';
              return;
            }
            pending.errorText = '这一轮还没有回答（可能已中断或页面刷新）';
          });
        }
      } catch (e) {
        // 保留空列表而不是旧会话内容，避免把上一个会话的消息当成当前会话展示
        msgs.reset();
        msgs.loadError = (e as Error).message || '会话加载失败';
      } finally {
        msgs.messagesLoading = false;
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
      const token = streamSession.newRecoveryToken();
      const stream = useStreamStore();
      const approvals = useApprovalStore();
      const question = useQuestionStore();
      beginExperienceRecovery(runId, this.currentSessionId, token);
      stream.setStatus('reconnecting');
      let failedAttempts = 0;
      let pollAttempts = 0;
      let firstPoll = true;
      while (streamSession.isRecoveryCurrent(token)) {
        // 首次很快接上；正常跟随时 1.5s 一次；连续失败按 1s→2s→4s 退避
        const wait = firstPoll
          ? 600
          : failedAttempts > 0
            ? Math.min(8000, 1000 * 2 ** (failedAttempts - 1))
            : 1500;
        firstPoll = false;
        await delay(wait);
        if (!streamSession.isRecoveryCurrent(token)) {
          discardExperienceRecovery(token);
          return;
        }
        pollAttempts += 1;
        try {
          const result = await agentApi.getRunEvents(runId);
          if (!streamSession.isRecoveryCurrent(token)) {
            discardExperienceRecovery(token);
            return;
          }
          failedAttempts = 0;
          const applied = applyRunEvents(result.events, target);
          stream.setRecoveredTruncated(applied.truncated);
          // R6：计划与审批也要在断线恢复后回到同一状态
          if (applied.plan) {
            stream.setPlan(applied.plan);
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
              approvals.dismissApproval(approvalId);
            } else if (!approvals.pendingApprovals.some((a) => a.approvalId === approvalId)) {
              approvals.request(
                {
                  approvalId,
                  tool: applied.approval!.tool,
                  risk: applied.approval!.risk,
                  expiresAt: 0
                },
                this.currentSessionId
              );
            }
          }
          // P7-06：还原/清除等待中的决策询问卡片（刷新、断线重连都要能回到同一状态）
          if (applied.userQuestion) {
            question.ask({ ...applied.userQuestion, runId });
          } else {
            question.clearForRun(runId);
          }
          // P5-10-3：usage 不落库，恢复后至少用 run 的 token 汇总兜底；轮次保持未知
          if (!stream.liveBudget && (result.totalTokens ?? 0) > 0) {
            stream.setBudget({
              promptTokens: result.promptTokens ?? 0,
              completionTokens: result.completionTokens ?? 0,
              totalTokens: result.totalTokens as number
            });
          }
          if (
            result.state === 'QUEUED' ||
            result.state === 'RUNNING' ||
            result.state === 'WAITING_APPROVAL' ||
            result.state === 'WAITING_USER_INPUT'
          ) {
            target.streaming = true;
            target.failed = false;
            stream.setStatus('reconnecting');
            continue;
          }
          target.streaming = false;
          // 已收到终态并完成事件重放即代表传输恢复成功；模型/工具终态另由现有 UI 表达。
          finishExperienceRecovery(token, 'recovered', pollAttempts);
          if (result.state === 'SUCCEEDED') {
            stream.setStatus('recovered');
            // 以落库的完整回答为准：检查点对超长回答会截断
            if (this.currentSessionId != null) {
              await this.loadSessionMessages(this.currentSessionId, true);
              // 历史 trace 可能还没有计划字段，重载后把刚恢复的快照补回去
              if (applied.plan) {
                stream.setPlan(applied.plan);
              }
            }
          } else if (result.state === 'CANCELLED') {
            stream.setStatus('interrupted');
            target.stopped = true;
            if (!target.content) {
              target.content = '（已停止生成）';
            }
          } else {
            stream.setStatus('interrupted');
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
            stream.setStatus('interrupted');
            finishExperienceRecovery(token, 'failed', pollAttempts);
            return;
          }
          stream.setStatus('reconnecting');
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
        useStreamStore().setRunId(run.runId);
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

    /** 断线恢复提示几秒后自动收起：只把「已恢复」落回空闲，不打断运行中的流 */
    clearStreamNotice() {
      const stream = useStreamStore();
      if (stream.streamStatus === 'recovered') stream.setStatus('idle');
    },

    /** 停止生成：先通知后端中止（长命令执行时没有 SSE 写出，只断开连接后端感知不到），再中断本地流 */
    stopGenerating() {
      const sessionId = this.currentSessionId;
      // P5-10：同时停掉断点恢复轮询，用户已经明确不要这一轮了
      streamSession.cancelRecovery();
      useStreamStore().finish();
      if (sessionId != null) {
        agentApi.stopGeneration(sessionId).catch(() => undefined);
      }
      streamSession.abort();
      // 停止生成时后端会拒绝该会话所有审批，前端同步清空卡片
      useApprovalStore().clearSession(sessionId);
      // P7-06：Run 已停止，后端 ask_user_question 会在 500ms 内收到取消标记退出等待
      useQuestionStore().clear();
    },

    /** 第六十八批 A2：提交工具审批结果；等后端 approval_resolved 事件后切 resolved 态，或用户手动关叉 */
    respondApproval(approvalId: string, decision: 'APPROVE' | 'REJECT', rememberSession = false) {
      return useApprovalStore().respondApproval(approvalId, decision, rememberSession);
    },

    // -----------------------------------------------------------------
    // P7-06：ask_user_question 处理
    // -----------------------------------------------------------------

    /** SSE user_question 事件到达：agent 暂停等用户回答决策询问 */
    handleUserQuestion(question: UserQuestion) {
      useQuestionStore().ask(question);
    },

    /** 提交用户回答；成功后清除 pending question */
    answerUserQuestion(runId: number, questionId: string, selected: string[] = [], custom?: string) {
      return useQuestionStore().answer(runId, questionId, selected, custom);
    },

    /** P5-10-2：批量允许低风险写入；只按服务端返回的 resolvedIds 标记，不夸大成功 */
    approveBatch(approvalIds: string[]) {
      return useApprovalStore().approveBatch(this.currentSessionId, approvalIds);
    },

    /** P5-10-2：加载会话内已处理审批历史；返回时校验会话没被切走，避免串数据 */
    loadApprovalHistory(sessionId?: number | null) {
      const sid = sessionId === undefined ? this.currentSessionId : sessionId;
      return useApprovalStore().loadApprovalHistory(sid);
    },

    /** 审批超时 / 过期：仅移除卡片，后端到时间会自动拒绝 */
    dismissApproval(approvalId: string) {
      useApprovalStore().dismissApproval(approvalId);
    },

    /** P5-12-4：从轨迹面板点待审批步骤，定位到审批区 */
    requestApprovalFocus() {
      useApprovalStore().requestFocus();
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
      useMessagesStore().truncateFrom(userIdx);
      await this.sendMessage(text);
    },

    /** P5-10-3：记录阶段首次到达；只前进不后退，重复到达不刷新时间 */
    markLiveStage(key: TaskStageKey) {
      const stream = useStreamStore();
      if (!this.isProcessing || stream.liveStageMarks[key]) return;
      const latest = TASK_STAGES.reduce(
        (max, stage, i) => (stream.liveStageMarks[stage.key] ? Math.max(max, i) : max),
        -1
      );
      if (TASK_STAGES.findIndex((stage) => stage.key === key) < latest) return;
      stream.markStage(key);
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
      useMessagesStore().mergeSessionAttachments(sessionId, attachments);
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
      const msgs = useMessagesStore();
      const sentAttachments = this.pendingChips
        .filter((c) => c.status === 'done' && c.id)
        .map((c) => ({ id: c.id as number, fileName: c.fileName }));
      const isNewSession = this.currentSessionId == null;
      if (!isNewSession && sentAttachments.length > 0) {
        msgs.mergeSessionAttachments(this.currentSessionId as number, sentAttachments);
      }
      this.pendingChips.forEach((c) => c.previewUrl && URL.revokeObjectURL(c.previewUrl));
      this.pendingChips = [];

      if (trimmed) msgs.appendUser(trimmed);
      if (sentAttachments.length > 0) msgs.appendHint(`已上传 ${sentAttachments.length} 个文件`);

      const startedAt = Date.now();
      // R6c：气泡由 messages store 创建并挂载，返回响应式引用供流式写入
      const aiMsg = msgs.beginAssistant();
      this.isProcessing = true;
      const stream = useStreamStore();
      stream.beginRun(startedAt);
      this.markLiveStage('understand');

      const controller = streamSession.begin();
      // 新一轮开始：清掉上一轮的恢复状态
      streamSession.cancelRecovery();

      try {
        // R6c：发送与流式事件映射抽到 `services/sendMessageService`，这里只注入会话/侧栏回调
        await sendChatMessage(
          {
            prompt: trimmed,
            sessionId: this.currentSessionId,
            // 当前项目：后端据此决定检索哪个项目的资料
            projectId: useProjectStore().currentProjectId ?? undefined,
            attachmentIds,
            // 会话头当前选择的模型 / 推理档位 / 工作权限，每轮都随请求下发
            model: this.model,
            reasoning: this.reasoning,
            permission: this.permission
          },
          {
            assistant: aiMsg,
            signal: controller.signal,
            getSessionId: () => this.currentSessionId,
            onSessionCreated: (id) => {
              this.currentSessionId = id;
              setExperienceSessionId(id);
              // 新建会话：等后端返回 sessionId 后，把本次附件正式关联
              if (isNewSession && sentAttachments.length > 0) {
                msgs.mergeSessionAttachments(id, sentAttachments);
              }
              // P5-12-2：新任务一建立就在侧栏点亮运行态，不用等列表接口回来
              this.markSessionRunState(id, 'RUNNING');
              this.loadSessions();
            },
            onSessionState: (state) => this.markSessionRunState(this.currentSessionId, state),
            markStage: (key) => this.markLiveStage(key),
            recoverRun: (runId, target) => this.recoverRun(runId, target)
          }
        );
      } finally {
        // 成功、失败、主动停止都记一次耗时；完成后的折叠摘要和执行轨迹共用这个值
        aiMsg.durationMs = Date.now() - startedAt;
        if (streamSession.isActive(controller)) streamSession.release(controller);
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

    /**
     * R7b：显式续跑被进程重启中断、且保留了断点的一轮。
     * 直接复用发送链路的 SSE 事件映射，只是入口换成 resume 接口；
     * 断点里已有用户消息与工具结果，因此不会再追加一条用户气泡。
     */
    async resumeInterrupted(target: UiMessage) {
      const runId = target.resumableRunId;
      if (runId == null || this.isProcessing || this.currentSessionId == null) return;

      const startedAt = Date.now();
      target.resumableRunId = undefined;
      target.failed = false;
      target.errorText = undefined;
      target.stopped = false;
      target.streaming = true;

      this.isProcessing = true;
      const stream = useStreamStore();
      stream.beginRun(startedAt);
      stream.setRunId(runId);
      this.markSessionRunState(this.currentSessionId, 'RUNNING');

      const controller = streamSession.begin();
      streamSession.cancelRecovery();

      try {
        await resumeChatRun(runId, {
          assistant: target,
          signal: controller.signal,
          getSessionId: () => this.currentSessionId,
          onSessionCreated: () => undefined,
          onSessionState: (state) => this.markSessionRunState(this.currentSessionId, state),
          markStage: (key) => this.markLiveStage(key),
          recoverRun: (id, t) => this.recoverRun(id, t)
        });
      } finally {
        target.durationMs = Date.now() - startedAt;
        if (streamSession.isActive(controller)) streamSession.release(controller);
        this.isProcessing = false;
        this.markSessionRunState(
          this.currentSessionId,
          target.failed ? 'FAILED' : target.stopped ? 'CANCELLED' : 'SUCCEEDED'
        );
        void this.refreshChanges();
      }
    },

    // ——— 文件变更审查流（R6：状态与请求委托 fileChanges store） ———

    fetchPendingChanges(projectId: number) {
      return useFileChangeStore().fetchPendingChanges(projectId);
    },

    // ——— P5-14 任务级交付 ———

    /** 拉取当前会话的 ChangeSet；刷新后由后端事件重建同一视图 */
    fetchChangeSet() {
      return useFileChangeStore().fetchChangeSet(this.currentSessionId);
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
    setChangeSetRunFilter(runId: number | null) {
      return useFileChangeStore().setChangeSetRunFilter(this.currentSessionId, runId);
    },

    /** 任务级整体撤回：逆序回滚，外部修改返回冲突 */
    async revertTask(): Promise<boolean> {
      if (this.currentSessionId == null) {
        ElMessage.warning('当前没有可撤回的任务');
        return false;
      }
      const ok = await useFileChangeStore().revertTask(this.currentSessionId);
      await this.refreshChanges();
      return ok;
    },

    /** 按 Run 撤回：只回滚该 Run 对应的 PENDING 变更 */
    async revertRunChanges(runId: number): Promise<boolean> {
      if (this.currentSessionId == null) {
        ElMessage.warning('当前没有可撤回的任务');
        return false;
      }
      const ok = await useFileChangeStore().revertRunChanges(this.currentSessionId, runId);
      await this.refreshChanges();
      return ok;
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
      useFileChangeStore().reportRevert(resp, prefix);
    },

    async revertChange(id: number): Promise<boolean> {
      const ok = await useFileChangeStore().revertChange(id);
      if (ok) void this.fetchChangeSet();
      return ok;
    },

    async acceptChange(id: number): Promise<boolean> {
      const ok = await useFileChangeStore().acceptChange(id);
      if (ok) void this.fetchChangeSet();
      return ok;
    },

    async acceptAll(projectId: number): Promise<boolean> {
      const ok = await useFileChangeStore().acceptAll(projectId);
      if (ok) void this.fetchChangeSet();
      return ok;
    },

    /**
     * P6-08：只保留传入的这些变更（卡片里列出的文件）。
     * 不走项目级 accept-all，避免把其他会话的 PENDING 一起接受；失败合并成一条提示。
     */
    async acceptChanges(ids: number[]): Promise<boolean> {
      const ok = await useFileChangeStore().acceptChanges(ids);
      if (ok) void this.fetchChangeSet();
      return ok;
    },

    async revertAll(projectId: number): Promise<boolean> {
      const ok = await useFileChangeStore().revertAll(projectId);
      if (ok) void this.fetchChangeSet();
      return ok;
    },

    /** 第六十八批 A3：撤回最近一轮的改动（按 sessionId + turnId 精确定位） */
    async revertTurn(): Promise<boolean> {
      let turnId: string | undefined;
      for (let i = this.messages.length - 1; i >= 0; i--) {
        const m = this.messages[i];
        if (m.role === 'assistant' && m.turnId) {
          turnId = m.turnId;
          break;
        }
      }
      const ok = await useFileChangeStore().revertTurn(this.currentSessionId, turnId);
      if (ok) void this.fetchChangeSet();
      return ok;
    }
  }
});
