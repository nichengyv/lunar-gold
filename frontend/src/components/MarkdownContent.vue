<template>
  <div class="md-content" v-html="html" @click="onCopyCode"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { marked } from 'marked';
import type { Tokens } from 'marked';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/common';
import { copyToClipboard } from '../utils/format';

const props = defineProps<{ content: string }>();

// 自定义代码块渲染：语言标签 + 复制按钮 + 高亮（在解析阶段完成，避免流式更新时反复操作 DOM）
marked.use({
  renderer: {
    code({ text, lang }: Tokens.Code): string {
      const language = (lang || '').split(/\s+/)[0];
      let highlighted: string;
      if (language && hljs.getLanguage(language)) {
        highlighted = hljs.highlight(text, { language }).value;
      } else {
        highlighted = hljs.highlightAuto(text).value;
      }
      const label = language || 'code';
      const encoded = encodeURIComponent(text);
      return (
        `<div class="code-block">` +
        `<div class="code-header"><span class="code-lang">${label}</span>` +
        `<button class="copy-code-btn" data-code="${encoded}">复制</button></div>` +
        `<pre><code class="hljs">${highlighted}</code></pre>` +
        `</div>`
      );
    }
  }
});

const html = computed(() => DOMPurify.sanitize(marked.parse(props.content) as string));

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
