<template>
  <aside
    class="workbench"
    :class="{ fullscreen, 'is-file': chat.dockTab === 'file' }"
    :style="fullscreen ? {} : { width: width + 'px' }"
  >
    <div class="wb-header">
      <div class="wb-tabs">
        <button class="wb-tab" :class="{ active: chat.dockTab === 'docs' }" @click="select('docs')">
          资料
          <span v-if="attachments.length" class="wb-tab-count">{{ attachments.length }}</span>
        </button>
        <button v-if="hasLocalWorkspace" class="wb-tab" :class="{ active: chat.dockTab === 'changes' }" @click="select('changes')">
          变更
          <span v-if="changes.length" class="wb-tab-count">{{ changes.length }}</span>
        </button>
        <button class="wb-tab" :class="{ active: chat.dockTab === 'plan' }" @click="select('plan')">
          计划
          <span v-if="planProgress.total" class="wb-tab-count">{{ planProgress.done }}/{{ planProgress.total }}</span>
        </button>
        <button class="wb-tab" :class="{ active: chat.dockTab === 'trace' }" @click="select('trace')">
          追踪
          <span v-if="pendingApprovalCount" class="wb-tab-count wb-tab-warn">{{ pendingApprovalCount }}</span>
        </button>
        <!-- 每个打开的文件一个 tab（可多个），tab 上有未保存圆点与关闭叉号；可拖拽排序 -->
        <div
          v-for="p in chat.openFiles"
          :key="p"
          class="wb-tab wb-tab-file"
          :class="{
            active: chat.dockTab === 'file' && chat.activeFilePath === p,
            dragging: dragPath === p,
            'drop-left': dropTarget?.path === p && !dropTarget.after,
            'drop-right': dropTarget?.path === p && dropTarget.after
          }"
          :title="p"
          draggable="true"
          @click="selectFile(p)"
          @dragstart="onDragStart($event, p)"
          @dragover.prevent="onDragOver($event, p)"
          @drop.prevent="onDrop"
          @dragend="endDrag"
        >
          <Icon name="file-text" :size="12" />
          <span class="wb-tab-file-name">{{ baseName(p) }}</span>
          <span v-if="chat.fileDirty[p]" class="wb-tab-dot" title="有未保存的改动"></span>
          <span class="wb-tab-close" title="关闭这个文件" @click.stop="closeTab(p)">
            <Icon name="x" :size="11" />
          </span>
        </div>
      </div>
      <button class="wb-icon-btn" :title="fullscreen ? '退出全屏' : '全屏'" :aria-label="fullscreen ? '退出全屏' : '全屏'" @click="$emit('toggle-fullscreen')">
        <Icon :name="fullscreen ? 'compress' : 'expand'" :size="14" />
      </button>
      <button class="wb-icon-btn" title="收起" aria-label="收起工作台" @click="$emit('close')">
        <Icon name="x" :size="15" />
      </button>
    </div>

    <div class="wb-body">
      <!-- 资料：本轮附件 + 会话已发送附件，随时回看 -->
      <template v-if="chat.dockTab === 'docs'">
        <div v-if="attachments.length" class="wb-list">
          <div v-for="a in attachments" :key="a.id" class="wb-item">
            <Icon name="file-text" :size="15" class="wb-item-icon" />
            <span class="wb-item-name" :title="a.fileName">{{ a.fileName }}</span>
          </div>
        </div>
        <div v-else class="wb-empty">
          <Icon name="paperclip" :size="22" />
          <p>本会话还没有上传附件</p>
          <p class="wb-empty-sub">在输入框点「上传文件」即可添加</p>
        </div>
        <router-link class="wb-link" to="/knowledge">
          管理「我的资料」<Icon name="chevron-right" :size="13" />
        </router-link>
      </template>

      <!-- 变更：只列出改动过的文件，点一下就在文件 tab 里用编辑器打开 -->
      <template v-else-if="chat.dockTab === 'changes'">
        <div v-if="changes.length" class="wb-list">
          <button
            v-for="c in changes"
            :key="c.path"
            class="wb-change"
            @click="openChange(c)"
          >
            <Icon :name="c.action === 'create' ? 'plus' : c.action === 'delete' ? 'trash' : 'file-text'" :size="14" class="wb-item-icon" />
            <span class="wb-item-name" :title="c.path">{{ c.path }}</span>
            <span v-if="c.source === 'run_command'" class="wb-tag">命令</span>
            <span v-if="c.action === 'delete'" class="wb-tag">已删除</span>
            <span v-if="c.additions || c.deletions" class="wb-stat">
              <span class="wb-add">+{{ c.additions }} 行</span>
              <span class="wb-del">-{{ c.deletions }} 行</span>
            </span>
          </button>
        </div>
        <div v-else class="wb-empty">
          <Icon name="file-text" :size="22" />
          <p>本会话还没有改动文件</p>
          <p class="wb-empty-sub">助手写入或修改文件后会出现在这里</p>
        </div>
      </template>

      <!-- 计划：本轮 Agent 提交的任务步骤，实时显示 pending / in_progress / completed -->
      <template v-else-if="chat.dockTab === 'plan'">
        <template v-if="latestPlan">
          <div class="plan-summary">
            <div class="plan-summary-top">
              <span class="plan-summary-title">本轮任务计划</span>
              <span class="plan-summary-count">{{ planProgress.done }}/{{ planProgress.total }} 完成</span>
            </div>
            <div class="plan-progress-track">
              <div class="plan-progress-bar" :style="{ width: planProgress.percent + '%' }"></div>
            </div>
          </div>
          <div class="wb-list">
            <div
              v-for="(s, i) in latestPlan.steps"
              :key="i"
              class="plan-step"
              :class="'is-' + s.status"
            >
              <span class="plan-step-mark">
                <Icon v-if="s.status === 'completed'" name="check" :size="12" />
                <span v-else-if="s.status === 'in_progress'" class="plan-step-dot"></span>
                <span v-else class="plan-step-index">{{ i + 1 }}</span>
              </span>
              <span class="plan-step-title">{{ s.title }}</span>
              <span class="plan-step-status">{{ planStatusLabel(s.status) }}</span>
            </div>
          </div>
        </template>
        <div v-else class="wb-empty">
          <Icon name="clock" :size="22" />
          <p>这一轮还没有任务计划</p>
          <p class="wb-empty-sub">助手处理多步任务时会先列出步骤</p>
        </div>
      </template>

      <!-- 第五十四批：追踪——会话级工具调用统计 + 时间线 + 变更汇总 -->
      <template v-else-if="chat.dockTab === 'trace'">
        <div v-if="!chat.currentSessionId" class="wb-empty">
          <Icon name="activity" :size="22" />
          <p>还没有开始会话</p>
          <p class="wb-empty-sub">发送第一条消息后这里会显示工具调用追踪</p>
        </div>
        <div v-else-if="traceLoading" class="wb-empty">
          <Icon name="loader" :size="22" class="wb-spin" />
          <p>加载中…</p>
        </div>
        <div v-else-if="!traceStats && !pendingApprovals.length && !chat.approvalHistory.length" class="wb-empty">
          <Icon name="alert-circle" :size="22" />
          <p>加载失败</p>
          <p class="wb-empty-sub">点上面的「追踪」tab 重试</p>
        </div>
        <template v-else>
          <!-- P5-12-4：待审批步骤单独高亮，点一下直接定位聊天区的审批卡片 -->
          <div v-if="pendingApprovals.length" class="trace-approvals">
            <div class="trace-section-title">
              待审批 {{ pendingApprovals.length }} 项
              <span class="trace-approvals-hint">处理后 Agent 才会继续</span>
            </div>
            <button
              v-for="a in pendingApprovals"
              :key="a.approvalId"
              class="trace-approval-row"
              @click="chat.requestApprovalFocus()"
            >
              <Icon name="shield" :size="12" class="trace-approval-icon" />
              <span class="trace-approval-tool">{{ a.tool }}</span>
              <span v-if="a.preview?.path" class="trace-approval-path" :title="a.preview.path">
                {{ baseName(a.preview.path) }}
              </span>
              <span v-if="a.preview?.kind === 'command'" class="trace-approval-path">执行命令</span>
              <span class="trace-approval-go">去处理 →</span>
            </button>
          </div>

          <!-- P5-10-2：本会话已处理审批历史，刷新或重开页面后仍可追溯 -->
          <div
            v-if="chat.approvalHistory.length || chat.approvalHistoryLoading"
            class="trace-approvals trace-approval-history"
          >
            <div class="trace-section-title">
              审批历史
              <span class="trace-approvals-hint">本会话已处理</span>
              <span v-if="chat.approvalHistoryLoading" class="wb-spin trace-history-loading">
                <Icon name="loader" :size="11" />
              </span>
            </div>
            <div v-if="!chat.approvalHistory.length" class="wb-empty-inline">加载中…</div>
            <div v-for="h in chat.approvalHistory" :key="h.approvalId" class="trace-history-row">
              <Icon
                :name="h.state === 'APPROVED' ? 'check-circle' : 'x-circle'"
                :size="12"
                :class="h.state === 'APPROVED' ? 'his-ok' : 'his-no'"
              />
              <span class="trace-history-tool" :title="h.tool">{{ h.tool }}</span>
              <span class="trace-history-state" :class="h.state === 'APPROVED' ? 'ok' : 'no'">
                {{ h.state === 'APPROVED' ? '已批准' : '已拒绝' }}
              </span>
              <span class="trace-history-source">{{ approvalSourceLabel(h.decisionSource) }}</span>
              <span v-if="h.rememberSession" class="trace-history-remember">本会话总是允许</span>
              <span class="trace-history-time">{{ fmtTime(h.decisionTime || h.createTime) }}</span>
            </div>
          </div>

          <!-- 无统计（会话还没跑完一轮）时仍展示上面的待审批区 -->
          <template v-if="traceStats">
          <!-- 工具时间线：日常最常看的过程记录，放在最上面 -->
          <div class="trace-section">
            <div class="trace-section-title">工具时间线</div>
            <div v-if="!traceStats.timeline.length" class="wb-empty-inline">（本会话无工具调用）</div>
            <template v-for="(t, i) in traceStats.timeline" :key="i">
              <!-- P5-12-3：行内直接给「改了哪个文件 / 增删多少行」，待审批的调用单独高亮 -->
              <div
                class="trace-tool-row"
                :class="{
                  'is-pending': timelinePending(t),
                  'is-clickable': !!t.path,
                  expanded: expandedTrace === i
                }"
                @click="onTraceRowClick(i, t)"
              >
                <span class="trace-tool-source" :class="t.source === 'mcp' ? 'src-mcp' : 'src-builtin'">
                  {{ t.source === 'mcp' ? 'MCP' : '内置' }}
                </span>
                <span class="trace-tool-name" :title="t.argsSummary">{{ t.name }}</span>
                <span v-if="t.path" class="trace-tool-path" :title="t.path">{{ baseName(t.path) }}</span>
                <span v-if="timelinePending(t)" class="trace-tool-pending">待审批</span>
                <span v-if="(t.additions ?? 0) || (t.deletions ?? 0)" class="trace-tool-stat">
                  <span class="wb-add">+{{ t.additions ?? 0 }}</span>
                  <span class="wb-del">−{{ t.deletions ?? 0 }}</span>
                </span>
                <span class="trace-tool-status" :class="t.status">{{ t.status === 'success' ? '✓' : t.status === 'error' ? '✗' : '…' }}</span>
                <span class="trace-tool-cost">{{ fmtMs(t.costMs) }}</span>
              </div>
              <!-- 点轨迹行展开该文件的 diff（取自本轮消息里的变更） -->
              <div v-if="expandedTrace === i" class="trace-diff-preview trace-row-diff">
                <div class="trace-diff-header">
                  <Icon name="file-text" :size="10" />
                  {{ t.path }}
                  <span class="trace-diff-source">（{{ traceRowDiff?.action === 'create' ? '新建' : '修改' }}）</span>
                  <span class="trace-diff-close" @click.stop="closeTraceDiff" title="关闭">✕</span>
                </div>
                <pre v-if="traceRowDiff?.diff" class="trace-diff-code">{{ traceRowDiff.diff }}</pre>
                <div v-else class="trace-diff-empty">本轮轨迹未保存该文件的 diff</div>
                <button class="trace-diff-open" @click.stop="chat.openFile(t.path!)">在编辑器中打开 →</button>
              </div>
            </template>
          </div>

          <!-- 统计、耗时、MCP、聚合收进「运行详情」，默认收起，避免日常视图过载 -->
          <details class="trace-details">
            <summary>
              运行详情
              <span class="trace-details-hint">统计 · 耗时拆分 · MCP · 聚合</span>
            </summary>
          <!-- Overview 统计卡片 -->
          <div class="trace-overview-wrapper">
            <button class="trace-export-btn" :disabled="!chat.currentSessionId" @click="exportSessionMd" title="导出为 Markdown">
              <Icon name="save" :size="12" />
              <span>导出 .md</span>
            </button>
            <div class="trace-overview">
            <div class="trace-card">
              <div class="trace-card-val">{{ traceStats.overview.totalRounds }}</div>
              <div class="trace-card-label">轮次</div>
            </div>
            <div class="trace-card">
              <div class="trace-card-val">{{ traceStats.overview.totalToolCalls }}</div>
              <div class="trace-card-label">工具调用</div>
            </div>
            <div class="trace-card">
              <div class="trace-card-val">{{ traceStats.overview.uniqueTools }}</div>
              <div class="trace-card-label">工具种类</div>
            </div>
            <div class="trace-card" :class="{ bad: traceStats.overview.toolSuccessRate < 0.9 }">
              <div class="trace-card-val">{{ Math.round(traceStats.overview.toolSuccessRate * 100) }}%</div>
              <div class="trace-card-label">成功率</div>
            </div>
            <div class="trace-card">
              <div class="trace-card-val">{{ traceStats.overview.uniqueFilesChanged }}</div>
              <div class="trace-card-label">变更文件</div>
            </div>
            <div class="trace-card">
              <div class="trace-card-val">{{ fmtMs(traceStats.overview.totalDurationMs) }}</div>
              <div class="trace-card-label">会话耗时</div>
            </div>
            <div class="trace-card" v-if="traceStats.usage && traceStats.usage.totalTokens > 0"
                 :title="`输入 ${traceStats.usage.promptTokens} / 输出 ${traceStats.usage.completionTokens}`">
              <div class="trace-card-val">{{ fmtTokens(traceStats.usage.totalTokens) }}</div>
              <div class="trace-card-label">Token</div>
            </div>
            <div class="trace-card trace-card-wide">
              <div class="trace-card-meta">
                <span class="trace-meta-pill">{{ fmtReasoning(traceStats.overview.reasoningPolicy) }}</span>
                <span class="trace-meta-pill" :class="'perm-' + traceStats.overview.permission?.toLowerCase()">
                  {{ fmtPermission(traceStats.overview.permission) }}
                </span>
              </div>
              <div class="trace-card-label" v-if="traceStats.overview.startedAt">
                {{ fmtTime(traceStats.overview.startedAt) }} ~ {{ fmtTime(traceStats.overview.lastActiveAt) }}
              </div>
              <div class="trace-card-label" v-else>会话刚开始</div>
            </div>
            </div><!-- /trace-overview -->
          </div><!-- /trace-overview-wrapper -->

          <!-- 第五十六批：耗时口径拆分（setup → tools → answer） -->
          <div v-if="breakdownPercents" class="trace-breakdown">
            <div class="trace-breakdown-track" :title="`Setup ${breakdownPercents.setupMs}ms · Tools ${breakdownPercents.toolsMs}ms · Answer ${breakdownPercents.answerMs}ms`">
              <div class="trace-breakdown-bar bar-setup" :style="{ width: breakdownPercents.setup + '%' }"></div>
              <div class="trace-breakdown-bar bar-tools" :style="{ width: breakdownPercents.tools + '%' }"></div>
              <div class="trace-breakdown-bar bar-answer" :style="{ width: breakdownPercents.answer + '%' }"></div>
            </div>
            <div class="trace-breakdown-labels">
              <span class="trace-bl-item">
                <span class="trace-bl-dot dot-setup"></span>
                <span class="trace-bl-name">准备</span>
                <span class="trace-bl-val">{{ fmtMs(breakdownPercents.setupMs) }} <span class="trace-bl-pct">{{ breakdownPercents.setup }}%</span></span>
              </span>
              <span class="trace-bl-item">
                <span class="trace-bl-dot dot-tools"></span>
                <span class="trace-bl-name">工具</span>
                <span class="trace-bl-val">{{ fmtMs(breakdownPercents.toolsMs) }} <span class="trace-bl-pct">{{ breakdownPercents.tools }}%</span></span>
              </span>
              <span class="trace-bl-item">
                <span class="trace-bl-dot dot-answer"></span>
                <span class="trace-bl-name">回答</span>
                <span class="trace-bl-val">{{ fmtMs(breakdownPercents.answerMs) }} <span class="trace-bl-pct">{{ breakdownPercents.answer }}%</span></span>
              </span>
              <span class="trace-bl-sub">{{ breakdownPercents.roundsWithSegments }} 轮有分段数据</span>
            </div>
          </div>

          <!-- 第五十五批：MCP Server 健康状态 -->
          <div v-if="mcpStatus?.enabled || (traceStats.tools.some(t => t.source === 'mcp') && !mcpStatusLoading)" class="trace-section">
            <div class="trace-section-title">
              MCP Servers
              <button v-if="mcpStatus" class="trace-refresh-btn" @click="refreshMcpStatus" title="刷新">
                <Icon name="refresh" :size="11" />
              </button>
              <span v-if="mcpStatusLoading" class="wb-spin"><Icon name="loader" :size="11" /></span>
            </div>
            <div v-if="mcpStatus?.enabled" class="mcp-server-list">
              <div
                v-for="(status, name) in mcpStatus.servers"
                :key="name"
                class="mcp-server"
              >
                <span class="mcp-dot" :class="'dot-' + status.status.toLowerCase()"></span>
                <span class="mcp-server-name">{{ name }}</span>
                <span class="mcp-server-status" :class="'mcp-' + status.status.toLowerCase()">
                  {{ status.status === 'UP' ? '正常' : status.status === 'DOWN' ? '熔断' : '未知' }}
                </span>
                <span v-if="status.consecutiveFailures > 0" class="mcp-server-fail">
                  连续失败 {{ status.consecutiveFailures }}
                </span>
                <span v-if="status.lastError" class="mcp-server-error" :title="status.lastError">
                  {{ status.lastError.length > 40 ? status.lastError.slice(0, 40) + '…' : status.lastError }}
                </span>
              </div>
            </div>
            <div v-else-if="mcpStatusLoading" class="wb-empty-inline">检测中…</div>
            <div v-else-if="traceStats.tools.some(t => t.source === 'mcp')" class="wb-empty-inline">
              本会话有 MCP 工具调用，但当前 MCP 未启用或健康接口不可用
            </div>
          </div>

          <!-- 工具按名称聚合 -->
          <div v-if="traceStats.tools.length" class="trace-section">
            <div class="trace-section-title">工具统计（按名称）</div>
            <div class="wb-list">
              <div v-for="t in traceStats.tools" :key="t.name" class="trace-toolstat">
                <span class="trace-tool-source" :class="t.source === 'mcp' ? 'src-mcp' : 'src-builtin'">
                  {{ t.source === 'mcp' ? 'MCP' : '内置' }}
                </span>
                <span class="trace-toolstat-name">{{ t.name }}</span>
                <span class="trace-toolstat-calls">{{ t.calls }} 次</span>
                <span class="trace-toolstat-success">✓{{ t.success }}</span>
                <span v-if="t.failure" class="trace-toolstat-fail">✗{{ t.failure }}</span>
                <span
                  class="trace-toolstat-rate"
                  :class="{ bad: t.successRate < 0.9 && t.calls >= 3 }"
                  :title="`成功率 ${Math.round(t.successRate * 100)}%`"
                >
                  {{ Math.round(t.successRate * 100) }}%
                </span>
                <span class="trace-toolstat-avg">均{{ fmtMs(t.avgCostMs) }}</span>
                <span class="trace-toolstat-p95">P95 {{ fmtMs(t.p95CostMs) }}</span>
                <span class="trace-toolstat-max">最大{{ fmtMs(t.maxCostMs) }}</span>
              </div>
            </div>
          </div>

          <!-- 文件变更汇总 -->
          <div v-if="traceStats.files.length" class="trace-section">
            <div class="trace-section-title">文件变更（聚合）</div>
            <div class="wb-list">
              <div v-for="f in traceStats.files" :key="f.path" class="trace-filestat" :class="{ expanded: expandedFile === f.path }" @click="toggleFileDiff(f.path)">
                <Icon :name="expandedFile === f.path ? 'chevron-down' : 'chevron-right'" :size="10" class="trace-filestat-chev" />
                <span class="trace-filestat-path" :title="f.path">{{ f.path }}</span>
                <span class="wb-add">+{{ f.additions }}</span>
                <span class="wb-del">-{{ f.deletions }}</span>
              </div>
              <!-- diff 预览展开面板 -->
              <div v-if="expandedFileDiff" class="trace-diff-preview">
                <div class="trace-diff-header">
                  {{ expandedFileDiff.path }}
                  <span class="trace-diff-source">（{{ expandedFileDiff.source === 'edit_file' ? '编辑' : expandedFileDiff.source === 'write_file' ? '写入' : expandedFileDiff.source === 'mcp' ? 'MCP' : expandedFileDiff.source }}）</span>
                  <span class="trace-diff-close" @click.stop="expandedFile = null" title="关闭">✕</span>
                </div>
                <pre class="trace-diff-code">{{ expandedFileDiff.diff }}</pre>
                <button class="trace-diff-open" @click.stop="chat.openFile(expandedFile!)">在编辑器中打开 →</button>
              </div>
            </div>
          </div>
          </details>
          </template>
        </template>
      </template>



      <!-- 文件：每个 tab 一个编辑器实例，切走时用 v-show 保持挂载，未保存内容不丢 -->
      <div
        v-for="p in chat.openFiles"
        :key="p"
        v-show="chat.dockTab === 'file' && chat.activeFilePath === p"
        class="wb-file"
      >
        <FileEditorPane
          :path="p"
          :change="changeOf(p)"
          :focus-line="chat.dockTab === 'file' && chat.activeFilePath === p ? chat.fileFocusLine : null"
          :focus-tick="chat.fileFocusTick"
          @close="closeTab(p)"
          @update:dirty="(d) => chat.setFileDirty(p, d)"
        />
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useChatStore } from '../stores/chat';
import { useMetaStore } from '../stores/meta';
import * as agentApi from '../api/agent';
import * as mcpApi from '../api/mcp';
import type { McpStatusResponse } from '../api/mcp';
import type { FileChange, SessionStats } from '../types/api';
import type { ApprovalHistoryItem, PlanSnapshot, PlanStepStatus } from '../types/chat';
import Icon from './Icon.vue';
import FileEditorPane from './FileEditorPane.vue';

