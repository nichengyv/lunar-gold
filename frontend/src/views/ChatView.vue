<template>
  <div class="page active chat-page">
    <div class="chat-layout">
      <!-- 会话头：当前工作区（模型/推理/权限已移到输入框左下） -->
      <div class="chat-header">
        <div class="chat-header-left">
          <span class="chat-context">
            <Icon name="folder" :size="14" />
            <span class="chat-context-name">{{ project.currentProject?.name || '默认工作区' }}</span>
          </span>
        </div>
        <div class="chat-header-right">
          <button
            class="chat-tool-btn"
            :class="{ active: chat.dockOpen && chat.dockTab === 'docs' }"
            title="查看资料"
            @click="toggleDock('docs')"
          >
            <Icon name="paperclip" :size="16" />
            <span v-if="docCount" class="chat-tool-count">{{ docCount }}</span>
          </button>
          <button
            class="chat-tool-btn"
            :class="{ active: chat.dockOpen && chat.dockTab === 'changes' }"
            title="查看文件变更"
            @click="toggleDock('changes')"
          >
            <Icon name="file-text" :size="16" />
            <span v-if="changeCount" class="chat-tool-count">{{ changeCount }}</span>
          </button>
        </div>
      </div>

      <div ref="chatBodyEl" class="chat-body">
        <div class="chat-main">
          <!-- 缩略导航以消息区为定位基准：固定在消息区右侧，不随输入栏变高而移动 -->
          <div class="messages-zone">
          <div ref="messagesEl" class="messages" @scroll="onScroll">
            <div v-if="chat.messages.length === 0" class="message assistant welcome">
              <div class="ai-answer">
                <div class="md-content">
                  <p>你好，我是你的智能助手。</p>
                  <p>我读过你「我的资料」里的内容，可以直接提问；也可以临时上传文件让我分析。</p>
                </div>
                <!-- 空态引导：点一下就把问题填进输入框，用户不需要想怎么说 -->
                <div class="welcome-actions">
                  <button class="suggest-btn" @click="useSuggestion('帮我总结一下资料里的核心结论')">
                    帮我总结资料要点
                  </button>
                  <button class="suggest-btn" @click="useSuggestion('这份资料主要讲了什么？')">
                    这份资料讲了什么
                  </button>
                  <button class="suggest-btn" @click="fileEl?.click()">
                    上传文件让我分析
                  </button>
                  <router-link class="suggest-btn" to="/knowledge">先去添加资料</router-link>
                </div>
              </div>
            </div>
            <!-- data-msg-id 供全文搜索定位：点击侧栏「正文命中」后滚动到具体那一条 -->
            <ChatMessage
              v-for="m in chat.messages"
              :key="m.id"
              :msg="m"
              :data-msg-id="m.id"
              @open-change="openChangeFromMessage"
            />
          </div>

          <!-- 会话缩略导航：右侧每轮一条横线。当前窗口对应那条拉长高亮，悬停其他横线预览该轮问答 -->
          <div v-if="turns.length > 1" class="turn-minimap">
            <button
              v-for="t in visibleTurns"
              :key="t.id"
              class="turn-tick"
              :class="{ active: activeTurnId === t.id, hovered: hoverTurnId === t.id }"
              @mouseenter="hoverTurnId = t.id"
              @mouseleave="hoverTurnId = null"
              @click="jumpToTurn(t.id)"
            ><span class="turn-tick-bar"></span></button>
            <div v-if="hoveredTurn && hoveredIndex >= 0" class="turn-tip" :style="{ '--tip-top': tipTop }">
              <div class="turn-tip-q">{{ questionText(hoveredTurn.question) }}</div>
              <div class="turn-tip-a">{{ answerExcerpt(hoveredTurn.answer) }}</div>
            </div>
          </div>
          </div>

          <!-- 回到底部：离开底部时出现在输入区右上方，点一下平滑拉回最新消息 -->
          <button
            v-if="showScrollBottom"
            class="scroll-bottom-btn"
            title="回到最新消息"
            @click="scrollToBottom"
          >
            <Icon name="arrow-down" :size="16" />
          </button>

          <PendingChangesCard />

          <!-- 第六十八批 A2：危险工具审批卡片，批准/拒绝后 Agent 继续或换方案 -->
          <ApprovalCard v-if="currentApproval" :key="currentApproval.approvalId" :approval="currentApproval" />

          <!-- 计划条：Agent 处理多步任务时显示，可折叠；点「详情」打开工作台的计划面板 -->
          <div v-if="currentPlan && !planHidden" class="plan-strip" :class="{ collapsed: planCollapsed }">
            <div class="plan-strip-head" @click="planCollapsed = !planCollapsed">
              <Icon :name="planCollapsed ? 'chevron-right' : 'chevron-down'" :size="11" class="plan-strip-chev" />
              <span class="plan-strip-label">任务计划</span>
              <span class="plan-strip-current" :title="currentStepTitle">{{ currentStepTitle }}</span>
              <span class="plan-strip-count">{{ planDone }}/{{ planTotal }}</span>
              <span class="plan-strip-track"><span class="plan-strip-bar" :style="{ width: planPercent + '%' }"></span></span>
              <button class="plan-strip-detail" @click.stop="openPlanDock">详情</button>
              <button class="plan-strip-close" title="关闭" @click.stop="planHidden = true">×</button>
            </div>
            <div v-if="!planCollapsed" class="plan-strip-body">
              <div
                v-for="(s, i) in currentPlan.steps"
                :key="i"
                class="plan-strip-step"
                :class="'is-' + s.status"
              >
                <span class="plan-strip-mark">
                  <Icon v-if="s.status === 'completed'" name="check" :size="10" />
                  <Icon v-else-if="s.status === 'aborted'" name="x" :size="10" />
                  <span v-else-if="s.status === 'in_progress'" class="plan-strip-dot"></span>
                  <span v-else class="plan-strip-index">{{ i + 1 }}</span>
                </span>
                <span class="plan-strip-text">{{ s.title }}</span>
              </div>
            </div>
          </div>

          <div class="input-bar">
            <!-- 上排：textarea + 右侧紧凑按钮 -->
            <div class="input-bar-row-top">
              <div class="input-wrapper">
                <div v-if="chat.pendingChips.length" class="attachment-list">
                  <div
                    v-for="chip in chat.pendingChips"
                    :key="chip.key"
                    class="attachment-chip"
                    :class="{ uploading: chip.status === 'uploading', error: chip.status === 'error' }"
                  >
                    <img v-if="chip.previewUrl" :src="chip.previewUrl" class="chip-thumb" :alt="chip.fileName">
                    <span v-else class="chip-file"><Icon name="file" :size="13" /> {{ chip.fileName }}</span>
                    <span v-if="chip.previewUrl" class="chip-name">{{ chip.fileName }}</span>
                    <span class="remove" title="移除" @click="removeChip(chip.key)">×</span>
                  </div>
                </div>
                <!-- @ 引用：文件插入相对路径（Agent 用 read_file 自读），资料插入 @资料:《文件名》并限定本轮检索，会话插入 @会话:《标题》作背景 -->
                <div v-if="mentionOpen" class="pop-palette">
                  <div class="pop-head">
                    <Icon name="search" :size="13" />
                    <span>引用文件、资料或会话</span>
                    <span class="pop-tip">↑↓ 选择 · Enter 插入 · Esc 关闭</span>
                  </div>
                  <button
                    v-for="(m, i) in mentionItems"
                    :key="m.key"
                    class="pop-item"
                    :class="{ active: i === mentionIndex }"
                    @mousedown.prevent="selectMention(m)"
                    @mouseenter="mentionIndex = i"
                  >
                    <span class="pop-icon">
                      <Icon :name="m.kind === 'doc' ? 'paperclip' : m.kind === 'session' ? 'clock' : 'file-text'" :size="14" />
                    </span>
                    <span class="mention-name">{{ m.name }}</span>
                    <span v-if="m.kind === 'doc'" class="pop-tag">资料</span>
                    <span v-if="m.kind === 'session'" class="pop-tag">会话</span>
                    <span class="pop-desc">{{ m.desc }}</span>
                  </button>
                  <div v-if="!mentionItems.length" class="pop-empty">{{ mentionEmptyText }}</div>
                </div>
                <!-- 斜杠命令面板：输入 / 后浮在输入框上方，↑↓ 选择、Enter 执行、Esc 关闭 -->
                <div v-if="paletteVisible" class="pop-palette">
                  <div class="pop-head">
                    <Icon name="terminal" :size="13" />
                    <span>命令</span>
                    <span class="pop-tip">↑↓ 选择 · Enter 执行 · Esc 关闭</span>
                  </div>
                  <button
                    v-for="(c, i) in matchedCommands"
                    :key="c.name"
                    class="pop-item"
                    :class="{ active: i === paletteIndex }"
                    @mousedown.prevent="runCommandAt(i)"
                    @mouseenter="paletteIndex = i"
                  >
                    <span class="pop-icon"><Icon :name="c.icon" :size="14" /></span>
                    <span class="pop-name">/{{ c.name }}</span>
                    <span class="pop-desc">{{ c.hint }}</span>
                  </button>
                  <div v-if="matchedCommands.length === 0" class="pop-empty">没有匹配的命令</div>
                </div>
                <textarea
                  ref="inputEl"
                  v-model="inputText"
                  rows="1"
                  placeholder="输入消息，/ 查看命令，@ 引用文件或资料..."
                  @keydown="onKeydown"
                  @input="autoResize"
                  @click="onInputClick"
                  @blur="closeMention"
                ></textarea>
              </div>
              <input ref="fileEl" type="file" style="display: none" @change="onFileChange">
              <button class="btn-attach btn-mini" title="上传文件" @click="fileEl?.click()">
                <Icon name="paperclip" :size="14" />
              </button>
              <button v-if="chat.isProcessing" class="btn-stop btn-mini" title="停止" @click="chat.stopGenerating()">
                <Icon name="stop" :size="13" />
              </button>
              <button v-else class="btn-send btn-mini" title="发送" @click="onSend">
                <Icon name="send" :size="13" />
              </button>
            </div>

            <!-- 下排：运行参数 pill（真正的左下角） -->
            <div class="input-bar-row-bottom">
              <div class="input-bar-footer">
                <!-- 模型 -->
                <el-dropdown class="pill-dropdown" trigger="click" @command="onModelChange">
                  <span class="chat-pill chat-pill-btn" title="切换对话模型">
                    <Icon name="cpu" :size="11" /> {{ chat.model }}
                  </span>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="deepseek-chat">deepseek-chat · 通用对话</el-dropdown-item>
                      <el-dropdown-item command="deepseek-reasoner">deepseek-reasoner · 深度推理</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <!-- 推理档位 -->
                <el-dropdown class="pill-dropdown" trigger="click" @command="onReasoningChange">
                  <span class="chat-pill chat-pill-btn" title="调整推理档位">
                    <Icon name="sparkles" :size="11" /> {{ reasoningLabel }}
                  </span>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="FAST">快速 · 少轮次</el-dropdown-item>
                      <el-dropdown-item command="STANDARD">标准 · 常规</el-dropdown-item>
                      <el-dropdown-item command="DEEP">深度 · 多步</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <!-- 工作权限 -->
                <el-dropdown class="pill-dropdown" trigger="click" @command="onPermissionChange">
                  <span class="chat-pill chat-pill-btn" title="调整工作权限">
                    <Icon name="shield" :size="11" /> {{ permissionLabel }}
                  </span>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="MANUAL">手动审批 · 写文件/命令都确认</el-dropdown-item>
                      <el-dropdown-item command="AUTO">自动审批 · 仅破坏性命令确认</el-dropdown-item>
                      <el-dropdown-item command="FULL">完全权限 · 全部自动通过</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧工作台：变更 / 资料 / 当前文件共用一块区域，用 tab 切换，避免多层浮层互相压盖 -->
        <div
          v-if="chat.dockOpen && !dockFullscreen"
          class="dock-resizer"
          :class="{ dragging: dockDragging }"
          title="拖动调整工作台宽度"
          @mousedown="startDockDrag"
        ></div>
        <WorkbenchDock
          v-if="chat.dockOpen"
          :width="dockWidth"
          :fullscreen="dockFullscreen"
          @close="closeDockSafely"
          @toggle-fullscreen="dockFullscreen = !dockFullscreen"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useChatStore } from '../stores/chat';
