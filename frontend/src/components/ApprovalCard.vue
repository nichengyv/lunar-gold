<script setup lang="ts">
/**
 * 危险工具审批卡片。
 *
 * 参考主流 Agent（Trae / Cursor / Claude Code）的做法：审批提示只回答三件事
 * 「做什么、动哪个文件、改了多少行」，内容默认不展开——需要细看时再点「查看改动」，
 * 避免把整段源码塞进审批卡片。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useChatStore } from '../stores/chat';
import type { PendingApproval } from '../types/api';
import type { ApprovalPreview } from '../types/chat';

const props = defineProps<{ approval: PendingApproval }>();
const chat = useChatStore();

const remaining = ref(0);
/** 详情默认折叠：卡片上只给摘要 */
const expanded = ref(false);
let timer: number | undefined;
let autoDismissTimer: number | undefined;

const isResolved = computed(() => props.approval.resolved !== undefined);
const preview = computed<ApprovalPreview | undefined>(() => props.approval.preview);

const riskLabel = computed(() => {
  if (props.approval.risk === 'DESTRUCTIVE') return '递归删除';
  if (props.approval.risk === 'EXECUTE') return '执行命令';
  if (preview.value?.kind === 'delete') return '删除文件';
  return props.approval.tool === 'edit_file' ? '修改文件' : '写入文件';
});

/** 路径拆成「目录 + 文件名」，文件名保持完整可读 */
const pathParts = computed(() => {
  const p = preview.value?.path ?? '';
  const idx = p.lastIndexOf('/');
  if (idx < 0) return { dir: '', name: p };
  return { dir: p.slice(0, idx), name: p.slice(idx + 1) };
});

const hasStats = computed(() => (preview.value?.additions ?? 0) > 0 || (preview.value?.deletions ?? 0) > 0);

/** diff 逐行分类，仅在展开时渲染 */
const diffLines = computed(() => {
  const text = preview.value?.diff;
  if (!text) return [] as { type: string; text: string }[];
  return text
    .split('\n')
    .filter((line, idx, arr) => !(line === '' && idx === arr.length - 1))
    .map((line) => {
      if (line.startsWith('@@')) return { type: 'hunk', text: line };
      if (line.startsWith('+')) return { type: 'add', text: line.slice(2) };
      if (line.startsWith('-')) return { type: 'del', text: line.slice(2) };
      if (line.startsWith('…')) return { type: 'note', text: line };
      return { type: 'ctx', text: line };
    });
});

const resultLabel = computed(() => {
  if (!isResolved.value) return '';
  return props.approval.resolved === 'APPROVE' ? '已批准' : '已拒绝';
});

/** resolved 后 3 秒自动 dismiss，避免数组堆积挡下一张卡片 */
function scheduleAutoDismiss() {
  if (autoDismissTimer) return;
  autoDismissTimer = window.setTimeout(() => {
    chat.dismissApproval(props.approval.approvalId);
    autoDismissTimer = undefined;
  }, 3000);
}

function tick() {
  remaining.value = Math.max(0, Math.ceil((props.approval.expiresAt - Date.now()) / 1000));
  if (remaining.value <= 0 && !isResolved.value) {
    chat.dismissApproval(props.approval.approvalId);
  }
  if (isResolved.value && timer) {
    window.clearInterval(timer);
    timer = undefined;
    scheduleAutoDismiss();
  }
}

watch(
  () => props.approval.resolved,
  (val) => {
    if (val !== undefined) scheduleAutoDismiss();
  }
);

onMounted(() => {
  tick();
  if (!isResolved.value) {
    timer = window.setInterval(tick, 1000);
  } else {
    scheduleAutoDismiss();
  }
});

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer);
  if (autoDismissTimer) window.clearTimeout(autoDismissTimer);
});

function respond(decision: 'APPROVE' | 'REJECT', remember = false) {
  if (isResolved.value) return;
  chat.respondApproval(props.approval.approvalId, decision, remember);
}

function close() {
  chat.dismissApproval(props.approval.approvalId);
}
</script>