defineProps<{
  /** 工作台宽度（px），由父组件拖动控制 */
  width: number;
  fullscreen?: boolean;
}>();

defineEmits<{
  (e: 'close'): void;
  (e: 'toggle-fullscreen'): void;
}>();

const chat = useChatStore();
const meta = useMetaStore();
/** 网页端没有本地文件改动，工作台不展示「变更」页 */
const hasLocalWorkspace = computed(() => meta.has('LOCAL_WORKSPACE'));

// ——— 第五十四批：追踪面板状态 ———
const traceLoading = ref(false);
const traceStats = ref<SessionStats | null>(null);
let traceLoadedSessionId: number | null = null;

// 路线①收尾：文件变更行展开 diff 预览
const expandedFile = ref<string | null>(null);

/** 从 chat.messages 里找某文件最后一次有 diff 的改动 */
const expandedFileDiff = computed(() => {
  const path = expandedFile.value;
  if (!path) return null;
  for (let i = chat.messages.length - 1; i >= 0; i--) {
    const m = chat.messages[i];
    if (m.role !== 'assistant') continue;
    const change = (m.changes || []).find((c) => c.path === path);
    if (change && change.diff) return change;
  }
  return null;
});

function toggleFileDiff(path: string) {
  expandedFile.value = expandedFile.value === path ? null : path;
}