import { useProjectStore } from '../stores/project';
import * as workspaceApi from '../api/workspace';
import * as knowledgeApi from '../api/knowledge';
import ChatMessage from '../components/ChatMessage.vue';
import WorkbenchDock from '../components/WorkbenchDock.vue';
import PendingChangesCard from '../components/PendingChangesCard.vue';
import ApprovalCard from '../components/ApprovalCard.vue';
import Icon from '../components/Icon.vue';
import type { DocumentInfo } from '../types/api';
import type { PermissionLevel, PlanSnapshot, ReasoningLevel } from '../types/chat';

const chat = useChatStore();
const project = useProjectStore();
const inputText = ref('');
const messagesEl = ref<HTMLElement>();
const inputEl = ref<HTMLTextAreaElement>();
const fileEl = ref<HTMLInputElement>();

//会话头三个可切换参数：模型 / 推理档位 / 工作权限（选择对后续消息生效，并随请求下发）
const REASONING_LABELS: Record<ReasoningLevel, string> = { FAST: '快速', STANDARD: '标准', DEEP: '深度' };
const PERMISSION_LABELS: Record<PermissionLevel, string> = {
  MANUAL: '手动审批',
  AUTO: '自动审批',
  FULL: '完全权限',
  READ_ONLY: '只读'
};

