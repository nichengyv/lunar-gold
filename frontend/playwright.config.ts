import { defineConfig } from '@playwright/test';

// P5-11-1：E2E 只验证前端关键路径，后端通过 page.route 仿真（见 e2e/fixtures/api.ts）。
// 默认启动 Vite dev server；设置 E2E_BASE_URL 可接已启动的实例。
const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5174';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  outputDir: 'test-results',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    headless: true
  },
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run dev -- --port 5174 --strictPort',
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000
      },
  projects: [
    {
      name: 'chromium',
      use: {
        browserName: 'chromium',
        // 默认用 Playwright 自带 Chromium；设置 E2E_CHANNEL=none 可改用系统浏览器
        ...(process.env.E2E_CHANNEL === 'none' ? {} : { channel: process.env.E2E_CHANNEL ?? 'chromium' })
      }
    }
  ]
});
