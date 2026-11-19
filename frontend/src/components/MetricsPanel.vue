<script setup lang="ts">
/**
 * 全局工具调用监控面板。
 *
 * 属于开发者视角：从工作台的一级标签移到「设置 → 应用信息 → 高级诊断」，
 * 避免普通用户在日常使用里被 P95、成功率这类指标干扰。
 */
import { computed, onMounted, ref } from 'vue';
import Icon from './Icon.vue';
import * as agentApi from '../api/agent';

const loading = ref(false);
const error = ref<string | null>(null);
const data = ref<any>(null);

/** 毫秒格式化：<1s 显示 ms，>=1s 显示秒 */
function fmtMs(ms: number | undefined): string {
  if (!ms || ms <= 0) return '0ms';
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  return Math.floor(ms / 60000) + 'm' + Math.floor((ms % 60000) / 1000) + 's';
}

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

async function fetchMetrics() {
  loading.value = true;
  error.value = null;
  try {
    data.value = await agentApi.getMetrics();
  } catch (e: any) {
    error.value = e?.message || '请求失败';
    data.value = null;
  } finally {
    loading.value = false;
  }
}

/** 工具列表按调用次数降序；P95 为 null 时排除（避免 / 0） */
const toolsList = computed(() => {
  const tools = data.value?.tools;
  if (!tools || typeof tools !== 'object') return [];
  return Object.entries(tools)
    .map(([name, v]: [string, any]) => ({
      name,
      calls: Number(v?.calls ?? 0),
      successRate: Number(v?.successRate ?? 0),
      p95Ms: Number(v?.p95Ms ?? 0),
      p99Ms: Number(v?.p99Ms ?? 0),
      sources: Array.from(v?.sources ?? [])
    }))
    .sort((a, b) => b.calls - a.calls);
});

/** 加权 P95 —— 按调用次数加权平均各工具的 P95（展示级摘要，不用精确百分位合并算法） */
const weightedP95 = computed(() => {
  let totalCalls = 0;
  let weighted = 0;
  for (const t of toolsList.value) {
    if (t.p95Ms > 0) {
      totalCalls += t.calls;
      weighted += t.p95Ms * t.calls;
    }
  }
  if (totalCalls === 0) return '—';
  return fmtMs(Math.round(weighted / totalCalls));
});

onMounted(fetchMetrics);
</script>

<template>
  <div class="metrics-panel">
    <div class="metrics-head">
      <span class="metrics-title">工具调用监控（全局）</span>
      <span v-if="data?.generatedAt" class="metrics-generated">更新于 {{ fmtTime(data.generatedAt) }}</span>
      <button class="metrics-refresh" :disabled="loading" title="刷新" @click="fetchMetrics">
        <Icon :name="loading ? 'loader' : 'refresh'" :size="12" :class="{ spin: loading }" />
      </button>
    </div>

    <div v-if="loading && !data" class="metrics-empty">加载监控数据…</div>
    <div v-else-if="error" class="metrics-empty">
      加载失败
      <span class="metrics-empty-sub">{{ error }}</span>
    </div>
    <div v-else-if="!data" class="metrics-empty">
      暂无监控数据
      <span class="metrics-empty-sub">后端还没记录到任何工具调用</span>
    </div>
    <template v-else>
      <div class="metrics-overview">
        <div class="trace-card">
          <div class="trace-card-val">{{ data.summary?.totalCalls ?? 0 }}</div>
          <div class="trace-card-label">总调用</div>
        </div>
        <div class="trace-card">
          <div class="trace-card-val">{{ data.summary?.uniqueTools ?? 0 }}</div>
          <div class="trace-card-label">工具种类</div>
        </div>
        <div class="trace-card" :class="{ bad: (data.summary?.successRate ?? 100) < 90 }">
          <div class="trace-card-val">{{ data.summary?.successRate ?? 0 }}%</div>
          <div class="trace-card-label">成功率</div>
        </div>
        <div class="trace-card">
          <div class="trace-card-val">{{ data.summary?.successCalls ?? 0 }}</div>
          <div class="trace-card-label">成功</div>
        </div>
        <div class="trace-card">
          <div class="trace-card-val">{{ data.summary?.failCalls ?? 0 }}</div>
          <div class="trace-card-label">失败</div>
        </div>
        <div class="trace-card">
          <div class="trace-card-val">{{ weightedP95 }}</div>
          <div class="trace-card-label">加权 P95</div>
        </div>
      </div>

      <div v-if="toolsList.length" class="metrics-table">
        <div class="metrics-table-header">
          <span>工具</span>
          <span>调用</span>
          <span>成功率</span>
          <span>P95</span>
          <span>P99</span>
        </div>
        <div v-for="t in toolsList" :key="t.name" class="metrics-row">
          <span class="metrics-toolname" :title="`来源: ${(t.sources || []).join(', ') || 'builtin'}`">{{ t.name }}</span>
          <span class="metrics-num">{{ t.calls }}</span>
          <span class="metrics-rate" :class="{ bad: t.successRate < 90 && t.calls >= 3 }">{{ t.successRate }}%</span>
          <span class="metrics-num">{{ fmtMs(t.p95Ms) }}</span>
          <span class="metrics-num">{{ fmtMs(t.p99Ms) }}</span>
        </div>
      </div>
      <div v-else class="metrics-empty-inline">暂无工具调用记录</div>
    </template>
  </div>
</template>

<style scoped>
.metrics-panel {
  border-top: 1px solid var(--border);
  margin-top: 12px;
  padding-top: 10px;
}
.metrics-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.metrics-title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text);
}
.metrics-generated {
  font-size: 11px;
  color: var(--text-secondary);
}
.metrics-refresh {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}
.metrics-refresh:hover { background: var(--surface-hover); color: var(--text); }
.metrics-refresh:disabled { opacity: .6; cursor: default; }
.spin { animation: metrics-spin 1s linear infinite; }
@keyframes metrics-spin { to { transform: rotate(360deg); } }

.metrics-overview {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
}
.trace-card {
  background: var(--surface-2);
  border-radius: var(--radius-sm);
  padding: 8px 6px;
  text-align: center;
  min-width: 0;
}
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

.metrics-table {
  margin-top: 12px;
  border-top: 1px solid var(--border);
}
.metrics-table-header {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 8px 6px 4px;
}
.metrics-table-header > span:first-child { flex: 1; min-width: 0; }
.metrics-table-header > span { flex-shrink: 0; width: 64px; text-align: right; }

.metrics-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 6px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-xs);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.metrics-row:hover { background: var(--surface-hover); }
.metrics-toolname {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
  font-weight: 500;
}
.metrics-num {
  flex-shrink: 0;
  width: 64px;
  text-align: right;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
.metrics-rate {
  flex-shrink: 0;
  width: 64px;
  text-align: right;
  color: var(--success);
  font-weight: 600;
}
.metrics-rate.bad { color: var(--danger); }

.metrics-empty {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 16px 4px;
  color: var(--text-secondary);
  font-size: var(--fs-xs);
}
.metrics-empty-sub { color: var(--text-tertiary); }
.metrics-empty-inline {
  padding: 16px 4px;
  color: var(--text-secondary);
  font-size: var(--fs-xs);
}
</style>