const reasoningLabel = computed(() => REASONING_LABELS[chat.reasoning] ?? '标准');
const permissionLabel = computed(() => PERMISSION_LABELS[chat.permission] ?? '手动审批');

function onModelChange(model: string) {
  chat.model = model;
}
function onReasoningChange(reasoning: string) {
  chat.reasoning = reasoning as ReasoningLevel;
}
function onPermissionChange(permission: string) {
  chat.setPermission(permission as PermissionLevel);
}

// 斜杠命令：输入 / 后浮层列出可用命令，↑↓ 切换、Tab 补全、Enter 执行、Esc 关闭
interface SlashCommand {
  name: string;
  icon: string;
  hint: string;
  /** 执行命令；args 为命令名后面的参数 */
  run: (args: string) => void;
}

const MODEL_ALIASES: Record<string, string> = {
  chat: 'deepseek-chat',
  reasoner: 'deepseek-reasoner',
  'deepseek-chat': 'deepseek-chat',
  'deepseek-reasoner': 'deepseek-reasoner'
};

const SLASH_COMMANDS: SlashCommand[] = [
  {
    name: 'clear',
    icon: 'trash',
    hint: '清空对话并开启新会话（历史仍留在左侧）',
    run: () => {
      if (chat.isProcessing) {
        ElMessage.warning('正在生成，先停止再清空');
        return;
      }
      chat.startNewSession();
      ElMessage.success('已开启新会话，之前的对话仍保存在左侧列表');
    }
  },
  {
    name: 'model',
    icon: 'cpu',
    hint: '查看或切换对话模型：/model reasoner',
    run: (args) => {
      const key = args.toLowerCase();
      if (!key) {
        ElMessage.info(`当前模型：${chat.model}；切换用 /model deepseek-chat 或 /model deepseek-reasoner`);
        return;
      }
      const model = MODEL_ALIASES[key];
      if (!model) {
        ElMessage.warning(`未知模型「${args}」，可用 deepseek-chat / deepseek-reasoner`);
        return;
      }
      chat.model = model;
      ElMessage.success(`已切换到 ${model}`);
    }
  },
  {
    name: 'help',
    icon: 'help',
    hint: '列出所有可用命令',
    // 面板本身就是命令清单，把输入框还原成「/」重新展开即可
    run: () => {
      inputText.value = '/';
    }
  }
];

// 只在输入「/ + 命令名」阶段弹面板；出现空格进入写参数阶段后收起，避免挡住输入
const slashToken = computed(() => {
  const text = inputText.value;
  if (!text.startsWith('/')) return null;
  const rest = text.slice(1);
  return /\s/.test(rest) ? null : rest.toLowerCase();
});
const paletteVisible = computed(() => slashToken.value != null);
const matchedCommands = computed(() =>
  slashToken.value == null ? [] : SLASH_COMMANDS.filter((c) => c.name.startsWith(slashToken.value as string))
);
const paletteIndex = ref(0);

// 过滤结果变化后把选中项拉回第一行，避免越界
watch(matchedCommands, () => {
  paletteIndex.value = 0;
});

function resetInput() {
  inputText.value = '';
  closeMention();
  if (inputEl.value) inputEl.value.style.height = 'auto';
}

/** 解析并执行 "/name args"；未知命令只提示、不清空输入，方便用户改字 */
function runSlashCommand(raw: string) {
  const body = raw.trim().slice(1);
  const [name, ...rest] = body.split(/\s+/);
  const args = rest.join(' ').trim();
  const cmd = SLASH_COMMANDS.find((c) => c.name === name.toLowerCase());
  if (!cmd) {
    ElMessage.warning('未知命令，输入 /help 查看可用命令');
    return;
  }
  resetInput();
  cmd.run(args);
  inputEl.value?.focus();
}

function runCommandAt(index: number) {
  const cmd = matchedCommands.value[index];
  if (cmd) runSlashCommand('/' + cmd.name);
}

// @ 引用工作区文件：选中后只是往消息里插入 "@相对路径"，
// 具体内容由 Agent 用 read_file 读取（工作区结构已在 system prompt 里）
const mentionOpen = ref(false);
const mentionStart = ref<number | null>(null);
const mentionQuery = ref('');
// @ 面板候选：工作区文件（插入相对路径）+ 知识库资料（插入 @资料:《文件名》）+ 历史会话（插入 @会话:《标题》）
interface MentionItem {
  key: string;
  kind: 'file' | 'doc' | 'session';
  name: string;
  desc: string;
}
const mentionItems = ref<MentionItem[]>([]);
const mentionIndex = ref(0);
// 资料列表按工作区懒加载并缓存，避免每敲一个字都请求一次
const mentionDocs = ref<DocumentInfo[]>([]);
let mentionDocsProjectId: number | null = null;
const MAX_MENTION_DOCS = 5;
const MAX_MENTION_SESSIONS = 5;
let mentionTimer: number | undefined;
let mentionSeq = 0;

const mentionEmptyText = computed(() => {
  if (!project.currentProject?.workspacePath) return '当前工作区还没绑定文件夹，也没有匹配的资料';
  return mentionQuery.value ? '没有匹配的文件、资料或会话' : '输入文件名、资料名或会话标题开始搜索';
});

