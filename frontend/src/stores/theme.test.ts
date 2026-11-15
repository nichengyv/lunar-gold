import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

describe('theme store 减少动效', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-motion');
    setActivePinia(createPinia());
    vi.resetModules();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('手动开启写入 data-motion，关闭后移除', async () => {
    const { useThemeStore } = await import('./theme');
    const store = useThemeStore();

    store.setReduceMotion(true);
    expect(document.documentElement.getAttribute('data-motion')).toBe('reduce');
    expect(localStorage.getItem('reduceMotion')).toBe('reduce');

    store.setReduceMotion(false);
    expect(document.documentElement.hasAttribute('data-motion')).toBe(false);
    expect(localStorage.getItem('reduceMotion')).toBe('full');
  });

  it('已保存 reduce 时初始化即生效', async () => {
    localStorage.setItem('reduceMotion', 'reduce');
    const { useThemeStore } = await import('./theme');
    const store = useThemeStore();
    expect(store.reduceMotion).toBe(true);

    store.init();
    expect(document.documentElement.getAttribute('data-motion')).toBe('reduce');
  });

  it('未手动设置时跟随系统偏好，并随系统变化更新', async () => {
    let listener: ((e: { matches: boolean }) => void) | undefined;
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({
        matches: true,
        addEventListener: (_: string, cb: (e: { matches: boolean }) => void) => {
          listener = cb;
        }
      }))
    );

    const { useThemeStore } = await import('./theme');
    const store = useThemeStore();
    expect(store.reduceMotion).toBe(true);

    store.init();
    expect(document.documentElement.getAttribute('data-motion')).toBe('reduce');

    listener?.({ matches: false });
    expect(document.documentElement.hasAttribute('data-motion')).toBe(false);
  });
});

describe('theme store 消息密度', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-density');
    setActivePinia(createPinia());
    vi.resetModules();
  });

  it('默认舒适，切紧凑写入 data-density 并持久化', async () => {
    const { useThemeStore } = await import('./theme');
    const store = useThemeStore();
    expect(store.density).toBe('comfortable');

    store.setDensity('compact');
    expect(document.documentElement.getAttribute('data-density')).toBe('compact');
    expect(localStorage.getItem('density')).toBe('compact');

    store.setDensity('comfortable');
    expect(document.documentElement.hasAttribute('data-density')).toBe(false);
  });

  it('已保存紧凑时初始化即生效', async () => {
    localStorage.setItem('density', 'compact');
    const { useThemeStore } = await import('./theme');
    const store = useThemeStore();
    expect(store.density).toBe('compact');

    store.init();
    expect(document.documentElement.getAttribute('data-density')).toBe('compact');
  });
});
