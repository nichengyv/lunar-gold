import { defineStore } from 'pinia';

const THEME_KEY = 'theme';
const FONT_KEY = 'fontSize';
/** 默认主题从亮色改为深色，老版本会把默认值自动写进 localStorage，需要一次性区分 */
const THEME_VERSION_KEY = 'themeVersion';
const THEME_VERSION = '2';

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
    fontSize: (localStorage.getItem(FONT_KEY) as 'small' | 'medium' | 'large') || 'medium'
  }),
  actions: {
    init() {
      applyTheme(this.isDark);
      applyFont(this.fontSize);
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
    }
  }
});
