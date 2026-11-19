<template>
  <div class="diagnostics-panel">
    <div class="diag-card">
      <div class="diag-card-head">
        <div>
          <h3><Icon name="shield" :size="15" /> 本地采集</h3>
          <p class="diag-subtitle">{{ enabled ? '正在记录当前浏览器中的白名单诊断数据' : '已停止新采集，已有记录仍可查看' }}</p>
        </div>
        <el-switch
          v-model="enabled"
          aria-label="本地体验采集"
          data-testid="diagnostics-enabled"
          @change="onToggle"
        />
      </div>
      <p class="privacy-note">
        数据只保存在当前浏览器，不会自动上传。异常仅保留脱敏摘要，不保存对话、工具参数、原始堆栈、API Key 或完整 URL 查询串。
        切换账号会清空本地 Run 和会话标识。
      </p>
      <div class="diag-actions">
        <button class="diag-btn" data-testid="diagnostics-export" @click="exportDiagnostics">
          <Icon name="download" :size="13" /> 导出诊断 JSON
        </button>
        <button class="diag-btn danger" data-testid="diagnostics-clear" @click="clearRecords">
          <Icon name="trash" :size="13" /> 清除记录
        </button>
        <span class="updated-at">最后更新：{{ formatTime(data.updatedAt) }}</span>
      </div>
    </div>

    <div class="diag-card">
      <div class="diag-card-head">
        <div>
          <h3><Icon name="clock" :size="15" /> 页面体验</h3>
          <p class="diag-subtitle">标准 Web Vitals · 当前浏览器样本</p>
        </div>
        <el-radio-group v-model="sessionFilter" size="small" aria-label="Web Vitals 会话筛选">
          <el-radio-button value="all">全部会话</el-radio-button>
          <el-radio-button value="current" :disabled="chat.currentSessionId == null">当前会话</el-radio-button>
        </el-radio-group>
      </div>
      <div class="metric-grid three">
        <div v-for="name in vitalNames" :key="name" class="metric-item">
          <span class="metric-label">{{ name }}</span>
          <strong class="metric-value">{{ formatVital(name, vitals[name].value) }}</strong>
          <span class="metric-sample">{{ vitals[name].sampleCount }} 个样本</span>
        </div>
      </div>
    </div>

    <div class="diag-card">
      <h3><Icon name="refresh" :size="15" /> 连接体验</h3>
      <p class="diag-subtitle">来自当前浏览器的 Run 与断线恢复记录，不代表全平台可用性</p>
      <div class="metric-grid four">
        <div class="metric-item">
          <span class="metric-label">流中断率</span>
          <strong class="metric-value">{{ formatPercent(connection.interruptionRate) }}</strong>
          <span class="metric-sample">{{ connection.interruptedRunCount }} / {{ connection.runCount }} 个 Run</span>
        </div>
        <div class="metric-item">
          <span class="metric-label">恢复成功率</span>
          <strong class="metric-value">{{ formatPercent(connection.recoverySuccessRate) }}</strong>
          <span class="metric-sample">{{ connection.recoveredCount }} / {{ connection.recoveryAttempts }} 次终态记录</span>
        </div>
        <div class="metric-item">
          <span class="metric-label">恢复次数</span>
          <strong class="metric-value">{{ connection.recoveryAttempts || '—' }}</strong>
          <span class="metric-sample">已有终态的恢复</span>
        </div>
        <div class="metric-item">
          <span class="metric-label">平均恢复耗时</span>
          <strong class="metric-value">{{ formatMs(connection.averageRecoveryMs) }}</strong>
          <span class="metric-sample">从接管到恢复终态</span>
        </div>
      </div>
    </div>

    <div v-if="showAcceptance" class="diag-card" data-testid="agent-experience-metrics">
      <div class="diag-card-head">
        <div>
          <h3><Icon name="cpu" :size="15" /> Agent 体验</h3>
          <p class="diag-subtitle">当前账号既有审计数据 · 最近最多 {{ agentMetrics?.windowRuns ?? 100 }} 个 Run</p>
        </div>
        <button class="diag-btn compact" :disabled="metricsLoading" @click="loadAgentMetrics">
          <Icon name="refresh" :size="13" /> {{ metricsLoading ? '加载中…' : '刷新' }}
        </button>
      </div>
      <p v-if="metricsError" class="metrics-error" role="alert">
        后端指标加载失败，本地诊断仍可使用。
        <button class="retry-link" @click="loadAgentMetrics">重试</button>
      </p>
      <div class="metric-grid four">
        <div class="metric-item">
          <span class="metric-label">首字时延 p95</span>
          <strong class="metric-value">{{ formatMs(agentMetrics?.firstToken.p95Ms ?? null) }}</strong>
          <span class="metric-sample">{{ agentMetrics?.firstToken.sampleCount ?? 0 }} 个 Run</span>
        </div>
        <div class="metric-item">
          <span class="metric-label">工具失败率</span>
          <strong class="metric-value">{{ formatPercent(agentMetrics?.tool.failureRate ?? null) }}</strong>
          <span class="metric-sample">{{ agentMetrics?.tool.totalCount ?? 0 }} 次调用 · 进程累计</span>
        </div>
        <div class="metric-item">
          <span class="metric-label">审批平均等待</span>
          <strong class="metric-value">{{ formatMs(agentMetrics?.approval.averageWaitMs ?? null) }}</strong>
          <span class="metric-sample">{{ agentMetrics?.approval.sampleCount ?? 0 }} 条已决策审批</span>
        </div>
        <div class="metric-item">
          <span class="metric-label">每轮平均 token</span>
          <strong class="metric-value">{{ formatNumber(agentMetrics?.tokens.averageTotalTokens ?? null) }}</strong>
          <span class="metric-sample">
            {{ agentMetrics?.tokens.sampleCount ?? 0 }} 个 Run · 总计 {{ formatNumber(agentMetrics?.tokens.totalTokens ?? 0) }}
          </span>
        </div>
      </div>
      <div v-if="approvalEntries.length" class="entry-table" data-testid="approval-entry-metrics">
        <div class="entry-row entry-head">
          <span>审批入口</span>
          <span>审批量</span>
          <span>批准率</span>
          <span>平均耗时</span>
        </div>
        <div
          v-for="item in approvalEntries"
          :key="`${item.entry}-${item.clientId ?? ''}`"
          class="entry-row"
        >
          <span class="entry-name" :title="item.clientId ?? undefined">{{ entryLabel(item) }}</span>
          <span>{{ item.totalCount }}</span>
          <span>{{ formatPercent(item.approvalRate) }}</span>
          <span>{{ formatMs(item.averageDecisionMs) }}</span>
        </div>
      </div>
      <p v-else-if="agentMetrics" class="diag-footnote">暂无已决策审批</p>
      <p v-if="agentMetrics" class="diag-footnote">生成时间：{{ formatTime(agentMetrics.generatedAt) }}</p>
    </div>

    <div class="diag-card" data-testid="diagnostics-errors">
      <h3><Icon name="alert" :size="15" /> 最近异常</h3>
      <p class="diag-subtitle">最多保留 50 条，仅显示已脱敏的单行摘要</p>
      <div v-if="recentErrors.length" class="error-list">
        <div v-for="item in recentErrors" :key="item.id" class="error-item">
          <div class="error-meta">
            <span class="error-kind" :class="item.kind">{{ kindLabel(item.kind) }}</span>
            <time>{{ formatTime(item.timestamp) }}</time>
            <span>{{ item.sessionId == null ? '无会话' : `会话 #${item.sessionId}` }}</span>
          </div>
          <p class="error-message">{{ item.message }}</p>
        </div>
      </div>
      <div v-else class="empty-errors">暂无本地异常记录</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import * as agentApi from '../api/agent';