// ——— P5-12-3/4：轨迹行内联 diff 与待审批联动 ———
/** 当前会话未处理的审批：轨迹里高亮对应步骤，也能直接跳去处理 */
const pendingApprovals = computed(() =>
  chat.pendingApprovals.filter((a) => a.sessionId === chat.currentSessionId && !a.resolved)
);
const pendingApprovalCount = computed(() => pendingApprovals.value.length);

/** P5-10-2：审批来源标签；旧记录没有来源时不猜成本人 */
function approvalSourceLabel(source: ApprovalHistoryItem['decisionSource']): string {
  switch (source) {
    case 'USER': return '本人';
    case 'BATCH': return '本人 · 批量';
    case 'CLIENT': return '客户端';
    case 'TIMEOUT': return '超时自动拒绝';
    case 'SESSION_STOP': return '会话停止';
    case 'SYSTEM': return '系统中止';
    default: return '来源未知';
  }
}

/** 该轨迹步骤是否在等待审批（工具同名，且路径不冲突） */
function timelinePending(t: SessionStats['timeline'][number]): boolean {
  return pendingApprovals.value.some((a) => {
    if (a.tool !== t.name) return false;
    const p = a.preview?.path;
    return !p || !t.path || p === t.path;
  });
}

/** 点开的轨迹行下标 */
const expandedTrace = ref<number | null>(null);
/** 行内 diff：从本轮消息里找该文件最后一次改动 */
const traceRowDiff = computed(() => {
  const idx = expandedTrace.value;
  if (idx == null || !traceStats.value) return null;
  const path = traceStats.value.timeline[idx]?.path;
  if (!path) return null;
  for (let i = chat.messages.length - 1; i >= 0; i--) {
    const m = chat.messages[i];
    if (m.role !== 'assistant') continue;
    const change = (m.changes || []).find((c) => c.path === path);
    if (change?.diff) return change;
  }
  return null;
});