/** 拉取当前工作区的知识库资料（失败当作没有资料，不影响文件搜索） */
async function ensureMentionDocs(): Promise<DocumentInfo[]> {
  const pid = project.currentProjectId;
  if (pid == null) return [];
  if (mentionDocsProjectId === pid) return mentionDocs.value;
  try {
    mentionDocs.value = await knowledgeApi.listDocuments(pid);
  } catch {
    mentionDocs.value = [];
  }
  mentionDocsProjectId = pid;
  return mentionDocs.value;
}

/** 从光标往前找最近的 @，拿到引用关键字并触发搜索（中文写作常没有空格，不要求 @ 前有空白） */
function updateMention(el: HTMLTextAreaElement) {
  const text = el.value;
  const caret = el.selectionStart ?? text.length;
  const at = text.slice(0, caret).lastIndexOf('@');
  if (at < 0) {
    closeMention();
    return;
  }
  const query = text.slice(at + 1, caret);
  // 关键字里出现空白说明已经写完路径（或换行），收起面板
  if (/\s/.test(query)) {
    closeMention();
    return;
  }
  mentionStart.value = at;
  mentionQuery.value = query;
  mentionOpen.value = true;
  window.clearTimeout(mentionTimer);
  mentionTimer = window.setTimeout(() => searchMention(query), 250);
}

function closeMention() {
  mentionOpen.value = false;
  mentionStart.value = null;
  mentionQuery.value = '';
  mentionItems.value = [];
  mentionIndex.value = 0;
  window.clearTimeout(mentionTimer);
}

async function searchMention(query: string) {
  if (!project.currentProjectId || !query) {
    mentionItems.value = [];
    return;
  }
  const seq = ++mentionSeq;
  const kw = query.toLowerCase();
  // 资料：本地过滤已缓存的列表，命中数封顶，避免把文件候选挤下去
  const docItems: MentionItem[] = (await ensureMentionDocs())
    .filter((d) => d.status === 'READY' && d.fileName.toLowerCase().includes(kw))
    .slice(0, MAX_MENTION_DOCS)
    .map((d) => ({
      key: `doc-${d.id}`,
      kind: 'doc' as const,
      name: d.fileName,
      desc: '知识库资料 · 本轮只查这份'
    }));

  // 会话：从侧栏已加载的会话列表本地过滤（只列当前工作区、排除正在对话的会话）
  const sessionItems: MentionItem[] = chat.sessions
    .filter((s) => s.id !== chat.currentSessionId)
    .filter((s) => (s.title || '').toLowerCase().includes(kw))
    .slice(0, MAX_MENTION_SESSIONS)
    .map((s) => ({
      key: `session-${s.id}`,
      kind: 'session' as const,
      name: s.title || `会话${s.id}`,
      desc: '历史会话 · 仅作背景参考'
    }));

  let fileItems: MentionItem[] = [];
  if (project.currentProject?.workspacePath) {
    try {
      const list = await workspaceApi.searchWorkspaceFiles(project.currentProjectId, query);
      fileItems = list.map((f) => ({
        key: `file-${f.relativePath}`,
        kind: 'file' as const,
        name: f.name,
        desc: f.relativePath
      }));
    } catch {
      fileItems = [];
    }
  }
  // 只认最后一次请求，避免慢请求把新关键字的结果覆盖掉
  if (seq !== mentionSeq) return;
  mentionItems.value = [...docItems, ...sessionItems, ...fileItems];
  mentionIndex.value = 0;
}

/** 资料插入 @资料:《文件名》（后端据此限定检索），会话插入 @会话:《标题》（后端取最近问答作背景），文件插入 @相对路径 */
function selectMention(item: MentionItem) {
  const el = inputEl.value;
  const current = el?.value ?? inputText.value;
  const caret = el?.selectionStart ?? current.length;
  const start = mentionStart.value ?? caret;
  const insert =
    (item.kind === 'doc'
      ? `@资料:《${item.name}》`
      : item.kind === 'session'
        ? `@会话:《${item.name}》`
        : `@${item.desc}`) + ' ';
  inputText.value = current.slice(0, start) + insert + current.slice(caret);
  closeMention();
  nextTick(() => {
    el?.focus();
    const pos = start + insert.length;
    el?.setSelectionRange(pos, pos);
  });
}

function onInputClick(e: MouseEvent) {
  updateMention(e.target as HTMLTextAreaElement);
}

// 草稿跨会话持久化：每个会话存一份未发送的输入文字（新会话按工作区区分），
// 切会话 / 刷新页面时回填，避免写到一半切走或刷新就丢字。
// 注意放在 mention 相关 ref 之后初始化：immediate 回调会调用 closeMention()。
const draftKey = computed(() =>
  chat.currentSessionId != null ? String(chat.currentSessionId) : `new-${project.currentProjectId ?? 'none'}`
);

function resizeInput() {
  const el = inputEl.value;
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 140) + 'px';
}

function fillInput(text: string) {
  inputText.value = text;
  closeMention();
  nextTick(resizeInput);
}

// 每敲一个字就落盘；空串由 store 视为删除该会话草稿
watch(inputText, (text) => chat.saveDraft(draftKey.value, text));

watch(
  draftKey,
  (key, oldKey) => {
    // 新会话首次发送后拿到后端 sessionId：这仍是同一次输入，把草稿和附件挪到正式键上，不覆盖已输入内容
    if (oldKey && oldKey.startsWith('new-') && chat.currentSessionId != null) {
      chat.clearDraft(oldKey);
      chat.clearDraftChips(oldKey);
      if (inputText.value) chat.saveDraft(key, inputText.value);
      chat.saveDraftChips(key);
      return;
    }
    fillInput(chat.draftFor(key));
    // 回填该会话未发送的附件（已发送的附件仍由 switchSession 的 sessionAttachments 负责）
    chat.restoreDraftChips(key);
  },
  { immediate: true }
);

// 右侧工作台（变更 / 资料 / 当前文件）：展开状态与 tab 放 store，侧栏点文件时也能直接切过去
const DOCK_WIDTH_KEY = 'chat.dockWidth';
// 对话区最小宽度：给会话头/消息里的按钮留位置，避免被工作台挤出去
const MIN_CHAT_WIDTH = 420;
const chatBodyEl = ref<HTMLElement | null>(null);

const dockWidth = ref(Number(localStorage.getItem(DOCK_WIDTH_KEY)) || 520);
const dockFullscreen = ref(false);
const dockDragging = ref(false);

function containerWidth() {
  return chatBodyEl.value?.clientWidth || window.innerWidth;
}

