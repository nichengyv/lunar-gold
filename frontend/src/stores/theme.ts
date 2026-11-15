import { defineStore } from 'pinia';

const THEME_KEY = 'theme';
const FONT_KEY = 'fontSize';
/** 默认主题从亮色改为深色，老版本会把默认值自动写进 localStorage，需要一次性区分 */
const THEME_VERSION_KEY = 'themeVersion';
const THEME_VERSION = '2';
/** P5-10-4：减少动效。值为 reduce / full，缺省时跟随系统偏好 */
const MOTION_KEY = 'reduceMotion';
/** P5-09：消息密度。comfortable 为默认，compact 只切密度 token */
const DENSITY_KEY = 'density';
export type Density = 'comfortable' | 'compact';

/** 读取初始主题：v2 之前留下的值不算用户选择，统一按新的深色默认 */
export function resolveInitialDark(): boolean {
  const stored = localStorage.getItem(THEME_KEY);
  const version = localStorage.getItem(THEME_VERSION_KEY);
  if (version !== THEME_VERSION) return true;
  return stored !== 'light';
}

function applyTheme(isDark: boolean) {
  const root = document.documentElement;
  root.setAttribute('data-theme', isDark ? 'dark' : 'light');
  root.classList.toggle('dark', isDark);
  localStorage.setItem(THEME_KEY, isDark ? 'dark' : 'light');
  localStorage.setItem(THEME_VERSION_KEY, THEME_VERSION);
}

/** 未手动设置时读取系统偏好 */
function systemReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function storedMotion(): 'reduce' | 'full' | null {
  const value = localStorage.getItem(MOTION_KEY);
  return value === 'reduce' || value === 'full' ? value : null;
}

/** 写入 data-motion，样式只需一条 [data-motion="reduce"] 规则 */
function applyMotion(reduce: boolean) {
  const root = document.documentElement;
  if (reduce) root.setAttribute('data-motion', 'reduce');
  else root.removeAttribute('data-motion');
}

function initialReduceMotion(): boolean {
  const stored = storedMotion();
  return stored === null ? systemReducedMotion() : stored === 'reduce';
}

/** null 表示用户没有手动选择，此时跟随系统并监听变化 */
let manualMotion = storedMotion() === null ? null : storedMotion() === 'reduce';

function bindSystemMotion() {
  if (manualMotion !== null || typeof window.matchMedia !== 'function') return;
  window.matchMedia('(prefers-reduced-motion: reduce)').addEventListener?.('change', (e) => {
    applyMotion(e.matches);
  });
}

/** 读取已保存的密度档位，非法值回落到舒适 */
function storedDensity(): Density {
  return localStorage.getItem(DENSITY_KEY) === 'compact' ? 'compact' : 'comfortable';
}

/** 写入 data-density；舒适档移除属性，样式默认即为舒适 */
function applyDensity(density: Density) {
  const root = document.documentElement;
  if (density === 'compact') root.setAttribute('data-density', 'compact');
  else root.removeAttribute('data-density');
}

function applyFont(size: 'small' | 'medium' | 'large') {
  const root = document.documentElement;
  const map: Record<string, string> = { small: '13px', medium: '14px', large: '15px' };
  root.style.setProperty('--fs-base', map[size]);
  root.style.setProperty('--fs-sm', size === 'small' ? '12px' : size === 'medium' ? '13px' : '14px');
  root.style.setProperty('--fs-xs', size === 'small' ? '11px' : size === 'medium' ? '12px' : '13px');
  localStorage.setItem(FONT_KEY, size);
}

export const useThemeStore = defineStore('theme', {
  state: () => ({
    // 默认深色：开发者工具的使用场景多在编辑器旁边，亮色太刺眼；用户手动切换后以 localStorage 为准
    isDark: resolveInitialDark(),
    fontSize: (localStorage.getItem(FONT_KEY) as 'small' | 'medium' | 'large') || 'medium',
    reduceMotion: initialReduceMotion(),
    density: storedDensity() as Density
  }),
  actions: {
    init() {
      applyTheme(this.isDark);
      applyFont(this.fontSize);
      applyMotion(this.reduceMotion);
      applyDensity(this.density);
      bindSystemMotion();
    },
    set(isDark: boolean) {
      this.isDark = isDark;
      applyTheme(isDark);
    },
    toggle() {
      this.set(!this.isDark);
    },
    setFont(size: 'small' | 'medium' | 'large') {
      this.fontSize = size;
      applyFont(size);
    },
    /** P5-09：切换消息密度，只改根节点属性与持久化值 */
    setDensity(density: Density) {
      this.density = density;
      localStorage.setItem(DENSITY_KEY, density);
      applyDensity(density);
    },
    /** P5-10-4：手动开关后不再跟随系统，直到用户清除本地设置 */
    setReduceMotion(reduce: boolean) {
      this.reduceMotion = reduce;
      manualMotion = reduce;
      localStorage.setItem(MOTION_KEY, reduce ? 'reduce' : 'full');
      applyMotion(reduce);
    }
  }
});
