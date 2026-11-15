/**
 * P5-11-3：Markdown 渲染的两条路径。
 *
 * - 非流式（历史消息、已完成的回答）：整篇解析，结果与改造前完全一致。
 * - 流式：按顶层 token 分段渲染并缓存，只重算新增或变化的段落；
 *   流式结束后调用方切回整篇解析，保证最终 HTML 不受分段语义影响。
 */
import { marked } from 'marked';
import type { Token } from 'marked';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/common';

const CACHE_LIMIT = 200;
/** 已渲染段落缓存：key = token.type + 原始文本，value = HTML */
const segmentCache = new Map<string, string>();

// 代码块仍然在解析阶段产出语言标签、复制按钮与高亮，避免流式更新时操作 DOM
marked.use({
  renderer: {
    code({ text, lang }) {
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

/** 整篇解析：非流式内容与流式结束后的最终结果 */
export function renderMarkdown(content: string): string {
  return DOMPurify.sanitize(marked.parse(content) as string);
}

/** 流式解析：只重新渲染新增/变化的顶层段落，其余命中缓存 */
export function renderMarkdownStreaming(content: string): string {
  const tokens = marked.lexer(content);
  const next = new Map<string, string>();
  const parts: string[] = [];

  for (const token of tokens as Token[]) {
    const key = `${token.type}\u0000${token.raw}`;
    let html = segmentCache.get(key) ?? next.get(key);
    if (html === undefined) {
      html = marked.parser([token]);
    }
    next.set(key, html);
    if (next.size > CACHE_LIMIT) {
      const oldest = next.keys().next().value;
      if (oldest !== undefined) next.delete(oldest);
    }
    parts.push(html);
  }

  // 只保留本次仍出现的段落，避免跨消息缓存无限增长
  segmentCache.clear();
  next.forEach((html, key) => segmentCache.set(key, html));
  return DOMPurify.sanitize(parts.join(''));
}

/** 测试用：清空段落缓存，避免用例之间互相影响 */
export function resetMarkdownSegmentCache(): void {
  segmentCache.clear();
}