function onTraceRowClick(i: number, t: SessionStats['timeline'][number]) {
  if (timelinePending(t)) chat.requestApprovalFocus();
  if (t.path) expandedTrace.value = expandedTrace.value === i ? null : i;
}

function closeTraceDiff() {
  expandedTrace.value = null;
}

// ——— 第五十五批：MCP Server 健康状态（全局，与会话无关） ———
const mcpStatus = ref<McpStatusResponse | null>(null);
const mcpStatusLoading = ref(false);
let mcpStatusLoaded = false;

async function fetchMcpStatus() {
  if (mcpStatusLoaded) return;
  mcpStatusLoading.value = true;
  try {
    mcpStatus.value = await mcpApi.getMcpStatus();
    mcpStatusLoaded = true;
  } catch {
    mcpStatus.value = null;
  } finally {
    mcpStatusLoading.value = false;
  }
}

function refreshMcpStatus() {
  mcpStatusLoaded = false;
  fetchMcpStatus();
}

async function fetchTrace() {
  const sid = chat.currentSessionId;
  if (!sid) {
    traceStats.value = null;
    return;
  }
  if (sid === traceLoadedSessionId) return; // 已拉过
  traceLoading.value = true;
  try {
    traceStats.value = await agentApi.getSessionStats(sid);
    traceLoadedSessionId = sid;
  } catch {
    traceStats.value = null;
  } finally {
    traceLoading.value = false;
  }
}

// 切到追踪 tab 或换会话时拉一次；换会话强制重拉
watch(
  () => [chat.dockTab, chat.currentSessionId] as const,
  ([tab, sid]) => {
    if (sid !== traceLoadedSessionId) {
      traceLoadedSessionId = null;
      traceStats.value = null;
      expandedTrace.value = null;
    }
    if (tab === 'trace') {
      if (sid != null) fetchTrace();
      fetchMcpStatus(); // MCP 状态全局拉一次
    }
  },
  { immediate: true }
);

// 每轮结束后 trace 才落库：清掉缓存，停在追踪面板时自动重拉，避免看到上一轮的旧统计
watch(
  () => chat.isProcessing,
  (busy) => {
    if (busy) return;
    traceLoadedSessionId = null;
    if (chat.dockTab === 'trace') fetchTrace();
  }
);