<template>
  <div class="approval-card" :class="{ resolved: isResolved }">
    <div class="approval-head">
      <Icon
        :name="isResolved ? (approval.resolved === 'APPROVE' ? 'check-circle' : 'x-circle') : 'shield'"
        :size="13"
        class="approval-icon"
      />
      <span class="approval-title">{{ isResolved ? resultLabel : '需要你确认' }}</span>
      <span class="approval-chip">{{ riskLabel }}</span>
      <span v-if="!isResolved" class="approval-timer">{{ remaining }}s</span>
      <button class="approval-close" title="关闭" @click="close">×</button>
    </div>

    <div v-if="!isResolved" class="approval-body">
      <!-- 命令：单行展示，过长点击展开 -->
      <template v-if="preview?.kind === 'command'">
        <div class="approval-cmd" :class="{ wrapped: expanded }" @click="expanded = !expanded">
          <span class="cmd-prefix">$</span>{{ preview.command || '（空命令）' }}
        </div>
        <div v-if="preview.note" class="approval-note">{{ preview.note }}</div>
      </template>

      <!-- 文件类：摘要一行；内容默认折叠，点「查看改动」才展开 -->
      <template v-else-if="preview?.kind === 'write' || preview?.kind === 'edit' || preview?.kind === 'delete'">
        <div class="approval-file">
          <Icon :name="preview.kind === 'delete' ? 'trash' : 'file'" :size="12" class="file-icon" />
          <span v-if="pathParts.dir" class="file-dir" :title="preview.path">{{ pathParts.dir }}/</span>
          <span class="file-name">{{ pathParts.name || '（未提供路径）' }}</span>
          <span v-if="preview.newFile" class="badge new">新建</span>
          <span v-else-if="preview.kind === 'delete'" class="badge del">删除</span>
          <span v-if="hasStats" class="badge add">+{{ preview.additions ?? 0 }}</span>
          <span v-if="hasStats" class="badge del">−{{ preview.deletions ?? 0 }}</span>
          <button v-if="diffLines.length" class="link-btn" @click="expanded = !expanded">
            {{ expanded ? '收起' : preview.kind === 'delete' ? '查看将删除的内容' : '查看改动' }}
          </button>
        </div>
        <div v-if="expanded && diffLines.length" class="approval-diff">
          <div v-for="(line, i) in diffLines" :key="i" class="diff-line" :class="line.type">{{ line.text }}</div>
        </div>
      </template>

      <template v-else>
        <div class="approval-note">{{ preview?.note || '工具参数无法解析' }}</div>
      </template>
    </div>

    <div class="approval-actions">
      <button v-if="!isResolved" class="approval-btn" @click="respond('REJECT')">拒绝</button>
      <button v-if="!isResolved" class="approval-btn" @click="respond('APPROVE', true)">本会话总是允许</button>
      <button v-if="!isResolved" class="approval-btn primary" @click="respond('APPROVE')">允许</button>
      <button v-else class="approval-btn" :disabled="true">{{ resultLabel }}</button>
    </div>
  </div>
</template>

<style scoped>
.approval-card {
  background: var(--surface-2);
  border: 1px solid var(--warning, #d97706);
  border-radius: var(--radius-sm);
  margin: 0 20px 6px;
  padding: 8px 10px;
  flex-shrink: 0;
  transition: opacity .2s, border-color .2s, background .2s;
}
.approval-card.resolved {
  border-color: var(--border);
  background: var(--surface);
  opacity: 0.7;
}
.approval-card.resolved .approval-title {
  text-decoration: line-through;
  color: var(--text-secondary);
}
.approval-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
}
.approval-icon { color: var(--warning, #d97706); }
.approval-card.resolved .approval-icon { color: var(--text-tertiary); }
.approval-title { font-weight: 600; color: var(--text); }
.approval-chip {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  background: var(--surface);
  border-radius: 4px;
  padding: 1px 6px;
}
.approval-timer {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
.approval-close {
  background: none;
  border: none;
  color: var(--text-tertiary);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  padding: 0 2px;
  margin-left: 2px;
  opacity: 0;
  transition: opacity .15s, color .15s;
}
.approval-card:hover .approval-close,
.approval-card.resolved .approval-close { opacity: 1; }
.approval-close:hover { color: var(--danger); }

.approval-body { margin: 7px 0 8px; }

.approval-file {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  font-size: var(--fs-xs);
}
.file-icon { color: var(--text-tertiary); flex-shrink: 0; }
.file-dir {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--text-tertiary);
  direction: rtl;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex-shrink: 1;
}
.file-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--text);
  white-space: nowrap;
  flex-shrink: 0;
}
.badge {
  flex-shrink: 0;
  border-radius: 4px;
  padding: 0 5px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.badge.new { background: color-mix(in srgb, var(--primary) 14%, transparent); color: var(--primary); }
.badge.add { background: color-mix(in srgb, #16a34a 14%, transparent); color: #16a34a; }
.badge.del { background: color-mix(in srgb, var(--danger, #dc2626) 14%, transparent); color: var(--danger, #dc2626); }

.link-btn {
  margin-left: auto;
  flex-shrink: 0;
  background: none;
  border: none;
  padding: 0 2px;
  color: var(--primary);
  font-size: 11px;
  cursor: pointer;
}
.link-btn:hover { text-decoration: underline; }

.approval-cmd {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--text);
  background: var(--surface);
  border-radius: 5px;
  padding: 6px 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}
.approval-cmd.wrapped {
  white-space: pre-wrap;
  word-break: break-all;
  cursor: default;
}
.cmd-prefix {
  color: var(--text-tertiary);
  margin-right: 5px;
}

.approval-diff {
  margin-top: 7px;
  background: var(--surface);
  border-radius: 5px;
  padding: 5px 0;
  max-height: 160px;
  overflow-y: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  line-height: 1.55;
}
.diff-line {
  padding: 0 8px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-secondary);
}
.diff-line.add { background: color-mix(in srgb, #16a34a 12%, transparent); color: #15803d; }
.diff-line.del { background: color-mix(in srgb, var(--danger, #dc2626) 12%, transparent); color: var(--danger, #dc2626); }
.diff-line.hunk { color: var(--text-tertiary); }
.diff-line.note { color: var(--text-tertiary); font-style: italic; }

.approval-note {
  margin-top: 5px;
  font-size: 11px;
  color: var(--text-tertiary);
}

.approval-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
.approval-btn {
  padding: 3px 10px;
  border: 1px solid var(--border);
  background: var(--surface);
  border-radius: 5px;
  color: var(--text-secondary);
  font-size: var(--fs-xs);
  cursor: pointer;
}
.approval-btn:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
.approval-btn:disabled {
  cursor: default;
  opacity: 0.6;
  text-decoration: line-through;
}
.approval-btn.primary {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}
.approval-btn.primary:hover { opacity: 0.9; color: #fff; }
</style>
