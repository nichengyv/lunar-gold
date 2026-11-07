import { defineStore } from 'pinia';

const THEME_KEY = 'theme';
const FONT_KEY = 'fontSize';

function applyTheme(isDark: boolean) {
  const root = document.documentElement;
  root.setAttribute('data-theme', isDark ? 'dark' : 'light');
  root.classList.toggle('dark', isDark);
  localStorage.setItem(THEME_KEY, isDark ? 'dark' : 'light');
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
    isDark: (localStorage.getItem(THEME_KEY) || 'light') === 'dark',
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
