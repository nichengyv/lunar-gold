import { defineStore } from 'pinia';
import { reactive } from 'vue';
import { ElMessage } from 'element-plus';
import * as agentApi from '../api/agent';
import * as settingsApi from '../api/settings';
import { streamChat } from '../api/sse';
import * as fileChangesApi from '../api/fileChanges';
import { useProjectStore } from './project';
import type { FileChange, MessageTrace, PendingApproval, PendingFileChange, SessionInfo, ToolInvocation, UiMessage } from '../types/api';
import type { PermissionLevel, PlanSnapshot, ReasoningLevel } from '../types/chat';

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

// 当前流式请求的中断句柄：用户点「停止」时直接 abort，
// 浏览器断开后后端写 SSE 会失败并置 cancelled，从而停止继续消耗 token。
let currentAbort: AbortController | null = null;

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

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [] as SessionInfo[],
    // 侧栏按工作区分组展示：projectId -> 该工作区的会话列表
    sessionsByProject: {} as Record<number, SessionInfo[]>,
    currentSessionId: null as number | null,
    messages: [] as UiMessage[],
    /** 运行中的实时计划快照：SSE plan 事件直接写这里，避免依赖具体消息对象是否还在数组里 */
    livePlan: null as PlanSnapshot | null,
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
    dockTab: 'changes' as 'changes' | 'docs' | 'file' | 'trace' | 'plan' | 'metrics',
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
    // 第六十八批 A2：等待用户确认的危险工具调用（按会话隔离）
    pendingApprovals: [] as PendingApproval[]
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
    openDock(tab: 'changes' | 'docs' | 'file' | 'trace' | 'plan' | 'metrics') {
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
      if (projectStore.currentProjectId != null) {
        await this.fetchPendingChanges(projectStore.currentProjectId);
      }
    },
    startNewSession() {
      this.currentSessionId = null;
      this.messages = [];
      this.pendingChips = [];
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
      this.currentSessionId = sessionId;
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
          durationMs: trace?.durationMs
        };
      });
      // 后端只在本轮完整结束时才存助手回答：历史停在用户消息上，说明那一轮被刷新/断线/停止打断了。
      // 补一条失败气泡，否则刷新后既看不到失败提示，也只能重新输入才能再问一次。
      if (this.messages.length && this.messages[this.messages.length - 1].role === 'user') {
        this.messages.push({
          id: nextId(),
          role: 'assistant',
          content: '',
          thinking: '',
          tools: [],
          failed: true,
          errorText: '这一轮还没有回答（可能已中断或页面刷新）'
        });
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
      if (sessionId != null) {
        agentApi.stopGeneration(sessionId).catch(() => undefined);
      }
      currentAbort?.abort();
      currentAbort = null;
      // 停止生成时后端会拒绝该会话所有审批，前端同步清空卡片
      if (sessionId != null) {
        this.pendingApprovals = this.pendingApprovals.filter((a) => a.sessionId !== sessionId);
      }
    },

    /** 第六十八批 A2：提交工具审批结果；不再在这里删卡片——等后端 approval_resolved 事件后切 resolved 态，或用户手动关叉 */
    async respondApproval(approvalId: string, decision: 'APPROVE' | 'REJECT', rememberSession = false) {
      try {
        await agentApi.respondApproval(approvalId, decision, rememberSession ? 'SESSION' : 'NONE');
      } catch (e) {
        ElMessage.error('审批提交失败: ' + (e as Error).message);
      }
      // 乐观更新：后端大概率会发 approval_resolved，但 SSE 事件有延迟；先标个 resolved 占位，避免用户看到"已点了但还没状态"
      const idx = this.pendingApprovals.findIndex((a) => a.approvalId === approvalId);
      if (idx >= 0 && !this.pendingApprovals[idx].resolved) {
        this.pendingApprovals[idx] = { ...this.pendingApprovals[idx], resolved: decision };
      }
    },

    /** 审批超时 / 过期：仅移除卡片，后端到时间会自动拒绝 */
    dismissApproval(approvalId: string) {
      this.pendingApprovals = this.pendingApprovals.filter((a) => a.approvalId !== approvalId);
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
      this.isProcessing = true;
      this.livePlan = null;

      const controller = new AbortController();
      currentAbort = controller;

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
            permission: this.permission
          },
          {
            onSessionId: (id) => {
              this.currentSessionId = id;
              // 新建会话：等后端返回 sessionId 后，把本次附件正式关联
              if (isNewSession && sentAttachments.length > 0) {
                this.mergeSessionAttachments(id, sentAttachments);
              }
              this.loadSessions();
            },
            // 第六十八批 A3：本轮 id 用于「撤回本轮」文件改动
            onTurnId: (turnId) => {
              aiMsg.turnId = turnId;
            },
            onThinking: (thought) => {
              aiMsg.thinking = thought;
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
            },
            // 审批被处理（用户点击 / 超时 / 停止会话）：标记为 resolved，卡片切到"已处理"态并给叉号
            onApprovalResolved: (approvalId, decision) => {
              const idx = this.pendingApprovals.findIndex((a) => a.approvalId === approvalId);
              if (idx >= 0) {
                this.pendingApprovals[idx] = { ...this.pendingApprovals[idx], resolved: decision };
              }
            },
            //同一文件被多次改动时只保留最新一条，避免面板里重复堆叠
            onFileChange: (change: FileChange) => {
              if (!aiMsg.changes) aiMsg.changes = [];
              const idx = aiMsg.changes.findIndex((c) => c.path === change.path);
              if (idx >= 0) aiMsg.changes[idx] = change;
              else aiMsg.changes.push(change);
            },
            onToolCall: (tool, args) => {
              aiMsg.tools.push({ name: tool, args, status: 'running' } as ToolInvocation);
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
              aiMsg.content = full;
            },
            onDone: () => {
              aiMsg.streaming = false;
              // 思考内容保留：完成后折叠成「思考过程」入口，切回会话也仍能看到
            },
            onError: (message) => {
              aiMsg.streaming = false;
              aiMsg.failed = true;
              // 只记失败原因，不覆盖已经流出来的半截回答
              aiMsg.errorText = message || '这一轮没有完成';
            }
          },
          controller.signal
        );
      } catch (e) {
        aiMsg.streaming = false;
        if (controller.signal.aborted) {
          // 用户主动停止：保留已生成的部分，不标成失败
          aiMsg.stopped = true;
          if (!aiMsg.content) aiMsg.content = '（已停止生成）';
        } else {
          aiMsg.failed = true;
          const raw = (e as Error).message || '';
          // 浏览器网络层错误只会说 "Failed to fetch"，对普通用户没有意义
          aiMsg.errorText = /failed to fetch|networkerror|load failed/i.test(raw)
            ? '网络连接中断了'
            : raw || '这一轮没有完成';
        }
      } finally {
        // 成功、失败、主动停止都记一次耗时；完成后的折叠摘要和执行轨迹共用这个值
        aiMsg.durationMs = Date.now() - startedAt;
        if (currentAbort === controller) currentAbort = null;
        this.isProcessing = false;
        // 生成结束后刷新审查流列表
        const projectStore = useProjectStore();
        if (projectStore.currentProjectId != null) {
          this.fetchPendingChanges(projectStore.currentProjectId);
        }
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

    async revertChange(id: number): Promise<boolean> {
      try {
        const resp = await fileChangesApi.revertChange(id);
        if (resp.ok) {
          this.pendingChanges = this.pendingChanges.filter((c) => c.id !== id);
          ElMessage.success('已撤销该文件变更');
          return true;
        }
        ElMessage.error(resp.error || '撤销失败');
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
          return true;
        }
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '批量保留失败');
        return false;
      }
    },

    async revertAll(projectId: number): Promise<boolean> {
      try {
        const resp = await fileChangesApi.revertAll(projectId);
        if (resp.ok) {
          this.pendingChanges = [];
          ElMessage.success(`已撤销 ${resp.count} 个文件变更${resp.failed ? `（${resp.failed} 个失败）` : ''}`);
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
