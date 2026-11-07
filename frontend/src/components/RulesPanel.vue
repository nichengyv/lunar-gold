<template>
  <div class="rules-panel">
    <div class="rules-header">
      <div class="rules-title">Agent 指令</div>
    </div>

    <!-- 三层分组 -->
    <div v-for="group in groups" :key="group.scope" class="rules-group">
      <div class="rules-group-header">
        <span class="rules-group-title">{{ group.label }}</span>
        <span class="rules-group-count">{{ group.enabledCount }}/{{ group.rules.length }}</span>
      </div>

      <div v-if="group.rules.length === 0" class="rules-empty-group">
        暂无规则
        <button v-if="!isLoading" class="rules-add-mini" @click="onCreate(group.scope)">+ 新建</button>
      </div>

      <div v-else class="rules-list">
        <div
          v-for="r in group.rules"
          :key="r.id"
          class="rule-card"
          :class="{ builtin: r.builtin }"
        >
          <!-- 开关 -->
          <div class="rule-toggle" :class="{ on: r.enabled }" @click="toggle(r)">
            <div class="rule-toggle-thumb"></div>
          </div>

          <!-- 内容 -->
          <div class="rule-body" @dblclick="onEdit(r)">
            <div class="rule-name">
              {{ r.name }}
              <span v-if="r.builtin" class="rule-badge">内置</span>
            </div>
            <div v-if="r.description" class="rule-desc">{{ r.description }}</div>
            <div class="rule-content-preview">{{ truncate(r.content, 80) }}</div>
          </div>

          <!-- 操作按钮 -->
          <div class="rule-actions">
            <button class="rule-action-btn" title="编辑" @click="onEdit(r)">
              <Icon name="edit-2" :size="12" />
            </button>
            <button
              v-if="!r.builtin"
              class="rule-action-btn danger"
              title="删除"
              @click="onDelete(r)"
            >
              <Icon name="trash-2" :size="12" />
            </button>
          </div>
        </div>

        <!-- 新建按钮 -->
        <button class="rule-card rule-add-card" @click="onCreate(group.scope)">
          <Icon name="plus" :size="14" />
          <span>新建{{ group.label }}规则</span>
        </button>
      </div>
    </div>

    <div v-if="isLoading" class="rules-loading">加载中…</div>
  </div>
</template>

<script setup lang="ts">
/**
 * Agent 规则 / Instruction 面板（对标 Cursor .cursorrules + Trae @Rules + Copilot System Prompt）
 *
 * 三层 scope：
 *   GLOBAL  — 用户级，所有项目所有会话生效
 *   PROJECT — 项目级，某个项目下所有会话生效
 *   SESSION — 会话级，仅当前会话生效
 *
 * 规则 enabled=true 的会在 ReActAgentService.buildMessages() 时按 GLOBAL→PROJECT→SESSION
 * 顺序拼进 system prompt 最前面（后者权重更高，可覆盖前者）。
 */
import { computed, onMounted, ref, watch } from 'vue';
import Icon from './Icon.vue';
import { useChatStore } from '../stores/chat';
import { useProjectStore } from '../stores/project';
import * as agentApi from '../api/agent';
import type { AgentRule } from '../api/agent';

const chat = useChatStore();
const projectStore = useProjectStore();

const isLoading = ref(false);

// 三层规则独立存
const globalRules = ref<AgentRule[]>([]);
const projectRules = ref<AgentRule[]>([]);
const sessionRules = ref<AgentRule[]>([]);

// 计算分组（enabled 在前）
const groups = computed(() => [
  {
    scope: 'GLOBAL' as const,
    label: '全局',
    rules: sorted(globalRules.value),
    enabledCount: globalRules.value.filter(r => r.enabled).length,
  },
  {
    scope: 'PROJECT' as const,
    label: '当前项目',
    rules: sorted(projectRules.value),
    enabledCount: projectRules.value.filter(r => r.enabled).length,
  },
  {
    scope: 'SESSION' as const,
    label: '当前会话',
    rules: sorted(sessionRules.value),
    enabledCount: sessionRules.value.filter(r => r.enabled).length,
  },
]);

function sorted(list: AgentRule[]) {
  return [...list].sort((a, b) => {
    if (a.enabled !== b.enabled) return a.enabled ? -1 : 1;
    return (b.id ?? 0) - (a.id ?? 0);
  });
}

async function loadAll() {
  const pid = projectStore.currentProjectId;
  const sid = chat.currentSessionId;
  isLoading.value = true;
  try {
    const [g, p, s] = await Promise.all([
      agentApi.getRules({}), // 不传参数 → 后端 SecurityContext 自动取当前用户 → 拉 GLOBAL
      pid != null ? agentApi.getRules({ projectId: pid }) : Promise.resolve([]),
      sid != null ? agentApi.getRules({ sessionId: sid }) : Promise.resolve([]),
    ]);
    globalRules.value = g;
    projectRules.value = p;
    sessionRules.value = s;
  } catch {
    // 后端可能还没跑起来，静默降级
  } finally {
    isLoading.value = false;
  }
}

/** scope 切换/项目切换/会话切换时自动刷新 */
watch(
  () => [projectStore.currentProjectId, chat.currentSessionId],
  () => loadAll()
);

onMounted(loadAll);

/** 开关 */
async function toggle(r: AgentRule) {
  try {
    await agentApi.toggleRule(r.id, !r.enabled);
    r.enabled = !r.enabled;
  } catch { /* 静默 */ }
}

