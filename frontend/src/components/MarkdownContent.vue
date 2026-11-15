<template>
  <div class="md-content" v-html="html" @click="onCopyCode"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { copyToClipboard } from '../utils/format';
import { renderMarkdown, renderMarkdownStreaming } from '../utils/markdownSegments';

const props = defineProps<{ content: string; streaming?: boolean }>();

// P5-11-3：流式期间只重算变化段落；完成后再整篇解析一次，保证最终结果与整篇一致
const html = computed(() =>
  props.streaming ? renderMarkdownStreaming(props.content) : renderMarkdown(props.content)
);

async function onCopyCode(e: MouseEvent) {
  const btn = (e.target as HTMLElement).closest('.copy-code-btn') as HTMLElement | null;
  if (!btn || !btn.dataset.code) return;
  await copyToClipboard(decodeURIComponent(btn.dataset.code));
  btn.textContent = '已复制';
  btn.classList.add('copied');
  setTimeout(() => {
    btn.textContent = '复制';
    btn.classList.remove('copied');
  }, 2000);
}
</script>
