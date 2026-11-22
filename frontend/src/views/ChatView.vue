<template>
  <div class="page active chat-page">
    <div class="chat-layout">
      <div class="chat-body">
        <div class="chat-main">
          <!-- 缩略导航以消息区为定位基准：固定在消息区右侧，不随输入栏变高而移动 -->
          <div class="messages-zone">
          <div ref="messagesEl" class="messages" :class="{ 'is-virtual': virtualEnabled }" @scroll="onScroll">
            <!-- P5-09：会话切换时先出骨架屏，避免旧会话内容直接跳到新内容 -->
            <SkeletonMessage v-if="chat.messagesLoading" :rows="3" />
            <!-- P5-09：历史加载失败时给出可读原因与重试，而不是空白消息区 -->
            <ErrorState
              v-else-if="chat.loadError"
              title="会话加载失败"
              :message="chat.loadError"
              code="SESSION_LOAD_FAILED"
              exportable
              @retry="chat.reloadCurrentSession()"
              @export-diagnostics="exportDiagnostics"
            />
            <!-- P5-12-1：空态只留金月标识与一句欢迎，不再堆任务列表与快捷键提示 -->
            <div v-else-if="chat.messages.length === 0" class="chat-empty">
              <span class="chat-empty-mark" aria-hidden="true"><Icon name="logo" :size="46" /></span>
              <p class="chat-empty-greeting">您好，想和金月聊些什么</p>
            </div>
            <!-- data-msg-id 供全文搜索定位：点击侧栏「正文命中」后滚动到具体那一条 -->
            <template v-else>
              <!-- P5-11-3：超过 200 条只渲染可视窗口，上下用占位撑起真实滚动高度；
                   占位与相邻消息之间仍有一份 flex gap，所以高度里扣掉一个 gap -->
              <div
                v-if="virtualEnabled && virtualRange.topPad > 0"
                class="virtual-pad"
                aria-hidden="true"
                :style="{ height: Math.max(0, virtualRange.topPad - MESSAGE_GAP) + 'px' }"
              ></div>
              <ChatMessage
                v-for="m in visibleMessages"
                :key="m.id"
                :ref="(el) => registerMessageEl(m.id, el)"
                :msg="m"
                :data-msg-id="m.id"
                @open-change="openChangeFromMessage"
              />
              <div
                v-if="virtualEnabled && virtualRange.bottomPad > 0"
                class="virtual-pad"
                aria-hidden="true"
                :style="{ height: Math.max(0, virtualRange.bottomPad - MESSAGE_GAP) + 'px' }"
              ></div>
            </template>
          </div>

          <!-- 会话缩略导航：右侧每轮一条横线。当前窗口对应那条拉长高亮，悬停其他横线预览该轮问答 -->
          <div v-if="turns.length > 1" class="turn-minimap">
            <button
              v-for="(t, index) in visibleTurns"
              :key="t.id"
              class="turn-tick"
              :class="{ active: activeTurnId === t.id, hovered: hoverTurnId === t.id }"
              :aria-label="`跳到第 ${index + 1} 轮问答`"
              :aria-current="activeTurnId === t.id ? 'true' : undefined"
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
            aria-label="回到最新消息"
            @click="scrollToBottom"
          >
            <Icon name="arrow-down" :size="16" />
          </button>

          <!-- 待审查改动：仅桌面端有文件写入，网页端不展示审查流 -->
          <PendingChangesCard v-if="hasLocalWorkspace" />

          <!-- 第六十八批 A2 / P5-12-4：危险工具审批卡片常驻输入区上方；多项时按队列展示并给计数 -->
          <div
            v-if="hasApproval && activeApprovals.length"
            ref="approvalZoneEl"
            class="approval-queue"
            role="region"
            aria-label="待审批队列"
            :class="{ 'is-flashing': approvalFlash }"
          >
            <div v-if="activeApprovals.length > 1" class="approval-queue-head">
              <Icon name="shield" :size="12" />
              <span>待审批 {{ activeApprovals.length }} 项</span>
              <button
                v-if="batchableApprovals.length >= 2"
                class="approval-batch-btn"
                title="只批量允许写/编辑文件，命令与删除仍需逐条确认"
                @click="approveLowRiskBatch"
              >
                允许全部低风险（{{ batchableApprovals.length }}）
              </button>
              <span class="approval-queue-hint">J/K 切换 · A 允许 / D 拒绝</span>
            </div>
            <div class="approval-queue-body">
              <ApprovalCard
                v-for="a in activeApprovals"
                :key="a.approvalId"
                :approval="a"
                :data-approval-id="a.approvalId"
                :shortcut-active="a.approvalId === selectedApprovalId"
                :selected="a.approvalId === selectedApprovalId"
                @select="selectApproval(a.approvalId)"
              />
            </div>
          </div>

          <!-- P7-06：agent 执行中决策询问卡片 -->
          <AskUserCard v-if="chat.pendingUserQuestion" :question="chat.pendingUserQuestion" />

          <!-- P5-10-3：长任务执行进度：五阶段时间线 + 轮次/token/时间预算 -->
          <div v-if="showTaskProgress" class="task-progress" :class="{ 'is-warning': budgetWarning }">
            <div class="tp-head">
              <span class="tp-label">执行进度</span>
              <span class="tp-current">
                第 {{ currentStageIndex }} 步 · {{ currentStage?.label || '理解' }}
                <span class="tp-current-time">{{ currentStage ? stageTimeText(currentStage) : '00:00' }}</span>
              </span>
              <span class="tp-budget">{{ budgetText }}</span>
            </div>
            <div class="tp-stages">
              <span
                v-for="s in stageTimeline"
                :key="s.key"
                class="tp-stage"
                :class="'is-' + s.status"
              >
                <span class="tp-stage-dot"></span>
                <span class="tp-stage-label">{{ s.label }}</span>
                <span class="tp-stage-time">{{ stageTimeText(s) }}</span>
              </span>
            </div>
            <div v-if="budgetWarningText" class="tp-warning">
              <Icon name="alert" :size="12" /> {{ budgetWarningText }}
            </div>
          </div>

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

          <!-- R7c：串行子智能体状态条；父任务只展示轻量状态，明细仍在子 Run 事件里 -->
          <div v-if="currentSubtasks.length" class="plan-strip" :style="{ marginTop: '6px' }">
            <div class="plan-strip-head" style="cursor: default">
              <span class="plan-strip-label">子任务</span>
              <span class="plan-strip-count">
                {{ currentSubtasks.filter((s) => s.status === 'SUCCEEDED').length }}/{{ currentSubtasks.length }}
              </span>
            </div>
            <div class="plan-strip-body">
              <div
                v-for="task in currentSubtasks"
                :key="task.subtaskId"
                class="plan-strip-step"
                :class="'is-' + (task.status === 'SUCCEEDED' ? 'completed'
                  : task.status === 'RUNNING' ? 'in_progress' : 'aborted')"
              >
                <span class="plan-strip-mark">
                  <Icon v-if="task.status === 'SUCCEEDED'" name="check" :size="10" />
                  <Icon v-else-if="task.status === 'FAILED' || task.status === 'CANCELLED'
                    || task.status === 'SKIPPED'" name="x" :size="10" />
                  <span v-else class="plan-strip-dot"></span>
                </span>
                <span class="plan-strip-text">
                  {{ task.subtaskId }} · {{ subtaskLabel[task.status ?? 'RUNNING'] ?? task.status }}
                </span>
              </div>
            </div>
          </div>

          <!-- P5-10：断线续传状态条：只在重连 / 已恢复 / 中断时需要提示 -->
          <div v-if="streamNotice" class="stream-status" :class="'is-' + chat.streamStatus">
            <span class="stream-status-dot"></span>
            <span class="stream-status-text">{{ streamNotice }}</span>
            <button
              v-if="chat.streamStatus === 'interrupted'"
              class="stream-status-btn"
              @click="chat.reloadCurrentSession()"
            >
              重新加载
            </button>
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
              <button class="btn-attach btn-mini" title="上传文件" aria-label="上传文件" @click="fileEl?.click()">
                <Icon name="paperclip" :size="14" />
              </button>
              <button v-if="chat.isProcessing" class="btn-stop btn-mini" title="停止" aria-label="停止生成" @click="chat.stopGenerating()">
                <Icon name="stop" :size="13" />
              </button>
              <button v-else class="btn-send btn-mini" title="发送" aria-label="发送消息" @click="onSend">
                <Icon name="send" :size="13" />
              </button>
            </div>

            <!-- 下排：模型 / 推理档位 / 工作权限，放在输入框下方（与 Trae、DeepSeek 一致） -->
            <div class="input-bar-row-bottom">
              <div class="input-bar-footer">
                <!-- 模型 -->
                <el-dropdown class="pill-dropdown" trigger="click" @command="onModelChange">
                  <span class="chat-pill chat-pill-btn" :title="`切换对话模型：${chat.model}`">
                    <Icon name="cpu" :size="11" /> {{ chat.model }}
                  </span>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-for="m in modelOptions" :key="m.id" :command="m.id">
                        {{ m.id }}<template v-if="m.name && m.name !== m.id"> · {{ m.name }}</template>
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <!-- 推理档位 -->
                <el-dropdown class="pill-dropdown" trigger="click" @command="onReasoningChange">
                  <span class="chat-pill chat-pill-btn" :title="`调整推理档位：${reasoningLabel}`">
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
                  <span class="chat-pill chat-pill-btn" :title="`调整工作权限：${permissionLabel}`">
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

      </div>
    </div>

    <!-- P5-10：流式内容播报用，视觉隐藏；读屏用户能听到「正在生成 / 已完成 / 已恢复」 -->
    <div class="sr-only" aria-live="polite">{{ liveAnnounce }}</div>

    <!-- P5-10：快捷键面板，Ctrl/Cmd + / 打开；P5-10-4 焦点限制在面板内，关闭后回到原处 -->
    <div v-if="shortcutsOpen" class="shortcuts-mask" @click.self="closeShortcuts">
      <div
        ref="shortcutsPanelEl"
        class="shortcuts-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="shortcuts-title"
      >
        <div class="shortcuts-head">
          <span id="shortcuts-title">键盘快捷键</span>
          <button
            ref="shortcutsCloseEl"
            class="shortcuts-close"
            title="关闭"
            aria-label="关闭快捷键面板"
            @click="closeShortcuts"
          >×</button>
        </div>
        <div v-for="item in shortcutList" :key="item.label" class="shortcuts-row">
          <span class="shortcuts-label">{{ item.label }}</span>
          <span class="shortcuts-keys">
            <kbd v-for="k in item.keys" :key="k">{{ k }}</kbd>
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch, type ComponentPublicInstance } from 'vue';
import { ElMessage } from 'element-plus';
import { buildStageTimeline, isBatchApprovable, TASK_STAGES, useChatStore } from '../stores/chat';
import { useMetaStore } from '../stores/meta';
import { useProjectStore } from '../stores/project';
import * as workspaceApi from '../api/workspace';
import * as knowledgeApi from '../api/knowledge';
import * as settingsApi from '../api/settings';
import type { ModelInfo } from '../api/settings';
import { createFrameThrottle } from '../utils/frameThrottle';
import { trapTabKey } from '../utils/focusTrap';
import { isReducedMotion } from '../utils/motion';
import { createExperienceDiagnosticsExport } from '../utils/experienceDiagnostics';
import { useVirtualMessages } from '../composables/useVirtualMessages';
import ChatMessage from '../components/ChatMessage.vue';
import SkeletonMessage from '../components/state/SkeletonMessage.vue';
import ErrorState from '../components/state/ErrorState.vue';
import PendingChangesCard from '../components/PendingChangesCard.vue';
import ApprovalCard from '../components/ApprovalCard.vue';
import AskUserCard from '../components/AskUserCard.vue';
import Icon from '../components/Icon.vue';
import type { DocumentInfo } from '../types/api';
import type { PermissionLevel, PlanSnapshot, ReasoningLevel, SubtaskUpdate, TaskStageItem } from '../types/chat';

