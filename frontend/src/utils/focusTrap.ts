/**
 * P5-10-4：焦点陷阱工具。
 *
 * 只做两件事：找出容器内可聚焦元素、把 Tab / Shift+Tab 限制在首尾之间循环。
 * 不接管初始焦点与焦点恢复，那属于调用方的交互语义。
 */

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])'
].join(',');

function isVisible(el: HTMLElement): boolean {
  if (el.hidden || el.getAttribute('aria-hidden') === 'true') return false;
  const style = window.getComputedStyle(el);
  return style.display !== 'none' && style.visibility !== 'hidden';
}

/** 容器内当前可聚焦的元素，按 DOM 顺序 */
export function getFocusable(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(isVisible);
}

/**
 * 处理 Tab 循环；返回 true 表示事件已被接管。
 * 焦点在容器外时，正向从第一个开始、反向从最后一个开始。
 */
export function trapTabKey(container: HTMLElement, event: KeyboardEvent): boolean {
  if (event.key !== 'Tab') return false;
  const items = getFocusable(container);
  if (items.length === 0) {
    event.preventDefault();
    return true;
  }
  const first = items[0];
  const last = items[items.length - 1];
  const active = document.activeElement as HTMLElement | null;
  const outside = !active || !container.contains(active);

  if (!event.shiftKey && (outside || active === last)) {
    event.preventDefault();
    first.focus();
    return true;
  }
  if (event.shiftKey && (outside || active === first)) {
    event.preventDefault();
    last.focus();
    return true;
  }
  return false;
}
