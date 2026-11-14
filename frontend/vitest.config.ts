import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

// P5-11-1：单元测试只覆盖不依赖浏览器的逻辑与组件，
// 复用 vite.config.ts 的 Vue 插件，但不影响 dev/build 行为。
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      include: ['src/**/*.{test,spec}.{ts,tsx}'],
      exclude: ['node_modules', 'dist', 'e2e/**'],
      restoreMocks: true,
      passWithNoTests: true
    }
  })
);