// P5-10-2：打开追踪页、切会话或审批状态变化时刷新会话审批历史
watch(
  () => [chat.dockTab, chat.currentSessionId, chat.approvalRevision] as const,
  ([tab, sid]) => {
    if (tab === 'trace' && sid != null) void chat.loadApprovalHistory(sid);
  },
  { immediate: true }
);

/** 毫秒格式化：<1s 显示 ms，>=1s 显示秒 */
function fmtMs(ms: number | undefined): string {
  if (!ms || ms <= 0) return '0ms';
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  return Math.floor(ms / 60000) + 'm' + Math.floor((ms % 60000) / 1000) + 's';
}

/** P4-07：token 数量按千位缩写，避免卡片被长数字撑开 */
function fmtTokens(n: number | undefined): string {
  if (!n || n <= 0) return '0';
  if (n < 1000) return String(n);
  if (n < 1_000_000) return (n / 1000).toFixed(1) + 'k';
  return (n / 1_000_000).toFixed(2) + 'M';
}

/** 推理档位 → 中文短标签 */
function fmtReasoning(r: string | undefined): string {
  if (!r) return '';
  const map: Record<string, string> = { FAST: '快速', STANDARD: '标准', DEEP: '深度' };
  return map[r] ?? r;
}

/** 权限 → 中文短标签 */
function fmtPermission(p: string | undefined): string {
  if (!p) return '';
  const map: Record<string, string> = { MANUAL: '手动审批', AUTO: '自动审批', FULL: '完全权限', READ_ONLY: '只读' };
  return map[p] ?? p;
}

/** ISO 时间 → HH:mm */
function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '';
  try {
    const d = new Date(iso);
    const h = String(d.getHours()).padStart(2, '0');
    const m = String(d.getMinutes()).padStart(2, '0');
    return `${h}:${m}`;
  } catch {
    return '';
  }
}

/** 第五十五批：导出当前会话为 Markdown 文件 */
function exportSessionMd() {
  const sid = chat.currentSessionId;
  if (!sid) return;

  // 找会话标题（优先从 store 里已加载的 session 取，没有就用当前 id）
  const sessionInfo = chat.findSession(sid);
  const title = sessionInfo?.title?.trim() || `会话-${sid}`;

  // 只导出有实际内容的气泡（跳过 hint、failed、空 content 的）
  const rounds: { q: string; a: string; thinking?: string; tools?: string; changes?: string; duration?: string }[] = [];
  let curQ = '';

  for (const m of chat.messages) {
    if (m.role === 'hint') continue;
    if (m.role === 'user') {
      curQ = (m.content || '').trim();
    } else if (m.role === 'assistant') {
      const answer = (m.content || '').trim();
      if (!curQ && !answer) continue;
      const round: typeof rounds[0] = { q: curQ || '（无问题）', a: answer || (m.failed ? '❌ 本轮失败：' + (m.errorText || '') : '（未生成内容）') };

      if (m.thinking?.trim()) round.thinking = m.thinking.trim();
      if (m.tools?.length) {
        round.tools = m.tools
          .map((t) => {
            const icon = t.status === 'success' ? '✓' : t.status === 'error' ? '✗' : '…';
            const cost = t.costMs ? ` (${fmtMs(t.costMs)})` : '';
            return `- ${icon} ${t.name}${cost}`;
          })
          .join('\n');
      }
      if (m.changes?.length) {
        round.changes = m.changes
          .map((c) => `- \`${c.path}\`  +${c.additions} -${c.deletions}`)
          .join('\n');
      }
      if (m.durationMs) round.duration = fmtMs(m.durationMs);

      rounds.push(round);
      curQ = '';
    }
  }

  // 组装 Markdown
  const now = new Date();
  const header = [
    `# ${title}`,
    '',
    `> 导出时间：${now.toLocaleString()} | 共 ${rounds.length} 轮问答`,
    '',
    '---',
    ''
  ].join('\n');

  const body = rounds
    .map((r, i) => {
      const parts: string[] = [
        `## 第 ${i + 1} 轮${r.duration ? ` · ${r.duration}` : ''}`,
        '',
        `### 用户`,
        '',
        r.q,
        '',
        `### 助手`,
        '',
        r.a || '_（本轮未完成）_'
      ];
      if (r.thinking) {
        parts.push(
          '',
          '<details>',
          '<summary>思考过程</summary>',
          '',
          r.thinking,
          '',
          '</details>'
        );
      }
      if (r.tools) {
        parts.push(
          '',
          '<details>',
          `<summary>工具调用</summary>`,
          '',
          r.tools,
          '',
          '</details>'
        );
      }
      if (r.changes) {
        parts.push(
          '',
          '<details>',
          '<summary>文件变更</summary>',
          '',
          r.changes,
          '',
          '</details>'
        );
      }
      parts.push('', '---', '');
      return parts.join('\n');
    })
    .join('\n');

  const md = header + body;

  // 触发下载
  const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  const safeTitle = title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 40);
  a.download = `${safeTitle}-${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}.md`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/** 文件 tab 上只显示文件名，完整路径放 title 里 */
function baseName(p: string) {
  const i = p.lastIndexOf('/');
  return i >= 0 ? p.slice(i + 1) : p;
}

/** 本会话附件：已发送的 + 输入框里待发送的，按 id 去重 */
const attachments = computed(() => {
  const sent = chat.currentSessionId ? chat.sessionAttachments[chat.currentSessionId] || [] : [];
  const pending = chat.pendingChips
    .filter((c) => c.status === 'done' && c.id)
    .map((c) => ({ id: c.id as number, fileName: c.fileName }));
  const map = new Map<number, { id: number; fileName: string }>();
  [...sent, ...pending].forEach((a) => map.set(a.id, a));
  return [...map.values()];
});

/** 仍在待审查的改动：以 ChangeSet（或项目待审查列表）为准，已接受/已撤回的 diff 不再参与标色 */
const reviewChanges = computed<FileChange[]>(() => {
  const set = chat.changeSet;
  const items = set ? set.changes.filter((c) => c.status === 'PENDING') : chat.pendingChanges;
  return items.map((c) => ({
    path: c.filePath,
    action: (c.action as FileChange['action']) ?? 'modify',
    source: c.source ?? 'agent',
    additions: c.additions ?? 0,
    deletions: c.deletions ?? 0,
    diff: c.diffText ?? ''
  }));
});

/** 本会话全部文件变更：同一路径只保留最新一次 */
const changes = computed(() => {
  const map = new Map<string, FileChange>();
  chat.messages.forEach((m) => {
    (m.changes || []).forEach((c) => map.set(c.path, c));
  });
  return [...map.values()];
});

/** 最近一次提交的任务计划：运行中读实时快照，结束后只读最后一条助手消息 */
const latestPlan = computed<PlanSnapshot | null>(() => {
  // 运行中只认本次任务的实时快照，避免新任务开始时回落到上一个任务的计划
  if (chat.isProcessing) return chat.livePlan?.steps?.length ? chat.livePlan : null;
  // 结束后只认最后一条助手消息，更早的计划属于之前的任务
  const last = [...chat.messages].reverse().find((m) => m.role === 'assistant');
  return last?.plan?.steps?.length ? last.plan : null;
});

