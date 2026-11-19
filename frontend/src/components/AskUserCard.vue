<script setup lang="ts">
/**
 * P7-06：agent 执行中提出的决策询问卡片。
 *
 * 和 ApprovalCard（危险工具审批）并列的第二张"用户在环"卡片：
 * 审批是"风险门禁"（要不要让 agent 执行危险操作），
 * 这张是"决策分支"（agent 停下来问方向选 A 还是 B）。
 */
import { computed, ref } from 'vue';
import { useChatStore } from '../stores/chat';
import type { UserQuestion } from '../types/chat';

const props = defineProps<{
  question: UserQuestion & { runId: number };
}>();
const chat = useChatStore();

/** 用户选中的选项 labels */
const selected = ref<string[]>([]);
/** 用户自由输入文本（无 options 或需要补充说明时用） */
const custom = ref('');
/** 已提交后禁用，防止重复提交 */
const submitted = ref(false);

const hasOptions = computed(() => (props.question.options?.length ?? 0) > 0);

function toggleOption(label: string) {
  if (submitted.value) return;
  if (props.question.multiSelect) {
    const idx = selected.value.indexOf(label);
    if (idx >= 0) selected.value.splice(idx, 1);
    else selected.value.push(label);
  } else {
    selected.value = selected.value[0] === label ? [] : [label];
  }
}

function isSelected(label: string) {
  return selected.value.includes(label);
}

async function submit() {
  if (submitted.value) return;
  if (!props.question.multiSelect && selected.value.length === 0 && !custom.value.trim()) {
    return; // 必须选或填
  }
  submitted.value = true;
  try {
    await chat.answerUserQuestion(
      props.question.runId,
      props.question.questionId,
      selected.value,
      custom.value.trim() || undefined
    );
  } catch (e) {
    submitted.value = false;
    console.error('回答决策询问失败', e);
  }
}

/** J/K 切换 / Enter 提交 / Esc 清空 */
function onKeydown(e: KeyboardEvent) {
  if (submitted.value) return;
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    void submit();
  } else if (e.key === 'Escape') {
    selected.value = [];
    custom.value = '';
  }
}
</script>

<template>
  <div class="userq-card" role="group" aria-label="agent 询问">
    <div class="userq-head">
      <span class="userq-icon">💬</span>
      <span v-if="question.header" class="userq-header">{{ question.header }}</span>
      <span v-else class="userq-header">需要你决定</span>
    </div>

    <div class="userq-body">
      <div class="userq-question">{{ question.question }}</div>

      <!-- 有选项：卡片式单选/多选 -->
      <div v-if="hasOptions" class="userq-options" @keydown="onKeydown">
        <button
          v-for="opt in question.options"
          :key="opt.label"
          class="userq-option"
          :class="{ selected: isSelected(opt.label), recommended: opt.label.includes('Recommended') }"
          :disabled="submitted"
          type="button"
          @click="toggleOption(opt.label)"
        >
          <span class="opt-label">{{ opt.label }}</span>
          <span v-if="opt.description" class="opt-desc">{{ opt.description }}</span>
        </button>
      </div>

      <!-- 无选项：自由输入 -->
      <div v-else class="userq-free">
        <textarea
          v-model="custom"
          class="userq-textarea"
          rows="2"
          :disabled="submitted"
          placeholder="请输入你的想法…"
          @keydown="onKeydown"
        />
      </div>
    </div>

    <div class="userq-actions">
      <span v-if="question.multiSelect" class="userq-hint">可多选</span>
      <button
        class="userq-submit"
        :disabled="submitted || (!selected.length && !custom.trim())"
        @click="submit"
      >
        {{ submitted ? '已回答' : '提交回答' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.userq-card {
  background: var(--surface-2);
  border: 1px solid var(--primary);
  border-radius: var(--radius-sm);
  margin: 0 20px 6px;
  padding: 10px 12px;
  flex-shrink: 0;
}
.userq-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
}
.userq-icon { font-size: 14px; }
.userq-header { font-weight: 600; color: var(--primary); }

.userq-body { margin: 8px 0 10px; }
.userq-question {
  font-size: var(--fs-sm);
  color: var(--text);
  line-height: 1.5;
  margin-bottom: 8px;
}

.userq-options {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.userq-option {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 8px 10px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text);
  font-size: var(--fs-xs);
  cursor: pointer;
  transition: border-color .15s, background .15s;
  text-align: left;
}
.userq-option:hover:not(:disabled) {
  border-color: var(--primary);
}
.userq-option.selected {
  border-color: var(--primary);
  background: color-mix(in srgb, var(--primary) 8%, var(--surface));
}
.userq-option.recommended .opt-label::before {
  content: '★ ';
  color: var(--primary);
}
.opt-label { font-weight: 500; }
.opt-desc { color: var(--text-tertiary); font-size: 11px; }

.userq-textarea {
  width: 100%;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  color: var(--text);
  font-size: var(--fs-xs);
  resize: vertical;
  box-sizing: border-box;
}
.userq-textarea:focus {
  outline: none;
  border-color: var(--primary);
}

.userq-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
}
.userq-hint {
  font-size: 11px;
  color: var(--text-tertiary);
}
.userq-submit {
  padding: 4px 12px;
  border: none;
  background: var(--primary);
  border-radius: 5px;
  color: var(--on-primary);
  font-size: var(--fs-xs);
  cursor: pointer;
}
.userq-submit:disabled {
  opacity: 0.5;
  cursor: default;
}
</style>
