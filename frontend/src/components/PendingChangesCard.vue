<script setup lang="ts">
/**
 * P6-08：待审查改动卡片（任务级）。
 *
 * 只回答两件事：还有哪些文件等你处理，一键保留还是撤销。
 * - 折叠态头部直接给「全部撤销 / 全部保留」，不必先展开；
 * - 展开态只列待审查文件（名字、目录、+n -m），点整行在工作台文件标签打开；
 * - 卡片内不再内嵌 diff 抽屉，逐行改动看工作台里的编辑器即可。
 */
import { computed } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useChatStore } from '../stores/chat';
import Icon from './Icon.vue';

const chat = useChatStore();

const expanded = defineModel<boolean>('expanded', { default: false });

type ReviewChange = {
  id: number;
  filePath: string;
  action: string;
  source: string;
  additions?: number | null;
  deletions?: number | null;
  turnId?: string | null;
  runId?: number | null;
};

const changeSet = computed(() => chat.changeSet);

/** 只把 PENDING 变更交给审查动作；已接受/已撤回不再占用卡片 */
const pendingChanges = computed<ReviewChange[]>(() => {
  if (!changeSet.value) return chat.pendingChanges;
  return changeSet.value.changes.filter((c) => c.status === 'PENDING');
});
const count = computed(() => pendingChanges.value.length);

/** 没有待审查文件就不再渲染卡片，避免留一个只剩审计信息的空壳 */
const visible = computed(() => count.value > 0);

const statusClass = computed(() =>
  changeSet.value ? `status-${changeSet.value.status.toLowerCase()}` : ''
);

function baseName(path: string): string {
  const idx = path.lastIndexOf('/');
  return idx < 0 ? path : path.slice(idx + 1);
}

function dirName(path: string): string {
  const idx = path.lastIndexOf('/');
  return idx < 0 ? '' : path.slice(0, idx);
}

/** 点整行：直接在工作台的文件标签里打开，改动行会自动标色 */
function onOpen(path: string) {
  chat.openFile(path);
}

async function onAcceptAll() {
  if (!count.value) return;
  const ids = pendingChanges.value.map((c) => c.id);
  await chat.acceptChanges(ids);
  expanded.value = false;
}

async function onRevertAll() {
  try {
    await ElMessageBox.confirm(
      '撤销本任务中所有待审查的文件改动？文件会恢复为执行前的状态；执行后被外部修改过的文件不会被覆盖。',
      '全部撤销',
      { type: 'warning', confirmButtonText: '全部撤销', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  const ok = await chat.revertTask();
  if (ok) expanded.value = false;
}

async function onContinueFix() {
  await chat.continueFix();
}
</script>

<template>
  <div v-if="visible" class="pfc-card" :class="{ expanded }">
    <!-- 折叠头：批量操作常驻，不展开也能决策 -->
    <div class="pfc-header" @click="expanded = !expanded">
      <Icon :name="expanded ? 'chevron-down' : 'chevron-right'" :size="11" class="pfc-chev" />
      <span class="pfc-count">{{ count }}</span>
      <span class="pfc-title">个文件改动待审查</span>
      <span v-if="changeSet" class="pfc-status" :class="statusClass">{{ changeSet.statusLabel }}</span>

      <div class="pfc-head-actions" @click.stop>
        <button
          v-if="changeSet?.continuePrompt"
          class="pfc-head-btn"
          @click="onContinueFix"
        >
          继续修复
        </button>
        <button class="pfc-head-btn danger" @click="onRevertAll">全部撤销</button>
        <button class="pfc-head-btn primary" @click="onAcceptAll">全部保留</button>
      </div>
    </div>

    <!-- 展开列表：只列待审查文件，点整行进编辑器 -->
    <div v-if="expanded" class="pfc-list">
      <button
        v-for="c in pendingChanges"
        :key="c.id"
        class="pfc-item"
        :title="c.filePath"
        @click="onOpen(c.filePath)"
      >
        <span class="pfc-name">{{ baseName(c.filePath) }}</span>
        <span class="pfc-path">{{ dirName(c.filePath) }}</span>
        <span class="pfc-stat">
          <span class="pfc-add">+{{ c.additions ?? 0 }}</span>
          <span class="pfc-del">-{{ c.deletions ?? 0 }}</span>
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.pfc-card {
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  margin-bottom: 6px;
  font-size: 12px;
  overflow: hidden;
}
.pfc-header {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
}
.pfc-header:hover { background: var(--surface-hover); }
.pfc-chev { color: var(--text-secondary); }
.pfc-count {
  background: var(--primary);
  color: var(--on-primary);
  font-weight: 700;
  font-size: 11px;
  min-width: 18px;
  height: 18px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 4px;
}
.pfc-title { font-weight: 600; color: var(--text); }
.pfc-status {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-secondary);
  white-space: nowrap;
}
.pfc-status.status-unverified,
.pfc-status.status-incomplete { color: var(--warning, var(--text-secondary)); border-color: var(--warning, var(--border)); }
.pfc-status.status-failed,
.pfc-status.status-stopped { color: var(--danger); border-color: var(--danger); }
.pfc-status.status-verified,
.pfc-status.status-accepted,
.pfc-status.status-completed { color: var(--success); border-color: var(--success); }
.pfc-status.status-running,
.pfc-status.status-waiting_approval { color: var(--primary); border-color: var(--primary); }

/* 批量操作常驻头部右侧；点击不展开列表 */
.pfc-head-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.pfc-head-btn {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-secondary);
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.pfc-head-btn:hover { background: var(--surface-hover); color: var(--text); }
.pfc-head-btn.danger { color: var(--danger); border-color: var(--danger); }
.pfc-head-btn.danger:hover { background: var(--danger); color: var(--on-primary); }
.pfc-head-btn.primary { background: var(--primary); border-color: var(--primary); color: var(--on-primary); }
.pfc-head-btn.primary:hover { opacity: 0.9; }

.pfc-list {
  border-top: 1px solid var(--border);
  max-height: 320px;
  overflow-y: auto;
}
.pfc-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  border-bottom: 1px solid var(--border);
  background: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  text-align: left;
  cursor: pointer;
  transition: background 0.12s;
}
.pfc-item:last-child { border-bottom: none; }
.pfc-item:hover { background: var(--surface-hover); }
.pfc-name {
  flex-shrink: 0;
  font-weight: 600;
  color: var(--text);
}
.pfc-path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-tertiary);
}
.pfc-stat {
  flex-shrink: 0;
  display: inline-flex;
  gap: 6px;
  font-variant-numeric: tabular-nums;
}
.pfc-add { color: var(--success); font-weight: 600; }
.pfc-del { color: var(--danger); font-weight: 600; }
</style>