/** 计划完成进度：已完成数 / 总数 / 百分比；无计划时返回 0，模板可直接取值 */
const planProgress = computed(() => {
  const steps = latestPlan.value?.steps ?? [];
  const done = steps.filter((s) => s.status === 'completed').length;
  return {
    done,
    total: steps.length,
    percent: steps.length ? Math.round((done / steps.length) * 100) : 0
  };
});

function planStatusLabel(status: PlanStepStatus): string {
  const map: Record<PlanStepStatus, string> = {
    pending: '待开始',
    in_progress: '进行中',
    completed: '已完成',
    aborted: '已中止'
  };
  return map[status] ?? status;
}

/** 第五十六批：耗时口径拆分 computed——算 setup/tools/answer 百分比 */
const breakdownPercents = computed(() => {
  const bd = traceStats.value?.timeBreakdown;
  if (!bd) return null;
  const total = bd.setupMs + bd.toolsMs + bd.answerMs;
  if (total === 0) return null;
  return {
    setup: Math.round((bd.setupMs / total) * 100),
    tools: Math.round((bd.toolsMs / total) * 100),
    answer: Math.round((bd.answerMs / total) * 100),
    setupMs: bd.setupMs,
    toolsMs: bd.toolsMs,
    answerMs: bd.answerMs,
    totalMs: total,
    roundsWithSegments: bd.roundsWithSegments
  };
});

/** 某个文件对应的那次改动，交给编辑器做行内标色；只有仍待审查的改动才标 */
function changeOf(path: string): FileChange | null {
  return reviewChanges.value.find((c) => c.path === path) || null;
}

/** 从 hunk 头里取新文件的起始行，点变更时直接定位到改动处 */
function changeLine(c: FileChange): number | undefined {
  const m = /^@@ -\d+(?:,\d+)? \+(\d+)/m.exec(c.diff || '');
  return m ? Number(m[1]) : undefined;
}

function openChange(c: FileChange) {
  // 已删除的文件在编辑器里打不开，改动内容在审查流中可撤回
  if (c.action === 'delete') return;
  chat.openFile(c.path, changeLine(c));
}

function select(tab: 'changes' | 'docs' | 'file' | 'trace' | 'plan') {
  chat.openDock(tab);
}

/** 切到已经在标签栏里的文件 tab */
function selectFile(path: string) {
  chat.activateFile(path);
}

// 拖拽排序：拖动中记录源 tab，拖动经过另一个 tab 时记录插入点（左半区插前、右半区插后）
const dragPath = ref<string | null>(null);
const dropTarget = ref<{ path: string; after: boolean } | null>(null);

function onDragStart(e: DragEvent, path: string) {
  dragPath.value = path;
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move';
    // 某些浏览器要求设置数据才允许拖动
    e.dataTransfer.setData('text/plain', path);
  }
}

function onDragOver(e: DragEvent, path: string) {
  if (!dragPath.value || dragPath.value === path) return;
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
  dropTarget.value = { path, after: e.clientX > rect.left + rect.width / 2 };
}

function onDrop() {
  const from = dragPath.value;
  const target = dropTarget.value;
  if (from && target && from !== target.path) {
    chat.moveFile(from, target.path, target.after);
  }
  endDrag();
}

function endDrag() {
  dragPath.value = null;
  dropTarget.value = null;
}

/** 关闭某个文件 tab：这个文件有未保存改动时先确认 */
async function closeTab(path: string) {
  if (chat.fileDirty[path]) {
    try {
      await ElMessageBox.confirm('这个文件还有未保存的改动，关闭后会丢失。', '未保存的改动', {
        confirmButtonText: '放弃改动并关闭',
        cancelButtonText: '继续编辑',
        type: 'warning'
      });
    } catch {
      return;
    }
  }
  chat.closeFile(path);
}
</script>

<style scoped>
.workbench {
    flex-shrink: 0;
    /* 工作台与主侧边栏做色差分层：sidebar 用 --surface，workbench 用更深的底色
       这样收起侧边栏再展开工作台时，两个面板一眼能区分层级 */
    background: var(--workbench-bg, #0d0d0f);
    border-left: 1px solid var(--border);
    /* 面板换成底色后，原先「浮在浅色面板上」的 surface-2 层级改用 surface 才浮得起来，
       覆盖只在本面板内生效 */
    --surface-2: var(--surface);
    display: flex;
    flex-direction: column;
    min-height: 0;
    animation: wbIn var(--dur-base) ease;
}

@keyframes wbIn {
    from { opacity: 0; transform: translateX(8px); }
    to { opacity: 1; transform: translateX(0); }
}

.workbench.fullscreen {
    position: fixed;
    inset: 0 0 0 var(--sidebar-width);
    width: auto !important;
    z-index: 30;
    border-left: none;
}

/* 与左侧导航一致：全屏时 <768px 的侧栏是抽屉，不占位 */
@media (max-width: 767px) {
    .workbench.fullscreen { left: 0; }
}

/* 头部与顶栏同高同色：标签按钮和顶栏按钮处在同一条顶层，不画横线 */
.wb-header {
    height: var(--header-height);
    box-sizing: border-box;
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 0 6px 0 8px;
    background: var(--surface);
    flex-shrink: 0;
}

.wb-tabs {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 2px;
    overflow-x: auto;
    scrollbar-width: none;
}
.wb-tabs::-webkit-scrollbar { display: none; }

.wb-tab {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    height: 26px;
    padding: 0 9px;
    border: none;
    background: none;
    border-radius: var(--radius-sm);
    font-size: var(--fs-xs);
    color: var(--text-secondary);
    cursor: pointer;
    white-space: nowrap;
}
.wb-tab:hover { background: var(--surface-hover); color: var(--text); }
.wb-tab.active {
    background: var(--primary-light);
    color: var(--primary);
    font-weight: 600;
}
.wb-tab-count {
    font-size: 10px;
    opacity: .8;
}
.wb-tab-count.wb-tab-warn {
    color: var(--warning);
    opacity: 1;
    font-weight: 700;
}
.wb-tab-file {
    max-width: 200px;
    padding-right: 4px;
    cursor: grab;
    user-select: none;
}
/* 拖动中的源 tab 变淡；经过的 tab 用左右竖线提示插入位置 */
.wb-tab-file.dragging { opacity: .45; }
.wb-tab-file.drop-left { box-shadow: inset 2px 0 0 var(--primary); }
.wb-tab-file.drop-right { box-shadow: inset -2px 0 0 var(--primary); }
.wb-tab-file-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.wb-tab-dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--warning);
    flex-shrink: 0;
}
/* 关闭叉号：平时半透明，悬停/当前 tab 时变实，避免一排 tab 全是叉号太吵 */
.wb-tab-close {
    flex-shrink: 0;
    width: 14px;
    height: 14px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: 3px;
    color: var(--text-secondary);
    opacity: .55;
}
.wb-tab-file:hover .wb-tab-close,
.wb-tab-file.active .wb-tab-close { opacity: 1; }
.wb-tab-close:hover { background: var(--surface-hover); color: var(--text); }

.wb-icon-btn {
    flex-shrink: 0;
    width: 24px;
    height: 24px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: none;
    border-radius: var(--radius-sm);
    color: var(--text-secondary);
    cursor: pointer;
}
.wb-icon-btn:hover { background: var(--surface-hover); color: var(--text); }