/** 工作台宽度上限：容器 - 对话区最小宽度 - 手柄 */
function maxDockWidth() {
  return Math.max(300, Math.min(window.innerWidth * 0.7, containerWidth() - MIN_CHAT_WIDTH - 5));
}

function clampDockWidth(w: number) {
  return Math.min(maxDockWidth(), Math.max(300, w));
}

// 布局尺寸要等容器挂载后才知道，这里再收敛一次初始值
onMounted(() => {
  dockWidth.value = clampDockWidth(dockWidth.value);
});

/** 拖动手柄：以工作台左缘为准，鼠标向左拖工作台变宽；拖动期间禁掉文本选择避免误选 */
function startDockDrag(e: MouseEvent) {
  e.preventDefault();
  const startX = e.clientX;
  const startW = dockWidth.value;
  dockDragging.value = true;
  document.body.style.userSelect = 'none';
  const onMove = (ev: MouseEvent) => {
    dockWidth.value = clampDockWidth(startW + (startX - ev.clientX));
  };
  const onUp = () => {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    document.body.style.userSelect = '';
    dockDragging.value = false;
    localStorage.setItem(DOCK_WIDTH_KEY, String(dockWidth.value));
  };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
}

/** 窗口变窄时立刻收敛宽度：改到下次拖动才修正会临时挤压对话区 */
function onWindowResize() {
  if (!chat.dockOpen || dockFullscreen.value) return;
  const clamped = clampDockWidth(dockWidth.value);
  if (clamped !== dockWidth.value) {
    dockWidth.value = clamped;
    localStorage.setItem(DOCK_WIDTH_KEY, String(clamped));
  }
}

/** 收起工作台前先确认：编辑器里的未保存内容会随挂载一起消失 */
async function closeDockSafely() {
  if (chat.editorDirty) {
    try {
      await ElMessageBox.confirm('工作台里还有未保存的改动，收起后会丢失。', '未保存的改动', {
        confirmButtonText: '放弃改动并收起',
        cancelButtonText: '继续编辑',
        type: 'warning'
      });
    } catch {
      return;
    }
  }
  chat.closeDock();
}

/** 会话头按钮：同一 tab 再点一次就收起 */
function toggleDock(tab: 'docs' | 'changes') {
  if (chat.dockOpen && chat.dockTab === tab) void closeDockSafely();
  else chat.openDock(tab);
}

/** 消息底部文件清单点击：直接用编辑器打开该文件（本次改动会在文件里标色） */
function openChangeFromMessage(path: string, action?: string) {
  // 已删除的文件在编辑器里打不开，内容可在「待审查改动」中回看并撤回
  if (action === 'delete') {
    ElMessage.info('该文件已删除，可在「待审查改动」中撤回');
    return;
  }
  chat.openFile(path);
}

/** 会话头的计数：资料看附件数，变更看本会话改动过的文件数（按路径去重） */
const docCount = computed(() => {
  const sent = chat.currentSessionId ? chat.sessionAttachments[chat.currentSessionId] || [] : [];
  return sent.length + chat.pendingChips.filter((c) => c.status === 'done').length;
});

const changeCount = computed(() => {
  const paths = new Set<string>();
  chat.messages.forEach((m) => (m.changes || []).forEach((c) => paths.add(c.path)));
  return paths.size;
});

// ——— 计划条：折叠状态只在前端保留 ———
const planCollapsed = ref(false);
const planHidden = ref(false);
const currentPlan = computed<PlanSnapshot | null>(() => {
  // 运行中只认本次任务的实时快照：新任务还没产出计划时不回落到历史，否则会弹上一个任务的卡片
  if (chat.isProcessing) return chat.livePlan?.steps?.length ? chat.livePlan : null;
  // 结束后只认最后一条助手消息：更早的计划属于之前的任务
  const last = [...chat.messages].reverse().find((m) => m.role === 'assistant');
  return last?.plan?.steps?.length ? last.plan : null;
});

// 每次新任务开始（isProcessing 由 false → true）都重新展开计划条，
// 避免上一次手动关闭后一直隐藏，看起来像"任务开始了却没有计划"
watch(
  () => chat.isProcessing,
  (busy) => {
    if (busy) {
      planHidden.value = false;
      planCollapsed.value = false;
    }
  }
);
const planTotal = computed(() => currentPlan.value?.steps.length ?? 0);
const planDone = computed(() => currentPlan.value?.steps.filter((s) => s.status === 'completed').length ?? 0);
const planPercent = computed(() => (planTotal.value ? Math.round((planDone.value / planTotal.value) * 100) : 0));
const currentStepTitle = computed(() => {
  const steps = currentPlan.value?.steps ?? [];
  const running = steps.find((s) => s.status === 'in_progress');
  if (running) return '进行中：' + running.title;
  const pending = steps.find((s) => s.status === 'pending');
  if (pending) return '待开始：' + pending.title;
  return steps.length ? '全部步骤已完成' : '';
});

function openPlanDock() {
  chat.openDock('plan');
}

// 第六十八批 A2：只显示当前会话的**未处理**待审批项——
// 已 resolved 的审批留在数组里给 resolved 态过渡用，但 find() 必须跳过它，
// 否则批准第一张后新的审批会被挡在后面永远渲染不出来（Array.find 返回第一个匹配）
const currentApproval = computed(() => {
  return chat.pendingApprovals.find(
    (a) => a.sessionId === chat.currentSessionId && !a.resolved
  ) ?? null;
});

// 离开底部超过一段距离才显示「回到底部」，避免在底部时白占位置
const SCROLL_BOTTOM_GAP = 80;
const showScrollBottom = ref(false);

// 任何消息内容变化（流式 chunk、工具事件）都滚动到底部
watch(
  () => chat.messages,
  () => {
    nextTick(() => {
      const el = messagesEl.value;
      if (el) el.scrollTop = el.scrollHeight;
      showScrollBottom.value = false;
    });
  },
  { deep: true }
);

function onScroll() {
  const el = messagesEl.value;
  if (!el) return;
  showScrollBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight > SCROLL_BOTTOM_GAP;
  updateActiveTurn();
}