import { useChatStore } from '../stores/chat';
import type { AgentApprovalEntryMetrics, AgentExperienceMetrics } from '../types/api';
import type { ExperienceErrorKind, WebVitalName } from '../utils/experienceDiagnostics';
import {
  clearExperienceDiagnostics,
  createExperienceDiagnosticsExport,
  getExperienceConnectionMetrics,
  getExperienceDiagnostics,
  getExperienceWebVitals,
  isExperienceDiagnosticsEnabled,
  setExperienceDiagnosticsEnabled,
  subscribeExperienceDiagnostics
} from '../utils/experienceDiagnostics';
import Icon from './Icon.vue';

const chat = useChatStore();
const enabled = ref(isExperienceDiagnosticsEnabled());
const data = ref(getExperienceDiagnostics());
const sessionFilter = ref<'all' | 'current'>('all');
/** 验收指标默认展示；设置页默认收起，由外层按开发者意图决定是否展示 */
withDefaults(defineProps<{ showAcceptance?: boolean }>(), { showAcceptance: true });

const agentMetrics = ref<AgentExperienceMetrics | null>(null);
const metricsLoading = ref(false);
const metricsError = ref(false);
const vitalNames: WebVitalName[] = ['LCP', 'INP', 'CLS'];
let unsubscribe: (() => void) | undefined;

const vitals = computed(() => {
  void data.value.updatedAt;
  return getExperienceWebVitals(sessionFilter.value === 'current' ? chat.currentSessionId : null);
});
const connection = computed(() => {
  void data.value.updatedAt;
  return getExperienceConnectionMetrics();
});
const recentErrors = computed(() => [...data.value.errors].reverse());
const approvalEntries = computed(() => agentMetrics.value?.approval.byEntry ?? []);

function entryLabel(item: AgentApprovalEntryMetrics): string {
  if (item.entry === 'WEB') return '工作台';
  if (item.entry === 'CLIENT') return item.clientId ? `客户端 · ${item.clientId}` : '客户端';
  if (item.entry === 'AUTO') return '自动';
  if (item.entry === 'UNKNOWN') return '来源未知';
  return item.entry;
}

