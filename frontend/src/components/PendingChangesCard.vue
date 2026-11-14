<script setup lang="ts">
/**
 * P5-14：任务级交付看板（文件变更审查卡片）
 * - 以当前会话为任务边界，聚合多个 Run 的变更、审批、诊断与验证
 * - 头部显示任务状态与验证摘要；未验证不会被「接受」覆盖为验证通过
 * - 支持按 Run 过滤、撤回此轮、整体撤回、继续修复与全部保留
 * - 无 ChangeSet 时退回项目级 pendingChanges 展示，兼容旧会话
 */
import { computed, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useChatStore } from '../stores/chat';
import { useProjectStore } from '../stores/project';
import Icon from './Icon.vue';

const chat = useChatStore();
const project = useProjectStore();

const expanded = defineModel<boolean>('expanded', { default: false });

type ReviewChange = {
  id: number;
  filePath: string;
  action: string;
  source: string;
  additions?: number | null;
  deletions?: number | null;
  diffText?: string | null;
  turnId?: string | null;
  runId?: number | null;
};

type RunGroup = {
  runId: number | null;
  turnId: string | null;
  label: string;
  state?: string;
  changes: ReviewChange[];
};

const changeSet = computed(() => chat.changeSet);
const verification = computed(() => changeSet.value?.verification ?? null);

/** 只把 PENDING 变更交给审查动作；已接受/已撤回保留在交付视图中做审计 */
const pendingChanges = computed<ReviewChange[]>(() => {
  if (!changeSet.value) return chat.pendingChanges;
  return changeSet.value.changes.filter((c) => c.status === 'PENDING');
});
const count = computed(() => pendingChanges.value.length);

const visible = computed(() => {
  if (changeSet.value) {
    return changeSet.value.totals.files > 0 && changeSet.value.status !== 'EMPTY';
  }
  return count.value > 0;
});

const statusClass = computed(() =>
  changeSet.value ? `status-${changeSet.value.status.toLowerCase()}` : ''
);

const verifyClass = computed(() => {
  const status = verification.value?.status;
  if (status === 'PASSED' || status === 'DIAGNOSTICS_PASSED') return 'verify-passed';
  if (status === 'FAILED') return 'verify-failed';
  return 'verify-muted';
});

const failedCommands = computed(() =>
  (verification.value?.commands ?? []).filter(
    (cmd) => cmd.timedOut || (cmd.exitCode != null && cmd.exitCode !== 0)
  )
);

const groups = computed<RunGroup[]>(() => {
  const cs = changeSet.value;
  if (!cs) {
    return count.value > 0
      ? [{ runId: null, turnId: null, label: '待审查', changes: pendingChanges.value }]
      : [];
  }
  const result: RunGroup[] = [];
  for (const run of cs.runs) {
    const items = pendingChanges.value.filter((c) =>
      run.turnId ? c.turnId === run.turnId : c.runId === run.runId
    );
    if (items.length > 0) {
      result.push({
        runId: run.runId,
        turnId: run.turnId ?? null,
        label: `#${run.runId}`,
        state: run.state,
        changes: items
      });
    }
  }
  const unmatched = pendingChanges.value.filter(
    (c) => !result.some((group) => group.changes.includes(c))
  );
  if (unmatched.length > 0) {
    result.push({ runId: null, turnId: null, label: '其他变更', changes: unmatched });
  }
  return result;
});

/** 第六十八批 A3：最近一轮的 turnId；无 ChangeSet 时仍可撤回本轮 */
const latestTurnId = computed(() => {
  for (let i = chat.messages.length - 1; i >= 0; i--) {
    const m = chat.messages[i];
    if (m.role === 'assistant' && m.turnId) return m.turnId;
  }
  return null;
});

/** D4：展开某文件的 diff 预览；null = 折叠 */
const diffOpenId = ref<number | null>(null);

/** 把 unified diff 文本解析成可渲染的行数组 */
function parseDiff(diffText: string | null | undefined) {
  if (!diffText) return [];
  return diffText.split('\n').map((line) => {
    let type: 'add' | 'del' | 'ctx' | 'meta' = 'ctx';
    if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('@@')) type = 'meta';
    else if (line.startsWith('+')) type = 'add';
    else if (line.startsWith('-')) type = 'del';
    return { type, text: line };
  });
}

function toggleDiff(id: number) {
  diffOpenId.value = diffOpenId.value === id ? null : id;
}

function onOpenEditor(path: string) {
  chat.openFile(path);
}