/** 全文搜索命中后定位：等消息渲染完，滚动到那条消息并清空定位目标 */
function scrollToFocusedMessage() {
  const targetId = chat.focusMessageId;
  if (targetId == null) return;
  nextTick(() => {
    const local = chat.messages.find((m) => m.sourceId === targetId);
    if (local) {
      document
        .querySelector(`[data-msg-id="${local.id}"]`)
        ?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
    chat.focusMessageId = null;
  });
}

// 侧栏点「正文命中」后 store 设置目标消息 id，这里负责滚动过去
watch(() => chat.focusMessageId, scrollToFocusedMessage);

// ===== 会话缩略导航（右侧横线） =====
interface TurnEntry {
  /** 该轮用户消息的本地 id，同时用于 DOM 定位 */
  id: number;
  question: string;
  answer: string;
}

/** 每轮 = 一条用户消息 + 紧随其后的一条助手消息；hint 提示不算一轮 */
const turns = computed<TurnEntry[]>(() => {
  const list: TurnEntry[] = [];
  const msgs = chat.messages;
  for (let i = 0; i < msgs.length; i++) {
    const m = msgs[i];
    if (m.role !== 'user') continue;
    let answer = '';
    for (let j = i + 1; j < msgs.length; j++) {
      const next = msgs[j];
      if (next.role === 'hint') continue;
      if (next.role === 'user') break;
      answer = next.content || next.errorText || '';
      break;
    }
    list.push({ id: m.id, question: m.content, answer });
  }
  return list;
});

// 当前窗口对应的那一轮：视窗顶部往下一点的位置，落在哪一轮就高亮哪条横线
const activeTurnId = ref<number | null>(null);
const hoverTurnId = ref<number | null>(null);

/** 条纹数量上限：超过后只显示当前位置附近的一段，滚动时这段窗口跟着移动 */
const MAX_VISIBLE_TICKS = 18;

const visibleStart = computed(() => {
  const n = turns.value.length;
  if (n <= MAX_VISIBLE_TICKS) return 0;
  const idx = turns.value.findIndex((t) => t.id === activeTurnId.value);
  const start = (idx < 0 ? 0 : idx) - Math.floor(MAX_VISIBLE_TICKS / 2);
  return Math.min(Math.max(start, 0), n - MAX_VISIBLE_TICKS);
});

/** 实际渲染的那一段条纹 */
const visibleTurns = computed(() =>
  turns.value.slice(visibleStart.value, visibleStart.value + MAX_VISIBLE_TICKS)
);

const hoveredTurn = computed(() => turns.value.find((t) => t.id === hoverTurnId.value) || null);

/** 悬停卡片对应的条纹在可见段里的位置；已滚出可见段时返回 -1，不显示卡片 */
const hoveredIndex = computed(() => visibleTurns.value.findIndex((t) => t.id === hoverTurnId.value));

/** 悬停卡片的纵向位置：按条纹在可见段里的序号等比映射到导航栏高度 */
const tipTop = computed(() => {
  const n = visibleTurns.value.length;
  const i = hoveredIndex.value;
  if (n <= 1 || i < 0) return '50%';
  return `${(i / (n - 1)) * 100}%`;
});

// 窗口滚动导致可见段变化时，之前悬停的条纹可能已不在列表里，顺手清掉悬停态
watch(visibleStart, () => {
  hoverTurnId.value = null;
});

function questionText(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

function answerExcerpt(answer: string): string {
  const flat = answer.replace(/\s+/g, ' ').trim();
  if (!flat) return '（这一轮还没有回答）';
  return flat.length > 120 ? flat.slice(0, 120) + '…' : flat;
}

/** 计算当前视窗对应哪一轮；消息按顺序排列，遇到第一条高于阈值的即可停止 */
function updateActiveTurn() {
  const el = messagesEl.value;
  const list = turns.value;
  if (!el || list.length === 0) {
    activeTurnId.value = null;
    return;
  }
  // 滚到最底部时直接锁定最后一轮：最后一问如果离视窗顶部不足 90px（回答很短），
  // 按阈值判断会误判成倒数第二轮，表现就是"拉到底了导航还停在倒数第二个"。
  if (el.scrollHeight - el.scrollTop - el.clientHeight <= 4) {
    activeTurnId.value = list[list.length - 1].id;
    return;
  }
  const containerTop = el.getBoundingClientRect().top;
  const threshold = el.scrollTop + 90;
  let current = list[0].id;
  for (const t of list) {
    const node = el.querySelector<HTMLElement>(`[data-msg-id="${t.id}"]`);
    if (!node) continue;
    const top = node.getBoundingClientRect().top - containerTop + el.scrollTop;
    if (top <= threshold) current = t.id;
    else break;
  }
  activeTurnId.value = current;
}

/** 点横线：把那一轮的用户消息滚到视窗偏上位置 */
function jumpToTurn(id: number) {
  const el = messagesEl.value;
  if (!el) return;
  const node = el.querySelector<HTMLElement>(`[data-msg-id="${id}"]`);
  if (!node) return;
  const containerTop = el.getBoundingClientRect().top;
  const top = node.getBoundingClientRect().top - containerTop + el.scrollTop - 16;
  el.scrollTo({ top, behavior: 'smooth' });
}

// 消息条数变化（切会话 / 新发一轮）后重算当前位置；流式 chunk 只改内容不改条数，不重复计算
watch(() => chat.messages.length, () => nextTick(updateActiveTurn));

/** 平滑回到底部；按钮立刻收起来，不用等滚动动画结束 */
function scrollToBottom() {
  const el = messagesEl.value;
  if (!el) return;
  el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  showScrollBottom.value = false;
}

function onKeydown(e: KeyboardEvent) {
  // @ 引用面板打开时优先接管方向键 / Tab / Enter / Esc
  if (mentionOpen.value) {
    const count = mentionItems.value.length;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (count) {
        const step = e.key === 'ArrowDown' ? 1 : -1;
        mentionIndex.value = (mentionIndex.value + step + count) % count;
      }
      return;
    }
    if (e.key === 'Tab') {
      e.preventDefault();
      const file = mentionItems.value[mentionIndex.value];
      if (file) selectMention(file);
      return;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      closeMention();
      return;
    }
    // 有候选时才拦截 Enter 插入文件；没有候选就按普通消息发送
    if (e.key === 'Enter' && !e.shiftKey && count) {
      e.preventDefault();
      selectMention(mentionItems.value[mentionIndex.value]);
      return;
    }
  }
  // 命令面板打开时接管方向键 / Tab / Enter / Esc，避免误发消息
  if (paletteVisible.value) {
    const count = matchedCommands.value.length;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (count) {
        const step = e.key === 'ArrowDown' ? 1 : -1;
        paletteIndex.value = (paletteIndex.value + step + count) % count;
      }
      return;
    }
    if (e.key === 'Tab') {
      e.preventDefault();
      const cmd = matchedCommands.value[paletteIndex.value];
      if (cmd) inputText.value = '/' + cmd.name + ' ';
      return;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      resetInput();
      return;
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      // 没有匹配项时走统一解析，给出「未知命令」提示而不是静默无反应
      if (matchedCommands.value.length) runCommandAt(paletteIndex.value);
      else runSlashCommand(inputText.value);
      return;
    }
  }
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    onSend();
  }
}