function refreshLocal() {
  data.value = getExperienceDiagnostics();
}

async function loadAgentMetrics() {
  metricsLoading.value = true;
  metricsError.value = false;
  try {
    agentMetrics.value = await agentApi.getExperienceMetrics();
  } catch {
    metricsError.value = true;
  } finally {
    metricsLoading.value = false;
  }
}

function onToggle() {
  setExperienceDiagnosticsEnabled(enabled.value);
  refreshLocal();
  if (enabled.value && !agentMetrics.value) void loadAgentMetrics();
}

function clearRecords() {
  clearExperienceDiagnostics();
  refreshLocal();
  ElMessage.success('本地体验诊断记录已清除');
}

function exportDiagnostics() {
  const content = JSON.stringify(createExperienceDiagnosticsExport(agentMetrics.value), null, 2);
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

function formatMs(value: number | null): string {
  return value == null ? '—' : `${Math.round(value).toLocaleString('zh-CN')} ms`;
}

function formatPercent(value: number | null): string {
  return value == null ? '—' : `${(value * 100).toFixed(1)}%`;
}

function formatNumber(value: number | null): string {
  if (value == null) return '—';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 1 });
}

function formatVital(name: WebVitalName, value: number | null): string {
  if (value == null) return '—';
  return name === 'CLS' ? value.toFixed(3) : `${Math.round(value).toLocaleString('zh-CN')} ms`;
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN', { hour12: false }) : '—';
}

function kindLabel(kind: ExperienceErrorKind): string {
  if (kind === 'vue') return 'Vue 渲染';
  if (kind === 'unhandledrejection') return 'Promise 拒绝';
  return '浏览器异常';
}

onMounted(() => {
  unsubscribe = subscribeExperienceDiagnostics(refreshLocal);
  if (enabled.value) void loadAgentMetrics();
});

onBeforeUnmount(() => unsubscribe?.());
</script>

<style scoped>
.diagnostics-panel { display: flex; flex-direction: column; gap: 14px; }
.diag-card {
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface);
}
.diag-card h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
}
.diag-card > h3 { margin-bottom: 4px; }
.diag-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.diag-subtitle { margin: 5px 0 0; color: var(--text-tertiary); font-size: 11px; line-height: 1.5; }
.privacy-note { margin: 14px 0; color: var(--text-secondary); font-size: 12px; line-height: 1.65; }
.diag-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.diag-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.diag-btn:hover { background: var(--surface-hover); color: var(--text-primary); }
.diag-btn:disabled { opacity: .55; cursor: default; }
.diag-btn.danger { color: var(--danger); }
.diag-btn.compact { flex-shrink: 0; padding: 4px 9px; }
.updated-at { margin-left: auto; color: var(--text-tertiary); font-size: 11px; }
.metric-grid { display: grid; gap: 10px; margin-top: 15px; }
.metric-grid.three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.metric-grid.four { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.metric-item {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
  padding: 12px;
  border-radius: 8px;
  background: var(--surface-2);
}
.metric-label { color: var(--text-secondary); font-size: 11px; }
.metric-value { color: var(--text-primary); font-size: 20px; font-weight: 650; line-height: 1.2; }
.metric-sample { color: var(--text-tertiary); font-size: 10px; line-height: 1.45; }
.metrics-error { margin: 12px 0 0; color: var(--danger); font-size: 12px; }
.entry-table { margin-top: 14px; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.entry-row {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) repeat(3, minmax(0, .8fr));
  align-items: center;
  gap: 10px;
  padding: 7px 12px;
  color: var(--text-secondary);
  font-size: 11px;
}
.entry-row + .entry-row { border-top: 1px solid var(--border); }
.entry-head { background: var(--surface-2); color: var(--text-tertiary); font-size: 10px; }
.entry-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.entry-row span:not(.entry-name) { text-align: right; font-variant-numeric: tabular-nums; }
.retry-link { padding: 0; border: 0; background: none; color: var(--primary); font: inherit; cursor: pointer; }
.diag-footnote { margin: 10px 0 0; color: var(--text-tertiary); font-size: 10px; text-align: right; }
.error-list { display: flex; flex-direction: column; gap: 8px; margin-top: 14px; }
.error-item { padding: 10px 12px; border-radius: 8px; background: var(--surface-2); }
.error-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; color: var(--text-tertiary); font-size: 10px; }
.error-kind { color: var(--warning); font-weight: 600; }
.error-kind.unhandledrejection, .error-kind.vue { color: var(--danger); }
.error-message { margin: 6px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.empty-errors { padding: 24px 0 10px; color: var(--text-tertiary); font-size: 12px; text-align: center; }
@media (max-width: 900px) {
  .metric-grid.four { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 600px) {
  .metric-grid.three, .metric-grid.four { grid-template-columns: 1fr; }
  .diag-card-head { flex-direction: column; }
  .updated-at { width: 100%; margin-left: 0; }
}
</style>
