import { beforeEach, describe, expect, it, vi } from 'vitest';
import { marked } from 'marked';
import {
  renderMarkdown,
  renderMarkdownStreaming,
  resetMarkdownSegmentCache
} from './markdownSegments';

describe('markdownSegments', () => {
  beforeEach(() => {
    resetMarkdownSegmentCache();
  });

  it('整篇解析保留标题、列表与代码块复制按钮', () => {
    const html = renderMarkdown('## 标题\n\n- 一\n- 二\n\n```ts\nconst a = 1;\n```');
    expect(html).toContain('<h2>标题</h2>');
    expect(html).toContain('<li>一</li>');
    expect(html).toContain('copy-code-btn');
    expect(html).toContain('code-lang');
  });

  it('流式分段渲染结果与整篇解析一致', () => {
    const content = '## 标题\n\n第一段。\n\n- 一\n- 二\n\n```js\nconst x = 1;\n```';
    expect(renderMarkdownStreaming(content)).toBe(renderMarkdown(content));
  });

  it('追加段落时只重新渲染新增的 token', () => {
    const parserSpy = vi.spyOn(marked, 'parser');
    const first = '## 标题\n\n第一段。';
    renderMarkdownStreaming(first);
    const firstCalls = parserSpy.mock.calls.length;
    expect(firstCalls).toBeGreaterThan(0);

    renderMarkdownStreaming(`${first}\n\n第二段。`);

    // 标题与第一段命中缓存，只有新增段落（以及新增的空行 token）需要渲染
    const newRaws = parserSpy.mock.calls.slice(firstCalls).map(([tokens]) => tokens[0].raw);
    expect(newRaws).toContain('第二段。');
    expect(newRaws).not.toContain('第一段。');
    expect(newRaws).not.toContain('## 标题\n');
    parserSpy.mockRestore();
  });

  it('内容变化导致旧段落失效时重新渲染该段落', () => {
    const first = renderMarkdownStreaming('第一段');
    const changed = renderMarkdownStreaming('第一段（补充）\n\n第二段');
    expect(first).toContain('第一段');
    expect(changed).toContain('第一段（补充）');
    expect(changed).toContain('第二段');
  });

  it('空内容返回空字符串', () => {
    expect(renderMarkdownStreaming('')).toBe('');
  });
});