.wb-body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
}

/* 编辑器容器：撑满工作台剩余高度，内部滚动交给 FileEditorPane */
.wb-file {
    flex: 1;
    min-height: 0;
    display: flex;
    overflow: hidden;
}

.wb-list {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 6px;
    display: flex;
    flex-direction: column;
    gap: 2px;
}

.wb-item,
.wb-change {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 100%;
    padding: 7px 8px;
    border: none;
    background: none;
    border-radius: var(--radius-sm);
    text-align: left;
    cursor: pointer;
    color: var(--text);
    font-size: var(--fs-sm);
}
.wb-item { cursor: default; }
.wb-change:hover { background: var(--surface-hover); }

.wb-item-icon { flex-shrink: 0; color: var(--text-secondary); }
.wb-item-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.wb-tag {
    flex-shrink: 0;
    font-size: 10px;
    padding: 0 4px;
    border-radius: var(--radius-sm);
    background: var(--surface-2);
    color: var(--text-secondary);
}
.wb-stat {
    flex-shrink: 0;
    font-size: 11px;
    display: flex;
    gap: 6px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.wb-add { color: var(--success); }
.wb-del { color: var(--danger); }

.wb-empty {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 6px;
    padding: 20px;
    color: var(--text-secondary);
    font-size: var(--fs-sm);
    text-align: center;
}
.wb-empty p { margin: 0; }
.wb-empty-sub { font-size: var(--fs-xs); opacity: .85; }

.wb-link {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 2px;
    margin: 0 8px 10px;
    padding: 6px;
    border-top: 1px solid var(--border);
    font-size: var(--fs-xs);
    color: var(--primary);
    text-decoration: none;
    flex-shrink: 0;
}
.wb-link:hover { text-decoration: underline; }

/* ——— 计划面板 ——— */
.plan-summary {
  padding: 9px 10px 8px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.plan-summary-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 7px;
}
.plan-summary-title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text);
}
.plan-summary-count {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
.plan-progress-track {
  height: 6px;
  border-radius: 3px;
  background: var(--surface-2);
  overflow: hidden;
}
.plan-progress-bar {
  height: 100%;
  background: var(--primary);
  border-radius: 3px;
  transition: width var(--dur-base) ease;
}
.plan-step {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-sm);
  color: var(--text);
}
.plan-step.is-completed .plan-step-title {
  color: var(--text-secondary);
  text-decoration: line-through;
}
.plan-step.is-in_progress {
  background: var(--primary-light);
}
.plan-step-mark {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
}
.plan-step.is-completed .plan-step-mark { color: var(--success); }
.plan-step-index {
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.plan-step-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary);
  animation: planPulse 1.2s ease-in-out infinite;
}
@keyframes planPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}
.plan-step-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.plan-step-status {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--text-secondary);
}
.plan-step.is-in_progress .plan-step-status { color: var(--primary); }
.plan-step.is-completed .plan-step-status { color: var(--success); }

/* ——— 第五十四批：追踪面板样式 ——— */
/* 运行详情：默认收起，日常只看时间线 */
.trace-details {
  margin: 10px 8px 12px;
  border-top: 1px solid var(--border);
  padding-top: 6px;
}
.trace-details > summary {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  font-size: 10px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  list-style: none;
  padding: 4px 0;
}
.trace-details > summary::-webkit-details-marker { display: none; }
.trace-details > summary::before {
  content: '›';
  font-size: 12px;
  line-height: 1;
  transition: transform .15s;
}
.trace-details[open] > summary::before { transform: rotate(90deg); }
.trace-details-hint {
  font-weight: 400;
  text-transform: none;
  letter-spacing: 0;
  color: var(--text-tertiary);
}
.trace-overview-wrapper {
  position: relative;
  padding-top: 24px; /* 给导出按钮留空间 */
}
.trace-export-btn {
  position: absolute;
  top: 4px;
  right: 8px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 7px;
  border: none;
  background: var(--surface-2);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  font-size: 10px;
  font-weight: 500;
  cursor: pointer;
  z-index: 1;
}
.trace-export-btn:hover:not(:disabled) { background: var(--surface-hover); color: var(--text); }
.trace-export-btn:disabled { opacity: .4; cursor: not-allowed; }

.trace-overview {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  padding: 8px;
  flex-shrink: 0;
}
.trace-card {
  background: var(--surface-2);
  border-radius: var(--radius-sm);
  padding: 8px 6px;
  text-align: center;
  min-width: 0;
}
.trace-card-wide {
  grid-column: span 3;
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  padding: 6px 8px;
}
.trace-card-meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: center;
}
.trace-meta-pill {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 600;
  letter-spacing: 0.02em;
  background: var(--surface-hover);
  color: var(--text-secondary);
}
.trace-meta-pill.perm-read_only { background: color-mix(in srgb, var(--danger) 12%, transparent); color: var(--danger); }
.trace-meta-pill.perm-write { background: color-mix(in srgb, var(--warning) 12%, transparent); color: var(--warning); }
.trace-meta-pill.perm-execute { background: color-mix(in srgb, var(--success) 12%, transparent); color: var(--success); }

.trace-card-val {
  font-size: 16px;
  font-weight: 700;
  color: var(--primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  line-height: 1.2;
}
.trace-card.bad .trace-card-val { color: var(--danger); }
.trace-card-label {
  font-size: 10px;
  color: var(--text-secondary);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ——— 第五十六批：耗时口径拆分 ——— */
.trace-breakdown {
  padding: 0 8px 6px;
  border-bottom: 1px solid var(--border);
}
.trace-breakdown-track {
  display: flex;
  height: 10px;
  border-radius: 5px;
  overflow: hidden;
  background: var(--surface-2);
  gap: 1px;
}
.trace-breakdown-bar {
  height: 100%;
  min-width: 0;
  transition: width 0.3s ease;
}
.trace-breakdown-bar.bar-setup { background: var(--primary); }
.trace-breakdown-bar.bar-tools { background: var(--warning); }
.trace-breakdown-bar.bar-answer { background: var(--success); }

.trace-breakdown-labels {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 6px;
  font-size: 10px;
}
.trace-bl-item {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--text-secondary);
}
.trace-bl-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.trace-bl-dot.dot-setup { background: var(--primary); }
.trace-bl-dot.dot-tools { background: var(--warning); }
.trace-bl-dot.dot-answer { background: var(--success); }
.trace-bl-name { color: var(--text-secondary); }
.trace-bl-val { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: var(--text); }
.trace-bl-pct { color: var(--text-secondary); opacity: .75; }
.trace-bl-sub {
  margin-left: auto;
  color: var(--text-secondary);
  opacity: .7;
}

.trace-section {
  padding: 8px;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.trace-section-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  flex-shrink: 0;
}

.wb-empty-inline {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  padding: 4px 0;
}

/* P5-12-4：追踪面板顶部的待审批区 */
.trace-approvals {
  margin: 2px 0 8px;
  padding: 6px 8px;
  border: 1px solid color-mix(in srgb, var(--warning) 45%, transparent);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--warning) 8%, transparent);
}
/* P5-10-2：审批历史用中性面板，和黄色待审批区区分 */
.trace-approval-history {
  border-color: var(--border);
  background: var(--surface-2);
}
.trace-history-loading {
  margin-left: 6px;
}
.trace-history-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  padding: 4px 6px;
  border-radius: var(--radius-sm);
  background: var(--surface-3);
  font-size: var(--fs-xs);
}
.trace-history-tool {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trace-history-state.ok { color: var(--success); }
.trace-history-state.no { color: var(--danger); }
.trace-history-source { color: var(--text-secondary); }
.trace-history-remember {
  padding: 0 4px;
  border-radius: 3px;
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 12%, transparent);
}
.trace-history-time {
  margin-left: auto;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}
