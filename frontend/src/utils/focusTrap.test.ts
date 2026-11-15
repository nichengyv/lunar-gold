import { describe, expect, it } from 'vitest';
import { getFocusable, trapTabKey } from './focusTrap';

function setupPanel(html = '<button id="a">A</button><input id="b" /><button id="c" disabled>C</button>') {
  document.body.innerHTML = `<div id="panel">${html}</div><button id="outside">outside</button>`;
  return document.getElementById('panel') as HTMLElement;
}

function tab(shiftKey = false) {
  return new KeyboardEvent('keydown', { key: 'Tab', shiftKey, bubbles: true, cancelable: true });
}

describe('focusTrap', () => {
  it('只返回可用元素，跳过禁用项', () => {
    const panel = setupPanel();
    expect(getFocusable(panel).map((el) => el.id)).toEqual(['a', 'b']);
  });

  it('Tab 在末尾回到第一个', () => {
    const panel = setupPanel();
    const b = document.getElementById('b') as HTMLElement;
    b.focus();
    const event = tab();
    expect(trapTabKey(panel, event)).toBe(true);
    expect(document.activeElement?.id).toBe('a');
    expect(event.defaultPrevented).toBe(true);
  });

  it('Shift+Tab 在首个回到最后一个', () => {
    const panel = setupPanel();
    const a = document.getElementById('a') as HTMLElement;
    a.focus();
    expect(trapTabKey(panel, tab(true))).toBe(true);
    expect(document.activeElement?.id).toBe('b');
  });

  it('焦点在容器外时从首/尾接入', () => {
    const panel = setupPanel();
    const outside = document.getElementById('outside') as HTMLElement;
    outside.focus();
    expect(trapTabKey(panel, tab())).toBe(true);
    expect(document.activeElement?.id).toBe('a');

    outside.focus();
    expect(trapTabKey(panel, tab(true))).toBe(true);
    expect(document.activeElement?.id).toBe('b');
  });

  it('中间元素不拦截，交给浏览器默认行为', () => {
    const panel = setupPanel();
    (document.getElementById('a') as HTMLElement).focus();
    expect(trapTabKey(panel, tab())).toBe(false);
  });

  it('没有可聚焦元素时拦截 Tab，避免焦点逃逸', () => {
    const panel = setupPanel('<span>empty</span>');
    const event = tab();
    expect(trapTabKey(panel, event)).toBe(true);
    expect(event.defaultPrevented).toBe(true);
  });

  it('非 Tab 键不处理', () => {
    const panel = setupPanel();
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    expect(trapTabKey(panel, event)).toBe(false);
  });
});