async function onRevertOne(id: number, filePath: string) {
  try {
    await ElMessageBox.confirm(`撤销「${filePath}」的本轮改动？`, '撤回单文件', {
      type: 'warning',
      confirmButtonText: '撤销',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  await chat.revertChange(id);
  if (diffOpenId.value === id) diffOpenId.value = null;
}

async function onAcceptOne(id: number) {
  await chat.acceptChange(id);
  if (diffOpenId.value === id) diffOpenId.value = null;
}

async function onAcceptAll() {
  const pid = project.currentProjectId;
  if (pid != null) {
    await chat.acceptAll(pid);
    expanded.value = false;
  }
}

async function onRevertRun(runId: number) {
  try {
    await ElMessageBox.confirm(
      `撤回 #${runId} 的全部未审查改动？同一文件多次修改会按逆序恢复；若文件已被外部修改，将返回冲突且不覆盖。`,
      '撤回此轮改动',
      { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await chat.revertRunChanges(runId);
}

async function onRevertTask() {
  try {
    await ElMessageBox.confirm(
      '整体撤回本任务所有未审查的文件改动？将按执行逆序恢复；已接受的变更保留审计，外部修改不会被覆盖。',
      '整体撤回',
      { type: 'warning', confirmButtonText: '整体撤回', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await chat.revertTask();
}

async function onRevertTurn() {
  try {
    await ElMessageBox.confirm(
      '将把本轮尚未审查的文件改动全部回滚到本轮开始前的状态。',
      '撤回本轮改动',
      { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await chat.revertTurn();
}

async function onContinueFix() {
  await chat.continueFix();
}
</script>

<template>
  <div v-if="visible" class="pfc-card" :class="{ expanded }">
    <!-- 折叠头 -->
    <div class="pfc-header" @click="expanded = !expanded">
      <Icon :name="expanded ? 'chevron-down' : 'chevron-right'" :size="11" class="pfc-chev" />
      <span v-if="count" class="pfc-count">{{ count }}</span>
      <span class="pfc-title">{{ count ? '个文件改动待审查' : '任务交付' }}</span>
      <span v-if="changeSet" class="pfc-status" :class="statusClass">{{ changeSet.statusLabel }}</span>
      <span v-if="changeSet" class="pfc-hint">
        +{{ changeSet.totals.additions }} -{{ changeSet.totals.deletions }}
      </span>
      <span v-else class="pfc-hint">（点击展开列表，点文件打开编辑器）</span>
    </div>

    <!-- 展开列表 -->
    <div v-if="expanded" class="pfc-list">
      <!-- 任务交付摘要 -->
      <div v-if="changeSet" class="pfc-summary">
        <span class="pfc-summary-item">
          验证：<strong :class="verifyClass">{{ verification?.label || '未验证' }}</strong>
        </span>
        <span v-if="changeSet.totals.files" class="pfc-summary-item">
          文件 {{ changeSet.totals.files }}
        </span>
        <span v-if="changeSet.totals.pending" class="pfc-summary-item">
          待审查 {{ changeSet.totals.pending }}
        </span>
        <span v-if="changeSet.totals.accepted" class="pfc-summary-item">
          已接受 {{ changeSet.totals.accepted }}
        </span>
        <span v-if="changeSet.approvals.requested" class="pfc-summary-item">
          审批 {{ changeSet.approvals.resolved }}/{{ changeSet.approvals.requested }}
        </span>
      </div>

      <!-- 验证失败上下文 -->
      <div v-if="changeSet && (verification?.message || failedCommands.length > 0)" class="pfc-verify-detail">
        <div v-if="verification?.message" class="pfc-verify-msg">{{ verification.message }}</div>
        <details v-if="failedCommands.length > 0" class="pfc-commands">
          <summary>失败命令（{{ failedCommands.length }}）</summary>
          <div v-for="(cmd, ci) in failedCommands" :key="ci" class="pfc-command">
            <div class="pfc-command-head">
              {{ cmd.name || cmd.command || '命令' }}
              <span v-if="cmd.exitCode != null">exit={{ cmd.exitCode }}</span>
              <span v-if="cmd.timedOut">超时</span>
            </div>
            <pre v-if="cmd.outputTail" class="pfc-command-output">{{ cmd.outputTail }}</pre>
          </div>
        </details>
      </div>

      <!-- 按 Run 过滤 -->
      <div v-if="changeSet && changeSet.runs.length > 1" class="pfc-run-filter">
        <button
          class="pfc-filter-chip"
          :class="{ active: chat.changeSetRunFilter == null }"
          @click.stop="chat.setChangeSetRunFilter(null)"
        >
          全部
        </button>
        <button
          v-for="run in changeSet.runs"
          :key="run.runId"
          class="pfc-filter-chip"
          :class="{ active: chat.changeSetRunFilter === run.runId }"
          @click.stop="chat.setChangeSetRunFilter(run.runId)"
        >
          #{{ run.runId }}
        </button>
      </div>

      <template v-for="group in groups" :key="group.runId ?? group.label">
        <div v-if="changeSet && groups.length > 0" class="pfc-run-head">
          <span class="pfc-run-label">{{ group.label }}</span>
          <span v-if="group.state" class="pfc-run-state">{{ group.state }}</span>
          <button
            v-if="group.runId != null && group.changes.length > 0"
            class="pfc-run-revert"
            @click.stop="onRevertRun(group.runId)"
          >
            撤回此轮
          </button>
        </div>

        <template v-for="c in group.changes" :key="c.id">
          <div class="pfc-item" :class="{ expanded: diffOpenId === c.id }">
            <Icon
              :name="c.action === 'create' ? 'file-plus' : c.action === 'delete' ? 'trash' : 'file-edit'"
              :size="11"
              class="pfc-item-icon"
            />
            <span class="pfc-item-path" :title="c.filePath">{{ c.filePath }}</span>
            <span v-if="c.additions" class="pfc-add">+{{ c.additions }}</span>
            <span v-if="c.deletions" class="pfc-del">-{{ c.deletions }}</span>

            <!-- D4：操作按钮组 -->
            <div class="pfc-item-actions" @click.stop>
              <button
                v-if="c.diffText"
                class="pfc-action-btn"
                :title="diffOpenId === c.id ? '收起 diff' : '查看 diff'"
                @click="toggleDiff(c.id)"
              >
                <Icon :name="diffOpenId === c.id ? 'chevron-down' : 'chevron-right'" :size="10" />
              </button>
              <button class="pfc-action-btn" title="在编辑器中打开" @click="onOpenEditor(c.filePath)">
                <Icon name="external-link" :size="10" />
              </button>
              <button class="pfc-action-btn pfc-action-ok" title="保留此文件改动" @click="onAcceptOne(c.id)">
                <Icon name="check" :size="10" />
              </button>
              <button
                class="pfc-action-btn pfc-action-danger"
                title="撤销此文件改动"
                @click="onRevertOne(c.id, c.filePath)"
              >
                <Icon name="x" :size="10" />
              </button>
            </div>
          </div>

          <!-- D4：inline diff 预览面板（展开时出现） -->
          <div v-if="diffOpenId === c.id && c.diffText" class="pfc-diff">
            <div class="pfc-diff-header">
              <span>{{ c.filePath }}</span>
              <span class="pfc-diff-source">
                （{{ c.action === 'create' ? '新建' : c.action === 'delete' ? '删除' : c.source || '编辑' }}）
              </span>
              <button v-if="c.action !== 'delete'" class="pfc-diff-open" @click="onOpenEditor(c.filePath)">
                在编辑器中打开 →
              </button>
              <span v-else class="pfc-diff-source">文件已删除</span>
            </div>
            <div class="pfc-diff-body">
              <div
                v-for="(line, li) in parseDiff(c.diffText)"
                :key="li"
                class="pfc-diff-line"
                :class="'diff-' + line.type"
              >
                {{ line.text || ' ' }}
              </div>
            </div>
          </div>
        </template>
      </template>

      <div v-if="count === 0" class="pfc-empty">
        当前没有待审查变更；已接受或已撤回的记录仍保留在任务交付视图中。
      </div>

      <!-- 底部操作 -->
      <div class="pfc-footer">
        <button
          v-if="changeSet?.continuePrompt"
          class="pfc-btn pfc-btn-primary"
          @click.stop="onContinueFix"
        >
          继续修复
        </button>
        <button v-if="changeSet && count > 0" class="pfc-btn pfc-btn-danger" @click.stop="onRevertTask">
          整体撤回
        </button>
        <button v-if="!changeSet && latestTurnId" class="pfc-btn pfc-btn-danger" @click.stop="onRevertTurn">
          撤回本轮
        </button>
        <button v-if="count > 0" class="pfc-btn pfc-btn-primary" @click.stop="onAcceptAll">
          全部保留
        </button>
      </div>
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
.pfc-hint { color: var(--text-secondary); font-size: 10px; margin-left: auto; }
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

.pfc-list {
  border-top: 1px solid var(--border);
  max-height: 340px;
  overflow-y: auto;
}
.pfc-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
  font-size: 10px;
  color: var(--text-secondary);
}
.pfc-summary-item { white-space: nowrap; }
.pfc-summary-item strong { color: var(--text); }
.verify-passed { color: var(--success); }
.verify-failed { color: var(--danger); }
.verify-muted { color: var(--warning, var(--text-secondary)); }

.pfc-verify-detail {
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
  background: color-mix(in srgb, var(--warning, var(--surface)) 8%, var(--surface));
  font-size: 10px;
  color: var(--text-secondary);
}
.pfc-verify-msg { margin-bottom: 4px; color: var(--text); }
.pfc-commands summary { cursor: pointer; color: var(--text-secondary); }
.pfc-command { margin-top: 4px; }
.pfc-command-head { font-weight: 600; color: var(--danger); }
.pfc-command-output {
  margin: 2px 0 0;
  max-height: 120px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 10px;
  color: var(--text-secondary);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 3px;
  padding: 4px 6px;
}

.pfc-run-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 5px 10px;
  border-bottom: 1px solid var(--border);
}
.pfc-filter-chip {
  font-size: 10px;
  padding: 1px 7px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-secondary);
  cursor: pointer;
}
.pfc-filter-chip.active {
  border-color: var(--primary);
  color: var(--primary);
}
.pfc-run-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
  font-size: 10px;
  color: var(--text-secondary);
}
.pfc-run-label { font-weight: 700; color: var(--text); }
.pfc-run-state { text-transform: lowercase; }
.pfc-run-revert {
  margin-left: auto;
  font-size: 10px;
  padding: 1px 7px;
  border: 1px solid var(--danger);
  background: transparent;
  color: var(--danger);
  border-radius: 8px;
  cursor: pointer;
}
.pfc-run-revert:hover { background: var(--danger); color: var(--on-primary); }

.pfc-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  cursor: pointer;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  border-bottom: 1px solid var(--border);
  transition: background 0.12s;
}
.pfc-item:last-child { border-bottom: none; }
.pfc-item:hover { background: var(--surface-hover); }
.pfc-item-icon { color: var(--primary); flex-shrink: 0; }
.pfc-item-path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
}
.pfc-add { flex-shrink: 0; color: var(--success); font-weight: 600; font-variant-numeric: tabular-nums; }
.pfc-del { flex-shrink: 0; color: var(--danger); font-weight: 600; font-variant-numeric: tabular-nums; }

/* D4：操作按钮组 */
.pfc-item-actions {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s;
}
.pfc-item:hover .pfc-item-actions,
.pfc-item.expanded .pfc-item-actions { opacity: 1; }
.pfc-action-btn {
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  border-radius: 3px;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0;
  line-height: 1;
}
.pfc-action-btn:hover { background: var(--surface-hover); color: var(--text); }
.pfc-action-ok:hover { background: rgba(103, 194, 58, 0.15); color: var(--success); }
.pfc-action-danger:hover { background: rgba(245, 108, 108, 0.15); color: var(--danger); }

/* D4：inline diff 预览面板 */
.pfc-diff {
  background: var(--bg);
  border-top: 1px solid var(--border);
  padding: 0;
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.pfc-diff-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
  font-size: 10px;
  font-weight: 600;
  color: var(--text);
}
.pfc-diff-source { color: var(--text-secondary); font-weight: 400; }
.pfc-diff-open {
  margin-left: auto;
  padding: 2px 8px;
  border: none;
  background: var(--primary);
  color: var(--on-primary);
  border-radius: 3px;
  font-size: 10px;
  font-weight: 600;
  cursor: pointer;
}
.pfc-diff-open:hover { opacity: 0.9; }
.pfc-diff-body {
  max-height: 280px;
  overflow: auto;
  padding: 4px 0;
  line-height: 1.5;
  white-space: pre;
}
.pfc-diff-line {
  padding: 0 10px;
  display: block;
}
.pfc-diff-line.diff-add { background: color-mix(in srgb, var(--success) 18%, transparent); color: var(--success); }
.pfc-diff-line.diff-del { background: color-mix(in srgb, var(--danger) 18%, transparent); color: var(--danger); }
.pfc-diff-line.diff-meta { color: var(--text-secondary); font-weight: 600; background: var(--surface-2); }
.pfc-diff-line.diff-ctx { color: var(--text-secondary); }

.pfc-empty {
  padding: 10px;
  font-size: 10px;
  color: var(--text-secondary);
}

.pfc-footer {
  border-top: 1px solid var(--border);
  padding: 8px 10px;
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
.pfc-btn {
  font-size: 11px;
  padding: 4px 12px;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: var(--surface);
  cursor: pointer;
  transition: background 0.15s;
}
.pfc-btn:hover { background: var(--surface-hover); }
.pfc-btn-danger {
  color: var(--danger);
  border-color: var(--danger);
}
.pfc-btn-danger:hover {
  background: var(--danger);
  color: var(--on-primary);
}
.pfc-btn-primary {
  background: var(--primary);
  color: var(--on-primary);
  border-color: var(--primary);
}
.pfc-btn-primary:hover {
  background: var(--primary-hover, var(--primary));
  opacity: 0.9;
}
</style>