// Esc Esc 停止生成：500ms 内连按两次 Esc 才触发，避免误按打断；
// 弹窗打开时（Element Plus 用 Esc 关弹窗）或命令面板已消费该事件时不计数
let lastEscAt = 0;
function onGlobalKeydown(e: KeyboardEvent) {
  // Ctrl/Cmd+K：随时聚焦输入框（弹窗打开时让位给弹窗）
  if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
    if (document.querySelector('.el-overlay')) return;
    e.preventDefault();
    inputEl.value?.focus();
    return;
  }
  if (e.key !== 'Escape') return;
  if (e.defaultPrevented || document.querySelector('.el-overlay')) {
    lastEscAt = 0;
    return;
  }
  const now = Date.now();
  if (now - lastEscAt < 500 && chat.isProcessing) {
    chat.stopGenerating();
    lastEscAt = 0;
  } else {
    lastEscAt = now;
  }
}

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown);
  window.addEventListener('resize', onWindowResize);
  // 首屏回填的草稿可能是多行，等 DOM 就绪后按内容撑高输入框
  if (inputText.value) nextTick(resizeInput);
  // 搜索命中时可能从别的页面跳进对话页，等本页挂载后补一次定位
  scrollToFocusedMessage();
  // 首屏已有历史时，先算出右侧缩略导航的当前位置
  nextTick(updateActiveTurn);
});

onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown);
  window.removeEventListener('resize', onWindowResize);
});

function autoResize(e: Event) {
  const el = e.target as HTMLTextAreaElement;
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 140) + 'px';
  updateMention(el);
}

async function onSend() {
  const raw = inputText.value;
  // 斜杠命令不发给模型，本地执行；生成中也允许切换模型 / 查看帮助
  if (raw.trim().startsWith('/')) {
    runSlashCommand(raw);
    return;
  }
  // 生成中再按发送：给出明确提示，而不是静默吞掉（草稿仍保留在输入框里）
  if (chat.isProcessing) {
    ElMessage.info('正在生成回答，请稍候；连按两下 Esc 可停止');
    return;
  }
  const text = raw;
  // 先把当前草稿键记下来：发送成功后该键的草稿与未发送附件都要清掉
  const key = draftKey.value;
  inputText.value = '';
  closeMention();
  if (inputEl.value) inputEl.value.style.height = 'auto';
  await chat.sendMessage(text);
  chat.clearDraftChips(key);
  inputEl.value?.focus();
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  // 上传成功后才落盘：附件 id 由后端生成，刷新后凭 id 继续发送；
  // 用上传发起时的键，避免上传途中切会话导致存到别人的草稿里
  const key = draftKey.value;
  void chat.uploadAttachment(file).then(() => chat.saveDraftChips(key));
}

/** 移除附件 chip 时同步更新持久化记录，避免刷新后"已移除的附件"又回来 */
function removeChip(key: string) {
  chat.removeChip(key);
  chat.saveDraftChips(draftKey.value);
}

/** 空态建议：填入输入框并聚焦，让用户可以直接改字或回车发送 */
function useSuggestion(text: string) {
  inputText.value = text;
  inputEl.value?.focus();
}


</script>

<style scoped>
.chat-page {
    padding: 0;
}

.chat-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-4);
    border-bottom: 1px solid var(--border);
    background: var(--surface);
    flex-shrink: 0;
}

.chat-header-left {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
    overflow: hidden;
}

.chat-context {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    max-width: 220px;
    padding: 4px 10px;
    border-radius: var(--radius-pill);
    background: var(--surface-hover);
    color: var(--text);
    font-size: var(--fs-sm);
    font-weight: 500;
}

.chat-context-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.chat-pill {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 4px 9px;
    border: 1px solid var(--border);
    border-radius: var(--radius-pill);
    color: var(--text-secondary);
    font-size: var(--fs-xs);
    white-space: nowrap;
    cursor: default;
}

/* 可切换参数：整颗胶囊就是下拉触发器 */
.pill-dropdown {
    display: inline-flex;
    align-items: center;
}

.pill-dropdown :deep(.el-dropdown) {
    display: inline-flex;
}

.chat-pill-btn {
    cursor: pointer;
    transition: all var(--dur-fast);
}
.chat-pill-btn:hover {
    color: var(--text);
    border-color: var(--primary);
}

.pill-chevron {
    opacity: 0.6;
}

.chat-header-right {
    display: flex;
    align-items: center;
    gap: 3px;
    flex-shrink: 0;
}

.chat-tool-btn {
    position: relative;
    width: 30px;
    height: 30px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    background: none;
    color: var(--text-secondary);
    cursor: pointer;
    transition: all var(--dur-fast);
}
.chat-tool-btn:hover { background: var(--surface-hover); color: var(--text); }
.chat-tool-btn.active {
    color: var(--primary);
    border-color: var(--primary);
    background: transparent;
}

.chat-tool-count {
    position: absolute;
    top: -3px;
    right: -3px;
    min-width: 15px;
    padding: 0 3px;
    border-radius: var(--radius-pill);
    background: var(--primary);
    color: #fff;
    font-size: 10px;
    line-height: 15px;
    text-align: center;
}

.chat-body {
    flex: 1;
    display: flex;
    min-height: 0;
    position: relative;
}

.chat-main {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
    /* 「回到底部」按钮以对话区为定位基准，浮在输入栏上方 */
    position: relative;
}

/* 消息区 + 右侧缩略导航：导航以本区域为定位基准，输入栏变高也不会压到它 */
.messages-zone {
    flex: 1;
    display: flex;
    min-height: 0;
    position: relative;
}

/* 回到底部：圆形按钮，压在输入栏上方的右下角 */
.scroll-bottom-btn {
    position: absolute;
    right: 24px;
    bottom: 92px;
    z-index: 5;
    width: 36px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--border);
    border-radius: 50%;
    background: var(--overlay-bg, var(--surface));
    backdrop-filter: var(--overlay-blur, none);
    color: var(--text);
    box-shadow: var(--shadow-overlay, 0 4px 12px rgba(0, 0, 0, 0.12));
    cursor: pointer;
    transition: transform 0.15s ease, background 0.15s ease;
}

