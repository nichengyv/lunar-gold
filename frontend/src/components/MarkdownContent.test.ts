import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import MarkdownContent from './MarkdownContent.vue';
import { copyToClipboard } from '../utils/format';

vi.mock('../utils/format', () => ({
  copyToClipboard: vi.fn(async () => undefined)
}));

const copyMock = vi.mocked(copyToClipboard);

describe('MarkdownContent', () => {
  beforeEach(() => {
    copyMock.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('带语言代码块显示语言标签与复制按钮', () => {
    const wrapper = mount(MarkdownContent, {
      props: { content: '```ts\nconst a = 1;\n```' }
    });
    expect(wrapper.find('.code-block').exists()).toBe(true);
    expect(wrapper.find('.code-lang').text()).toBe('ts');
    expect(wrapper.find('.copy-code-btn').exists()).toBe(true);
    expect(wrapper.find('pre code').text()).toContain('const a = 1;');
  });

  it('无语言代码块标签回落为 code', () => {
    const wrapper = mount(MarkdownContent, {
      props: { content: '```\nplain text\n```' }
    });
    expect(wrapper.find('.code-lang').text()).toBe('code');
  });

  it('点击复制写入原始代码并显示已复制', async () => {
    vi.useFakeTimers();
    const wrapper = mount(MarkdownContent, {
      props: { content: '```js\nconst x = "复 制";\n```' }
    });
    const btn = wrapper.find('.copy-code-btn');
    await btn.trigger('click');
    expect(copyMock).toHaveBeenCalledTimes(1);
    expect(copyMock.mock.calls[0][0].trim()).toBe('const x = "复 制";');
    expect(btn.text()).toBe('已复制');

    vi.advanceTimersByTime(2000);
    await wrapper.vm.$nextTick();
    expect(btn.text()).toBe('复制');
  });

  it('普通文本、列表与链接正常渲染', () => {
    const wrapper = mount(MarkdownContent, {
      props: { content: '## 标题\n\n- 一\n- 二\n\n[链接](https://example.com)' }
    });
    expect(wrapper.find('h2').text()).toBe('标题');
    expect(wrapper.findAll('li')).toHaveLength(2);
    expect(wrapper.find('a').attributes('href')).toBe('https://example.com');
  });

  it('流式模式同样渲染 Markdown 与代码块复制按钮', () => {
    const wrapper = mount(MarkdownContent, {
      props: { content: '## 标题\n\n第一段\n\n```ts\nconst a = 1;\n```', streaming: true }
    });
    expect(wrapper.find('h2').text()).toBe('标题');
    expect(wrapper.find('.code-lang').text()).toBe('ts');
    expect(wrapper.find('.copy-code-btn').exists()).toBe(true);
  });
});
