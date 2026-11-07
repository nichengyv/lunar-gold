<script setup lang="ts">
/**
 * 文件变更审查卡片
 * - Agent 写完文件后浮现："N 个文件改动待审查"
 * - 点击展开列表：只展示路径 + 加减行
 * - 点任一行 → 侧边栏打开该文件（用户在编辑器里直接看红绿 diff 手动改）
 * - 底部「全部保留」→ 调后端 accept-all，卡片消失
 * - 编辑器 Ctrl+S 保存单文件 → 自动 accept 该路径的记录
 */
import { computed, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useChatStore } from '../stores/chat';
import { useProjectStore } from '../stores/project';
import Icon from './Icon.vue';

const chat = useChatStore();
const project = useProjectStore();

const expanded = defineModel<boolean>('expanded', { default: false });

const changes = computed(() => chat.pendingChanges);
const count = computed(() => changes.value.length);

/** 第六十八批 A3：最近一轮的 turnId；有值才显示「撤回本轮」 */
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
</script>

<template>
  <div v-if="count > 0" class="pfc-card" :class="{ expanded }">
    <!-- 折叠头 -->
    <div class="pfc-header" @click="expanded = !expanded">
      <Icon :name="expanded ? 'chevron-down' : 'chevron-right'" :size="11" class="pfc-chev" />
      <span class="pfc-count">{{ count }}</span>
      <span class="pfc-title">个文件改动待审查</span>
      <span class="pfc-hint">（点击展开列表，点文件打开编辑器）</span>
    </div>

    <!-- 展开列表 -->
    <div v-if="expanded" class="pfc-list">
      <template v-for="c in changes" :key="c.id">
        <div
          class="pfc-item"
          :class="{ expanded: diffOpenId === c.id }"
        >
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
            <button
              class="pfc-action-btn"
              title="在编辑器中打开"
              @click="onOpenEditor(c.filePath)"
            >
              <Icon name="external-link" :size="10" />
            </button>
            <button
              class="pfc-action-btn pfc-action-ok"
              title="保留此文件改动"
              @click="onAcceptOne(c.id)"
            >
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
            <span class="pfc-diff-source">（{{ c.action === 'create' ? '新建' : c.action === 'delete' ? '删除' : c.source || '编辑' }}）</span>
            <button v-if="c.action !== 'delete'" class="pfc-diff-open" @click="onOpenEditor(c.filePath)">在编辑器中打开 →</button>
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

      <!-- 底部操作 -->
      <div class="pfc-footer">
        <button v-if="latestTurnId" class="pfc-btn pfc-btn-danger" @click.stop="onRevertTurn">
          撤回本轮
        </button>
        <button class="pfc-btn pfc-btn-primary" @click.stop="onAcceptAll">
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
  color: #fff;
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
.pfc-hint { color: var(--text-secondary); font-size: 10px; }

.pfc-list {
  border-top: 1px solid var(--border);
  max-height: 240px;
  overflow-y: auto;
}
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
  color: #fff;
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
.pfc-diff-line.diff-add { background: rgba(103, 194, 58, 0.18); color: #67c23a; }
.pfc-diff-line.diff-del { background: rgba(245, 108, 108, 0.18); color: #f56c6c; }
.pfc-diff-line.diff-meta { color: var(--text-secondary); font-weight: 600; background: var(--surface-2); }
.pfc-diff-line.diff-ctx { color: var(--text-secondary); }

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
  color: #fff;
}
.pfc-btn-primary {
  background: var(--primary);
  color: #fff;
  border-color: var(--primary);
}
.pfc-btn-primary:hover {
  background: var(--primary-hover, var(--primary));
  opacity: 0.9;
}
</style>