/** 新建 / 编辑 — 前端先弹简单的 prompt 对话框（后续接 Element Plus Dialog） */
function onCreate(scope: 'GLOBAL' | 'PROJECT' | 'SESSION') {
  const label = scope === 'GLOBAL' ? '全局' : scope === 'PROJECT' ? '项目' : '会话';
  const name = prompt(`新建${label}规则：名称`, '');
  if (!name) return;
  const content = prompt(`规则内容（会拼进 system prompt）：`, '你是一位...');
  if (!content) return;
  const rule: any = { name, content, scope, enabled: true };
  // GLOBAL 不传 userId —— 后端从 SecurityContext 自动注入
  if (scope === 'PROJECT') rule.projectId = projectStore.currentProjectId;
  if (scope === 'SESSION') rule.sessionId = chat.currentSessionId;
  agentApi.createRule(rule).then(loadAll).catch(() => {});
}

function onEdit(r: AgentRule) {
  const name = prompt('修改名称：', r.name) ?? r.name;
  const content = prompt('修改内容：', r.content) ?? r.content;
  agentApi.updateRule(r.id, { name, content }).then(loadAll).catch(() => {});
}

async function onDelete(r: AgentRule) {
  if (!confirm(`确定删除规则「${r.name}」？（内置规则不允许删除）`)) return;
  try {
    await agentApi.deleteRule(r.id);
    loadAll();
  } catch { /* 静默 */ }
}

function truncate(s: string, n: number) {
  const t = s.replace(/\s+/g, ' ').trim();
  return t.length > n ? t.slice(0, n) + '…' : t;
}
</script>

<style scoped>
.rules-panel { padding: 8px; }
.rules-header { padding: 4px 8px 12px; }
.rules-title {
  font-size: 12px; font-weight: 600; color: var(--text-secondary);
  text-transform: uppercase; letter-spacing: 0.5px;
}
.rules-group { margin-bottom: 12px; }
.rules-group-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 6px 10px;
}
.rules-group-title { font-size: 11px; font-weight: 600; color: var(--text-tertiary); }
.rules-group-count {
  font-size: 10px; color: var(--text-tertiary);
  padding: 1px 6px; border-radius: 10px;
  background: var(--surface-3, rgba(0,0,0,0.05));
}
.rules-list { display: flex; flex-direction: column; gap: 6px; }
.rules-empty-group {
  font-size: 11px; color: var(--text-tertiary); padding: 8px 12px;
  display: flex; justify-content: space-between; align-items: center;
}
.rules-add-mini {
  font-size: 10px; padding: 2px 8px; border: none; cursor: pointer;
  background: var(--surface-3, rgba(0,0,0,0.05));
  border-radius: 4px; color: var(--text-secondary);
}
.rule-card {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 10px 12px; border-radius: 8px;
  background: var(--surface); border: 1px solid transparent;
  transition: border-color 0.15s;
}
.rule-card:hover { border-color: var(--border); }
.rule-card.builtin { background: var(--surface-3, rgba(0,0,0,0.03)); }
.rule-body { flex: 1; min-width: 0; cursor: pointer; }
.rule-name { font-size: 13px; font-weight: 600; color: var(--text-primary); }
.rule-badge {
  font-size: 9px; padding: 0 5px; border-radius: 3px;
  background: var(--primary-bg, rgba(99,102,241,0.12));
  color: var(--primary); margin-left: 6px; font-weight: 500;
}
.rule-desc { font-size: 11px; color: var(--text-secondary); margin-top: 2px; }
.rule-content-preview {
  font-size: 11px; color: var(--text-tertiary);
  margin-top: 4px; font-family: monospace;
  background: var(--surface-3, rgba(0,0,0,0.04));
  padding: 4px 8px; border-radius: 4px;
}
.rule-toggle {
  width: 32px; height: 18px; border-radius: 9px;
  background: var(--surface-3, rgba(0,0,0,0.12));
  position: relative; cursor: pointer; flex-shrink: 0;
  transition: background 0.15s;
}
.rule-toggle.on { background: var(--primary); }
.rule-toggle-thumb {
  width: 14px; height: 14px; border-radius: 7px;
  background: #fff; position: absolute; top: 2px; left: 2px;
  transition: left 0.15s; box-shadow: 0 1px 2px rgba(0,0,0,0.2);
}
.rule-toggle.on .rule-toggle-thumb { left: 16px; }
.rule-actions {
  display: flex; gap: 4px; flex-shrink: 0; margin-top: 2px;
  opacity: 0; transition: opacity 0.15s;
}
.rule-card:hover .rule-actions { opacity: 1; }
.rule-action-btn {
  width: 22px; height: 22px; border-radius: 4px;
  border: none; cursor: pointer;
  background: transparent; color: var(--text-secondary);
  display: flex; align-items: center; justify-content: center;
}
.rule-action-btn:hover { background: var(--surface-3, rgba(0,0,0,0.08)); }
.rule-action-btn.danger:hover { color: #ef4444; }
.rule-add-card {
  justify-content: center; gap: 6px; cursor: pointer;
  color: var(--text-secondary); font-size: 12px;
  border: 1px dashed var(--border);
}
.rule-add-card:hover { border-color: var(--primary); color: var(--primary); background: transparent; }
.rules-loading { text-align: center; font-size: 12px; color: var(--text-tertiary); padding: 16px; }
</style>