.scroll-bottom-btn:hover {
    background: var(--surface-hover, var(--surface));
    transform: translateY(-2px);
}

.scroll-bottom-btn:focus-visible {
    outline: 2px solid var(--primary);
    outline-offset: 2px;
}

/* ===== 会话缩略导航：右侧竖排横线，一轮对话一条 ===== */
/* 目录高度随条纹数量自适应、整体垂直居中；间距固定，不随对话数量被拉开
   （最多渲染 18 条，多出的靠滚动换段） */
.turn-minimap {
    position: absolute;
    top: 50%;
    /* 让开消息区右侧的滚动条，避免刻度轨压住滚动条 */
    right: 16px;
    transform: translateY(-50%);
    z-index: 4;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 0;
}

/* 点击/悬停判定区：宽 18px（与高亮条纹同宽）、高 11px，彼此紧挨不留缝，好瞄准；
   真正的条纹画在内部 .turn-tick-bar 上，视觉上仍是 3px 细线 + 8px 间距 */
.turn-tick {
    flex: 0 0 11px;
    width: 18px;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    padding: 0;
    border: none;
    background: none;
    cursor: pointer;
}

/* 默认用正文色（白天模式接近黑色）压暗显示，短且细；当前窗口那条最长最亮，悬停那条提亮拉长 */
.turn-tick-bar {
    height: 3px;
    width: 6px;
    border-radius: 1px;
    background: var(--text);
    opacity: 0.55;
    transition: width 0.15s ease, opacity 0.15s ease, background 0.15s ease;
}

.turn-tick.hovered .turn-tick-bar {
    width: 13px;
    opacity: 1;
}

.turn-tick.active .turn-tick-bar {
    width: 18px;
    background: var(--primary);
    opacity: 1;
}

/* 悬停卡片：问题加粗在上，下面截取开头一段回答；不拦截鼠标，避免来回闪烁 */
.turn-tip {
    position: absolute;
    right: 36px;
    top: clamp(18px, var(--tip-top), calc(100% - 18px));
    transform: translateY(-50%);
    width: 280px;
    padding: 10px 12px;
    border: 1px solid var(--border);
    border-radius: 10px;
    background: var(--surface);
    box-shadow: var(--shadow-overlay, 0 6px 20px rgba(0, 0, 0, 0.16));
    pointer-events: none;
}

.turn-tip-q {
    display: -webkit-box;
    overflow: hidden;
    color: var(--text);
    font-size: 13px;
    font-weight: 700;
    line-height: 1.45;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
}

.turn-tip-a {
    display: -webkit-box;
    margin-top: 4px;
    overflow: hidden;
    color: var(--text-secondary);
    font-size: 12px;
    line-height: 1.5;
    -webkit-line-clamp: 3;
    -webkit-box-orient: vertical;
}

.chip-file {
    display: inline-flex;
    align-items: center;
    gap: 4px;
}

/* 命令 / @ 引用弹出面板：浮在输入框上方（含附件区），不挤动消息列表 */
.input-wrapper {
    position: relative;
}

.pop-palette {
    position: absolute;
    left: 0;
    right: 0;
    bottom: calc(100% + 8px);
    max-height: 268px;
    overflow-y: auto;
    padding: 4px;
    border: 1px solid color-mix(in srgb, var(--border) 72%, transparent);
    border-radius: var(--radius-lg);
    /* 毛玻璃浮层：半透明 + 背景模糊，与 Element Plus 下拉/弹窗同一套令牌 */
    background: var(--overlay-bg);
    backdrop-filter: var(--overlay-blur);
    -webkit-backdrop-filter: var(--overlay-blur);
    box-shadow: var(--shadow-overlay);
    z-index: 30;
}

.pop-head {
    display: flex;
    align-items: center;
    gap: 5px;
    padding: 4px 8px 6px;
    color: var(--text-secondary);
    font-size: var(--fs-xs);
}

.pop-tip {
    margin-left: auto;
    opacity: 0.6;
}

.pop-item {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    width: 100%;
    padding: 7px 8px;
    border: none;
    border-radius: var(--radius-sm);
    background: none;
    color: var(--text);
    font-family: inherit;
    font-size: var(--fs-base);
    text-align: left;
    cursor: pointer;
    transition: background var(--dur-fast);
}

.pop-item.active {
    background: var(--surface-hover);
}

.pop-icon {
    display: inline-flex;
    color: var(--text-secondary);
}

.pop-item.active .pop-icon {
    color: var(--primary);
}

.pop-name {
    flex-shrink: 0;
    min-width: 62px;
    color: var(--primary);
    font-weight: 600;
}

/* 文件名优先展示，长路径从尾部省略；pop-desc 与 pop-name 共用同一行宽度 */
.mention-name {
    flex-shrink: 0;
    max-width: 45%;
    overflow: hidden;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
}

/* 资料条目的小标签，和文件条目区分开 */
.pop-tag {
    flex-shrink: 0;
    padding: 0 6px;
    border-radius: 6px;
    background: color-mix(in srgb, var(--primary) 14%, transparent);
    color: var(--primary);
    font-size: 11px;
    line-height: 18px;
}

.pop-desc {
    overflow: hidden;
    color: var(--text-secondary);
    font-size: var(--fs-sm);
    text-overflow: ellipsis;
    white-space: nowrap;
}

.pop-empty {
    padding: 10px 8px;
    color: var(--text-secondary);
    font-size: var(--fs-sm);
}

.btn-attach {
    display: inline-flex;
    align-items: center;
    justify-content: center;
}

.btn-send,
.btn-stop {
    display: inline-flex;
    align-items: center;
    gap: 6px;
}

/* 右侧工作台拖动手柄：5px 热区，悬停/拖动时给主色提示 */
.dock-resizer {
    width: 5px;
    flex-shrink: 0;
    cursor: col-resize;
    background: transparent;
    transition: background var(--dur-fast);
}
.dock-resizer:hover,
.dock-resizer.dragging {
    background: var(--primary);
    opacity: 0.35;
}

/* 窄屏时工作台铺满对话区，没有拖动的空间 */
@media (max-width: 900px) {
    .dock-resizer { display: none; }
}
</style>