.his-ok { color: var(--success); }
.his-no { color: var(--danger); }
.trace-approvals-hint {
  margin-left: 6px;
  font-size: 10px;
  font-weight: 400;
  color: var(--text-tertiary);
  text-transform: none;
  letter-spacing: 0;
}
.trace-approval-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin-top: 4px;
  padding: 4px 6px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text);
  font-size: var(--fs-xs);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  text-align: left;
  cursor: pointer;
}
.trace-approval-row:hover { background: var(--surface-hover); }
.trace-approval-icon { color: var(--warning); flex-shrink: 0; }
.trace-approval-tool {
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trace-approval-path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-secondary);
}
.trace-approval-go {
  flex-shrink: 0;
  color: var(--warning);
  font-weight: 600;
}

/* 时间行 */
.trace-tool-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-xs);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.trace-tool-row:hover { background: var(--surface-hover); }
.trace-tool-row.is-clickable { cursor: pointer; }
.trace-tool-row.expanded { background: var(--surface-2); }
.trace-tool-row.is-pending {
  background: color-mix(in srgb, var(--warning) 12%, transparent);
  box-shadow: inset 2px 0 0 var(--warning);
}
.trace-tool-path {
  flex-shrink: 1;
  min-width: 0;
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-secondary);
}
.trace-tool-pending {
  flex-shrink: 0;
  font-size: 9px;
  font-weight: 600;
  padding: 1px 4px;
  border-radius: 3px;
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 16%, transparent);
}
.trace-tool-stat {
  flex-shrink: 0;
  display: flex;
  gap: 4px;
  font-variant-numeric: tabular-nums;
}
.trace-row-diff { margin: 2px 0 6px 14px; }
.trace-diff-empty {
  font-size: 10px;
  color: var(--text-tertiary);
  padding: 4px 0 6px;
}

.trace-tool-source {
  flex-shrink: 0;
  font-size: 9px;
  padding: 1px 4px;
  border-radius: 3px;
  font-weight: 600;
  letter-spacing: 0.03em;
}
.trace-tool-source.src-builtin {
  background: rgba(64, 158, 255, 0.12);
  color: var(--primary);
}
.trace-tool-source.src-mcp {
  background: rgba(167, 139, 250, 0.15);
  color: var(--accent);
}

.trace-tool-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
}
.trace-tool-status {
  flex-shrink: 0;
  font-weight: 700;
  width: 14px;
  text-align: center;
}
.trace-tool-status.success { color: var(--success); }
.trace-tool-status.error { color: var(--danger); }
.trace-tool-status.running { color: var(--warning); }
.trace-tool-cost {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

/* 工具按名称聚合 */
.trace-toolstat {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 6px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-xs);
}
.trace-toolstat:hover { background: var(--surface-hover); }
.trace-toolstat-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.trace-toolstat-calls {
  flex-shrink: 0;
  color: var(--text-secondary);
}
.trace-toolstat-success {
  flex-shrink: 0;
  color: var(--success);
  font-weight: 600;
}
.trace-toolstat-fail {
  flex-shrink: 0;
  color: var(--danger);
  font-weight: 600;
}
.trace-toolstat-rate {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--surface-2);
}
.trace-toolstat-rate.bad { color: var(--danger); background: rgba(245, 108, 108, 0.1); }
.trace-toolstat-avg {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
.trace-toolstat-p95 {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
  opacity: .85;
}
.trace-toolstat-max {
  flex-shrink: 0;
  color: var(--warning);
  font-variant-numeric: tabular-nums;
  opacity: .85;
}

/* 文件变更聚合 */
.trace-filestat {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 6px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-xs);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  cursor: pointer;
  transition: background 0.15s;
}
.trace-filestat:hover { background: var(--surface-hover); }
.trace-filestat.expanded { background: var(--surface-hover); }
.trace-filestat-chev { flex-shrink: 0; color: var(--text-secondary); }

/* diff 预览展开面板 */
.trace-diff-preview {
  margin: 2px 4px 6px;
  padding: 6px 8px;
  background: var(--surface-2);
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
}
.trace-diff-header {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  font-weight: 600;
  color: var(--text);
  padding-bottom: 4px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 4px;
}
.trace-diff-source {
  color: var(--text-secondary);
  font-weight: 400;
}
.trace-diff-close {
  margin-left: auto;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 12px;
  padding: 0 3px;
  border-radius: 3px;
}
.trace-diff-close:hover { background: var(--danger); color: var(--on-primary); }
.trace-diff-code {
  max-height: 200px;
  overflow: auto;
  font-size: 10px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  line-height: 1.4;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text);
  margin: 0 0 6px;
  padding: 4px;
  background: var(--bg);
  border-radius: 3px;
}
.trace-diff-open {
  width: 100%;
  padding: 4px 6px;
  border: none;
  background: var(--primary);
  color: var(--on-primary);
  border-radius: var(--radius-sm);
  font-size: 10px;
  font-weight: 600;
  cursor: pointer;
}
.trace-filestat-path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
}

.wb-spin { animation: spin 1s linear infinite; }
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ——— 第五十五批：MCP Server 健康状态 ——— */
.trace-refresh-btn {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border: none;
  background: none;
  border-radius: 3px;
  color: var(--text-secondary);
  cursor: pointer;
}
.trace-refresh-btn:hover { background: var(--surface-hover); color: var(--text); }

.mcp-server-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.mcp-server {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-xs);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.mcp-server:hover { background: var(--surface-hover); }
.mcp-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-secondary);
}
.mcp-dot.dot-up { background: var(--success); box-shadow: 0 0 4px var(--success); }
.mcp-dot.dot-down { background: var(--danger); box-shadow: 0 0 4px var(--danger); }
.mcp-dot.dot-unknown { background: var(--text-secondary); }
.mcp-server-name {
  flex: 0 0 auto;
  color: var(--text);
  font-weight: 600;
}
.mcp-server-status {
  flex-shrink: 0;
  font-size: 10px;
  padding: 0 5px;
  border-radius: 3px;
  font-weight: 600;
}
.mcp-server-status.mcp-up { background: color-mix(in srgb, var(--success) 12%, transparent); color: var(--success); }
.mcp-server-status.mcp-down { background: color-mix(in srgb, var(--danger) 12%, transparent); color: var(--danger); }
.mcp-server-status.mcp-unknown { background: var(--surface-hover); color: var(--text-secondary); }
.mcp-server-fail {
  flex-shrink: 0;
  color: var(--warning);
  font-size: 10px;
}
.mcp-server-error {
  flex-shrink: 0;
  color: var(--danger);
  font-size: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 50%;
}


</style>