/** P5-09：历史加载失败时也能直接带走诊断，不用先去设置页 */
function exportDiagnostics() {
  const content = JSON.stringify(createExperienceDiagnosticsExport(null), null, 2);
  const url = URL.createObjectURL(new Blob([content], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `experience-diagnostics-${new Date().toISOString().slice(0, 10)}.json`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  ElMessage.success('诊断 JSON 已导出');
}

const chat = useChatStore();
const meta = useMetaStore();
const project = useProjectStore();
/** 文件变更、审查流与工作台里的本地文件只在桌面端可用 */
const hasLocalWorkspace = computed(() => meta.has('LOCAL_WORKSPACE'));
const hasApproval = computed(() => meta.has('APPROVAL'));
const inputText = ref('');
const messagesEl = ref<HTMLElement>();
const inputEl = ref<HTMLTextAreaElement>();
const fileEl = ref<HTMLInputElement>();

// ——— P5-11-3：长会话窗口化渲染 ———
/** 超过这个条数才启用窗口化；更短的会话保持全量渲染，行为与改造前一致 */
const VIRTUAL_MESSAGE_THRESHOLD = 200;
/** 与 `.messages` 的 flex gap 保持一致，占位高度需要扣掉一份 */
const MESSAGE_GAP = 18;
const virtualEnabled = computed(() => chat.messages.length > VIRTUAL_MESSAGE_THRESHOLD);
const {
  visibleItems: visibleMessages,
  range: virtualRange,
  layoutVersion: virtualLayoutVersion,
  registerItem: registerVirtualItem,
  onScroll: virtualOnScroll,
  scrollToIndex: virtualScrollToIndex,
  offsetOfIndex: virtualOffsetOfIndex,
  syncViewport: syncVirtualViewport,
  reset: resetVirtual,
  dispose: disposeVirtual
} = useVirtualMessages({
  scroller: messagesEl,
  items: () => chat.messages,
  enabled: () => virtualEnabled.value
});

/** 模板 ref 回调：ChatMessage 是组件，取根元素实测高度 */
function registerMessageEl(id: number, el: Element | ComponentPublicInstance | null) {
  const node = el && '$el' in (el as object) ? (el as ComponentPublicInstance).$el : el;
  registerVirtualItem(id, (node as Element | null) ?? null);
}

// ——— P5-12-1：空态 ———
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

// 模型目录：与设置页共用 /api/settings/models（有 Key 时是账号实际可用模型），后端未就绪时回退静态两项
const FALLBACK_MODELS: ModelInfo[] = [
  { id: 'deepseek-chat', name: '通用对话', provider: 'DEEPSEEK', available: true },
  { id: 'deepseek-reasoner', name: '深度推理', provider: 'DEEPSEEK', available: true }
];
const modelOptions = ref<ModelInfo[]>(FALLBACK_MODELS);

async function loadModelOptions() {
  try {
    const list = await settingsApi.listModels();
    const usable = list.filter((m) => m.available);
    if (usable.length > 0) modelOptions.value = usable;
  } catch {
    // 后端未就绪：保留静态兜底，不阻塞聊天页
  }
}

const MODEL_ALIASES: Record<string, string> = {
  chat: 'deepseek-chat',
  reasoner: 'deepseek-reasoner'
};

const PERMISSION_ALIASES: Record<string, PermissionLevel> = {
  manual: 'MANUAL',
  auto: 'AUTO',
  full: 'FULL',
  'readonly': 'READ_ONLY',
  'read-only': 'READ_ONLY'
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
      const availableIds = modelOptions.value.map((m) => m.id).join(' / ');
      if (!key) {
        ElMessage.info(`当前模型：${chat.model}；可切换：${availableIds}`);
        return;
      }
      const aliased = MODEL_ALIASES[key] ?? key;
      const model = modelOptions.value.find((m) => m.id.toLowerCase() === aliased)?.id;
      if (!model) {
        ElMessage.warning(`未知模型「${args}」，可用 ${availableIds}`);
        return;
      }
      chat.model = model;
      ElMessage.success(`已切换到 ${model}`);
    }
  },
  {
    name: 'permission',
    icon: 'shield',
    hint: '查看或调整工作权限：/permission auto',
    run: (args) => {
      const key = args.toLowerCase();
      if (!key) {
        ElMessage.info(`当前权限：${permissionLabel.value}；切换用 /permission manual|auto|full`);
        return;
      }
      const permission = PERMISSION_ALIASES[key];
      if (!permission) {
        ElMessage.warning(`未知权限「${args}」，可用 manual / auto / full`);
        return;
      }
      chat.setPermission(permission);
      ElMessage.success(`已切换到 ${PERMISSION_LABELS[permission]}`);
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

onMounted(() => {
  // P5-11-3：首屏布局完成后同步一次视口高度，窗口化渲染才有正确的可视范围
  nextTick(() => {
    syncVirtualViewport();
    updateActiveTurn();
  });
  loadModelOptions();
});

onUnmounted(disposeVirtual);

/** 消息底部文件清单点击：直接用编辑器打开该文件（本次改动会在文件里标色） */
function openChangeFromMessage(path: string, action?: string) {
  // 已删除的文件在编辑器里打不开，内容可在「待审查改动」中回看并撤回
  if (action === 'delete') {
    ElMessage.info('该文件已删除，可在「待审查改动」中撤回');
    return;
  }
  chat.openFile(path);
}

// ——— P5-10：断线续传状态条 ———
let streamNoticeTimer: number | undefined;
const streamNotice = computed(() => {
  switch (chat.streamStatus) {
    case 'reconnecting':
      return '连接中断，正在从未完成的位置恢复…';
    case 'recovered':
      return chat.recoveredTruncated
        ? '已恢复：超长回答只保留了前 2 万字'
        : '已恢复：内容已从断点续上';
    case 'interrupted':
      return '连接中断，已保留已生成的内容';
    default:
      return '';
  }
});
// 「已恢复」是正向提示，几秒后自动收起；中断提示保留，等用户处理
watch(
  () => chat.streamStatus,
  (status) => {
    if (streamNoticeTimer) {
      window.clearTimeout(streamNoticeTimer);
      streamNoticeTimer = undefined;
    }
    if (status === 'recovered') {
      streamNoticeTimer = window.setTimeout(() => {
        chat.clearStreamNotice();
        streamNoticeTimer = undefined;
      }, 4000);
    }
  }
);

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

// R7c：串行子智能体的轻量状态条；子任务全部结束或没有委派时不展示
const currentSubtasks = computed<SubtaskUpdate[]>(() => {
  const last = [...chat.messages].reverse().find((m) => m.role === 'assistant');
  return last?.subtasks ?? [];
});

const subtaskLabel: Record<string, string> = {
  RUNNING: '执行中',
  SUCCEEDED: '已通过',
  FAILED: '未通过',
  CANCELLED: '已取消',
  SKIPPED: '已跳过',
  WAITING_APPROVAL: '待审批',
  WAITING_USER_INPUT: '待回答'
};

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
const planPercent = computed(() => (planTotal.value ? Math.round((planDone.value / planTotal.value) * 100) : 0));const currentStepTitle = computed(() => {
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

// ——— P5-10-3：阶段时间线与预算 ———
const nowTick = ref(Date.now());
let progressTimer: number | undefined;

watch(
  () => chat.isProcessing,
  (busy) => {
    if (progressTimer != null) {
      window.clearInterval(progressTimer);
      progressTimer = undefined;
    }
    if (busy) {
      nowTick.value = Date.now();
      progressTimer = window.setInterval(() => {
        nowTick.value = Date.now();
      }, 1000);
    }
  },
  { immediate: true }
);

onUnmounted(() => {
  if (progressTimer != null) window.clearInterval(progressTimer);
});

const stageTimeline = computed(() => buildStageTimeline(chat.liveStageMarks, nowTick.value));
const currentStage = computed(() => {
  const active = stageTimeline.value.find((s) => s.status === 'active');
  if (active) return active;
  return [...stageTimeline.value].reverse().find((s) => s.status === 'done');
});
const currentStageIndex = computed(() => {
  if (!currentStage.value) return 1;
  const idx = TASK_STAGES.findIndex((s) => s.key === currentStage.value?.key);
  return idx < 0 ? 1 : idx + 1;
});
/** 短问答不显示进度条，避免一闪而过；进入读取及以后立即显示 */
const showTaskProgress = computed(() => {
  if (!chat.isProcessing) return false;
  if (nowTick.value - chat.runStartedAt >= 3000) return true;
  return !!(chat.liveStageMarks.read || chat.liveStageMarks.modify || chat.liveStageMarks.verify);
});

const budget = computed(() => chat.liveBudget);
const budgetElapsedMs = computed(() =>
  Math.max(budget.value?.elapsedMs ?? 0, chat.isProcessing ? nowTick.value - chat.runStartedAt : 0)
);
const budgetText = computed(() => {
  const b = budget.value;
  const round = b?.round != null && b?.maxRounds != null ? `第 ${b.round}/${b.maxRounds} 轮` : '轮次 —';
  const tokens = b
    ? b.maxTokens
      ? `${formatTokenCount(b.totalTokens)}/${formatTokenCount(b.maxTokens)} token`
      : `${formatTokenCount(b.totalTokens)} token（不限）`
    : 'token —';
  const elapsed = b?.maxElapsedMs
    ? `${formatDuration(budgetElapsedMs.value)}/${formatDuration(b.maxElapsedMs)}`
    : `${formatDuration(budgetElapsedMs.value)}${b ? '（不限）' : ''}`;
  return `${round} · ${tokens} · ${elapsed}`;
});
const budgetWarningText = computed(() => {
  const b = budget.value;
  if (!b) return '';
  const parts: string[] = [];
  if (b.round != null && b.maxRounds && b.round / b.maxRounds >= 0.8) parts.push('轮次接近上限');
  if (b.maxTokens && b.totalTokens / b.maxTokens >= 0.8) parts.push('token 接近上限');
  if (b.maxElapsedMs && budgetElapsedMs.value / b.maxElapsedMs >= 0.8) parts.push('时间接近上限');
  return parts.length ? `${parts.join(' · ')}，Agent 会优先收口` : '';
});
const budgetWarning = computed(() => !!budgetWarningText.value);

function formatDuration(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

function formatTokenCount(n: number): string {
  if (n >= 10000) return `${Math.round(n / 1000)}k`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

function stageTimeText(stage: TaskStageItem): string {
  if (stage.status === 'skipped') return '跳过';
  if (stage.status === 'pending') return '待开始';
  return formatDuration(stage.durationMs ?? 0);
}

// 第六十八批 A2 / P5-12-4：当前会话所有**未处理**待审批项按队列渲染——
// 已 resolved 的审批留在数组里给 resolved 态过渡用，队列必须过滤掉。
const activeApprovals = computed(() => {
  return chat.pendingApprovals.filter(
    (a) => a.sessionId === chat.currentSessionId && !a.resolved
  );
});

// P5-10-2：当前选中审批 + 可批量允许的低风险项（判定与后端白名单一致）
const selectedApprovalId = ref<string | null>(null);
const batchableApprovals = computed(() => activeApprovals.value.filter(isBatchApprovable));

/** 队列变化时收口选中项：仍在就保留；被移除则优先选同位置后一项，没有后一项选前一项 */
watch(
  () => activeApprovals.value.map((a) => a.approvalId),
  (ids, oldIds) => {
    if (!ids.length) {
      selectedApprovalId.value = null;
      return;
    }
    if (selectedApprovalId.value && ids.includes(selectedApprovalId.value)) return;
    const oldIdx = oldIds ? oldIds.indexOf(selectedApprovalId.value ?? '') : -1;
    const nextIdx = oldIdx >= 0 ? Math.min(oldIdx, ids.length - 1) : 0;
    selectedApprovalId.value = ids[nextIdx];
  },
  { immediate: true, flush: 'post' }
);

function selectApproval(approvalId: string) {
  selectedApprovalId.value = approvalId;
  scrollSelectedApproval();
}

function scrollSelectedApproval() {
  nextTick(() => {
    const id = selectedApprovalId.value;
    if (!id || !approvalZoneEl.value) return;
    approvalZoneEl.value
      .querySelector<HTMLElement>(`[data-approval-id="${id}"]`)
      ?.scrollIntoView({ block: 'nearest' });
  });
}

/** J/K 在当前队列内移动，到边界后停止，不循环 */
function moveApprovalSelection(delta: number) {
  const list = activeApprovals.value;
  if (!list.length) return;
  const idx = list.findIndex((a) => a.approvalId === selectedApprovalId.value);
  const next = Math.max(0, Math.min(list.length - 1, (idx < 0 ? 0 : idx) + delta));
  selectedApprovalId.value = list[next].approvalId;
  scrollSelectedApproval();
}

function approveLowRiskBatch() {
  void chat.approveBatch(batchableApprovals.value.map((a) => a.approvalId));
}

/** P5-10-2：输入框内打字不触发 J/K 移动 */
function isTypingTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  return !!el?.closest('input, textarea, select, [contenteditable="true"]');
}

// P5-12-4：轨迹面板点「待审批」步骤时，滚到审批区并短暂高亮
const approvalZoneEl = ref<HTMLElement>();
const approvalFlash = ref(false);
let approvalFlashTimer: number | undefined;
watch(
  () => chat.approvalFocusToken,
  () => {
    if (!activeApprovals.value.length) return;
    nextTick(() => {
      approvalZoneEl.value?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      approvalFlash.value = true;
      if (approvalFlashTimer) window.clearTimeout(approvalFlashTimer);
      approvalFlashTimer = window.setTimeout(() => {
        approvalFlash.value = false;
        approvalFlashTimer = undefined;
      }, 1200);
    });
  }
);

// 离开底部超过一段距离才显示「回到底部」，避免在底部时白占位置
const SCROLL_BOTTOM_GAP = 80;
const showScrollBottom = ref(false);
/** 用户是否仍停留在底部；向上翻阅历史时不再被流式内容强行拉回 */
const autoFollow = ref(true);

// P5-11-3：跟底也走合帧，避免每个流式 chunk 触发一次强制布局
const followScroll = createFrameThrottle<void>(() => {
  const el = messagesEl.value;
  if (!el || !autoFollow.value) return;
  el.scrollTop = el.scrollHeight;
  showScrollBottom.value = false;
});

/**
 * 只观察消息条数与最后一条的长度变化，避免每个 chunk 深遍历整个消息数组。
 * 普通文本、思考、工具、改动任一增长都意味着气泡高度可能变化。
 */
const followKey = computed(() => {
  const list = chat.messages;
  const last = list[list.length - 1];
  return [
    list.length,
    last?.content?.length ?? 0,
    last?.thinking?.length ?? 0,
    last?.tools?.length ?? 0,
    last?.changes?.length ?? 0,
    last?.streaming ? 1 : 0
  ].join(':');
});

watch(followKey, () => followScroll.push(), { flush: 'post' });

// 切会话 / 重新加载：清空高度缓存，恢复自动跟随并回到底部（与改造前一致）
watch(
  () => chat.messages,
  () => {
    autoFollow.value = true;
    resetVirtual();
    followScroll.push();
  },
  { flush: 'post' }
);

// 虚拟化下实测高度回填会把占位换成真实高度，补一次跟底，避免停在半路
watch(virtualLayoutVersion, () => followScroll.push(), { flush: 'post' });

function onScroll() {
  const el = messagesEl.value;
  if (!el) return;
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
  showScrollBottom.value = distance > SCROLL_BOTTOM_GAP;
  autoFollow.value = distance <= SCROLL_BOTTOM_GAP;
  virtualOnScroll();
  updateActiveTurn();
}

/** 统一的消息定位：虚拟窗口下先按估算偏移滚动，渲染后按实测高度校正一次 */
async function scrollToMessage(id: number) {
  const el = messagesEl.value;
  if (!el) return;
  const idx = chat.messages.findIndex((m) => m.id === id);
  if (idx < 0) return;
  if (virtualEnabled.value) {
    virtualScrollToIndex(idx);
    await nextTick();
  }
  let node = el.querySelector<HTMLElement>(`[data-msg-id="${id}"]`);
  if (!node && virtualEnabled.value) {
    // 估算高度偏差过大时目标仍未进入窗口，再校正一次
    virtualScrollToIndex(idx);
    await nextTick();
    node = el.querySelector<HTMLElement>(`[data-msg-id="${id}"]`);
  }
  node?.scrollIntoView({ block: 'center', behavior: isReducedMotion() ? 'auto' : 'smooth' });
}

/** 全文搜索命中后定位：等消息渲染完，滚动到那条消息并清空定位目标 */
function scrollToFocusedMessage() {
  const targetId = chat.focusMessageId;
  if (targetId == null) return;
  nextTick(() => {
    const local = chat.messages.find((m) => m.sourceId === targetId);
    if (local) void scrollToMessage(local.id);
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

/** 消息 id → 数组下标，虚拟化下用它把轮次映射到高度缓存偏移 */
const messageIndexById = computed(() => {
  const map = new Map<number, number>();
  chat.messages.forEach((m, i) => map.set(m.id, i));
  return map;
});

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
  const threshold = el.scrollTop + 90;
  // P5-11-3：虚拟窗口下只有可视段在 DOM 里，改用高度缓存偏移判断，避免漏掉未渲染的轮次
  if (virtualEnabled.value) {
    let current = list[0].id;
    for (const t of list) {
      const idx = messageIndexById.value.get(t.id);
      if (idx == null) continue;
      if (virtualOffsetOfIndex(idx) <= threshold) current = t.id;
      else break;
    }
    activeTurnId.value = current;
    return;
  }
  const containerTop = el.getBoundingClientRect().top;
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

/** 点横线：把那一轮的用户消息滚到视窗偏上位置（虚拟化下自动先渲染再校正） */
function jumpToTurn(id: number) {
  void scrollToMessage(id);
}

// 消息条数变化（切会话 / 新发一轮）后重算当前位置；流式 chunk 只改内容不改条数，不重复计算
watch(() => chat.messages.length, () => nextTick(updateActiveTurn));
// 虚拟化下实测高度回填会改变偏移，同步刷新缩略导航高亮
watch(virtualLayoutVersion, () => nextTick(updateActiveTurn));

/** 平滑回到底部；按钮立刻收起来，不用等滚动动画结束 */
function scrollToBottom() {
  const el = messagesEl.value;
  if (!el) return;
  autoFollow.value = true;
  el.scrollTo({ top: el.scrollHeight, behavior: isReducedMotion() ? 'auto' : 'smooth' });
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

// ——— P5-10：快捷键面板 + 读屏播报 ———
const shortcutsOpen = ref(false);
const shortcutsPanelEl = ref<HTMLElement | null>(null);
const shortcutsCloseEl = ref<HTMLButtonElement | null>(null);
/** 打开前的焦点，关闭面板时归还；元素已卸载则回退到输入框 */
let shortcutReturnFocus: HTMLElement | null = null;
const shortcutList = [
  { label: '聚焦输入框', keys: ['Ctrl', 'K'] },
  { label: '连续按两次停止生成', keys: ['Esc', 'Esc'] },
  { label: '批准当前审批', keys: ['A'] },
  { label: '拒绝当前审批', keys: ['D'] },
  { label: '切换待审批', keys: ['J', 'K'] },
  { label: '发送消息', keys: ['Enter'] },
  { label: '换行', keys: ['Shift', 'Enter'] },
  { label: '引用文件 / 资料 / 会话', keys: ['@'] },
  { label: '斜杠命令', keys: ['/'] },
  { label: '打开快捷键面板', keys: ['Ctrl', '/'] }
];

/** P5-10-4：打开时记住来源焦点，关闭时归还 */
function openShortcuts() {
  shortcutReturnFocus = document.activeElement as HTMLElement | null;
  shortcutsOpen.value = true;
  void nextTick(() => shortcutsCloseEl.value?.focus());
}

function closeShortcuts() {
  shortcutsOpen.value = false;
  const target = shortcutReturnFocus;
  shortcutReturnFocus = null;
  void nextTick(() => {
    if (target && document.contains(target)) target.focus();
    else inputEl.value?.focus();
  });
}

const liveAnnounce = ref('');
watch(
  () => chat.isProcessing,
  (busy) => {
    if (busy) liveAnnounce.value = '助手正在生成回答';
    else if (activeApprovals.value.length === 0) liveAnnounce.value = '回答已生成';
  }
);
watch(
  () => chat.streamStatus,
  (status) => {
    if (status === 'reconnecting') liveAnnounce.value = '连接中断，正在恢复未完成的内容';
    else if (status === 'recovered') liveAnnounce.value = '已从断点恢复内容';
    else if (status === 'interrupted') liveAnnounce.value = '连接中断，已保留已生成的内容';
  }
);
// P5-10-4：审批到达与清空只播报数量变化，不逐条打断
watch(
  () => activeApprovals.value.length,
  (count, prev) => {
    if (count > 0) liveAnnounce.value = `有 ${count} 项审批待确认，按 A 允许、D 拒绝、J/K 切换`;
    else if (prev) liveAnnounce.value = '审批已处理完';
  }
);

// Esc Esc 停止生成：500ms 内连按两次 Esc 才触发，避免误按打断；
// 弹窗打开时（Element Plus 用 Esc 关弹窗）或命令面板已消费该事件时不计数
let lastEscAt = 0;
function onGlobalKeydown(e: KeyboardEvent) {
  // 快捷键面板打开时 Tab 只在面板内循环，避免焦点跑到页面背景
  if (shortcutsOpen.value && e.key === 'Tab' && shortcutsPanelEl.value) {
    if (trapTabKey(shortcutsPanelEl.value, e)) return;
  }
  // Ctrl/Cmd+K：随时聚焦输入框（弹窗打开时让位给弹窗）
  if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
    if (document.querySelector('.el-overlay')) return;
    e.preventDefault();
    inputEl.value?.focus();
    return;
  }
  // Ctrl/Cmd+/：查看快捷键
  if ((e.ctrlKey || e.metaKey) && e.key === '/') {
    e.preventDefault();
    if (shortcutsOpen.value) closeShortcuts();
    else openShortcuts();
    return;
  }
  if (e.key === 'Escape' && shortcutsOpen.value) {
    closeShortcuts();
    return;
  }
  // P5-10-2：J/K 在当前会话审批队列中移动；输入态或带修饰键时让给原行为
  if (!e.metaKey && !e.ctrlKey && !e.altKey && !isTypingTarget(e.target)) {
    const key = e.key.toLowerCase();
    if (key === 'j' || key === 'k') {
      if (!activeApprovals.value.length) return;
      e.preventDefault();
      moveApprovalSelection(key === 'j' ? 1 : -1);
      return;
    }
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
  // 侧栏任务列表与空态概览共用 store 里的运行态，这里刷新一次
  chat.refreshRunningRuns();
  // 首屏回填的草稿可能是多行，等 DOM 就绪后按内容撑高输入框
  if (inputText.value) nextTick(resizeInput);
  // 搜索命中时可能从别的页面跳进对话页，等本页挂载后补一次定位
  scrollToFocusedMessage();
  // 首屏已有历史时，先算出右侧缩略导航的当前位置
  nextTick(updateActiveTurn);
  // P5-14：恢复当前会话的任务交付视图（刷新后由后端重建）
  if (hasLocalWorkspace.value) void chat.fetchChangeSet();
});

onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown);
  if (streamNoticeTimer) window.clearTimeout(streamNoticeTimer);
  if (approvalFlashTimer) window.clearTimeout(approvalFlashTimer);
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
</script>

<style scoped>
.chat-page {
    padding: 0;
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

/* P5-10：快捷键面板 */
.shortcuts-mask {
    position: fixed;
    inset: 0;
    z-index: 2600;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgb(0 0 0 / 32%);
}
.shortcuts-panel {
    width: min(380px, 90vw);
    padding: 14px 16px 10px;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: var(--surface);
    box-shadow: var(--shadow-lg, 0 12px 32px rgb(0 0 0 / 18%));
    color: var(--text);
    font-size: var(--fs-sm);
}
.shortcuts-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
    font-weight: 600;
}
.shortcuts-close {
    border: none;
    background: transparent;
    color: var(--text-secondary);
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
}
.shortcuts-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 6px 0;
    border-top: 1px solid var(--border);
}
.shortcuts-label { color: var(--text-secondary); }
.shortcuts-keys { display: inline-flex; gap: 4px; }
.shortcuts-keys kbd {
    min-width: 20px;
    padding: 1px 6px;
    border: 1px solid var(--border);
    border-bottom-width: 2px;
    border-radius: 4px;
    background: var(--surface-2);
    color: var(--text);
    font-family: inherit;
    font-size: var(--fs-xs);
    text-align: center;
}
/* P5-12-1：空态只留金月标识与一句欢迎，垂直居中、轻量 */
.chat-empty {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 18px;
    padding-bottom: 4vh;
    color: var(--text);
}
/* 品牌金：月亮取品牌金 #f5c451，外圈是一层很淡的光晕 */
.chat-empty-mark {
    display: inline-flex;
    color: #f5c451;
    filter: drop-shadow(0 0 16px rgb(245 196 81 / 26%));
}
.chat-empty-greeting {
    margin: 0;
    font-size: var(--fs-lg);
    font-weight: 500;
    color: var(--text-secondary);
    letter-spacing: .02em;
}

/* P5-12-4：审批队列——多项时不被互相遮蔽，超出高度内部滚动 */
.approval-queue {
    flex-shrink: 0;
    margin: 0 20px 6px;
    border-radius: var(--radius-sm);
    transition: box-shadow .25s, background .25s;
}
.approval-queue.is-flashing {
    background: color-mix(in srgb, var(--warning) 12%, transparent);
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--warning) 45%, transparent);
}
.approval-queue-head {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 4px;
    padding: 0 2px;
    font-size: var(--fs-xs);
    color: var(--warning);
    font-weight: 600;
}
.approval-queue-hint {
    margin-left: auto;
    color: var(--text-tertiary);
    font-weight: 400;
}
/* P5-10-2：低风险批量允许入口，只影响白名单内的写/编辑审批 */
.approval-batch-btn {
    margin-left: 4px;
    padding: 1px 8px;
    font-size: var(--fs-xs);
    font-weight: 600;
    color: var(--primary);
    background: color-mix(in srgb, var(--primary) 10%, transparent);
    border: 1px solid color-mix(in srgb, var(--primary) 35%, transparent);
    border-radius: var(--radius-sm);
    cursor: pointer;
}
.approval-batch-btn:hover {
    background: color-mix(in srgb, var(--primary) 18%, transparent);
}
.approval-queue-body {
    max-height: 42vh;
    overflow-y: auto;
}
/* 卡片自带左右外边距，队列里改由队列统一控制，避免双重缩进 */
.approval-queue-body :deep(.approval-card) {
    margin: 0 0 6px;
}
.approval-queue-body :deep(.approval-card:last-child) {
    margin-bottom: 0;
}
</style>
