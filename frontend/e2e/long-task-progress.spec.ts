import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures/api';

// 说明：Playwright 的 route.fulfill 无法逐块推送 SSE，因此「阶段随事件推进」由 Vitest 覆盖；
// 这里验证真实组件在长任务期间的进度条、预算行、停止与继续、失败分级。

test('长任务运行中显示阶段进度与预算，停止后收起', async ({ page }) => {
  await mockApi(page, { streamDelayMs: 8000 });
  await page.goto('/chat');

  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('重构这个模块');
  await input.press('Enter');

  const progress = page.locator('.task-progress');
  await expect(progress).toBeVisible({ timeout: 6000 });
  await expect(progress).toContainText('执行进度');
  await expect(progress).toContainText('第 1 步');
  await expect(progress).toContainText('理解');
  await expect(progress).toContainText('轮次 —');

  await page.locator('.btn-stop').click();
  await expect(page.locator('.stopped-hint')).toContainText('已停止生成');
  await expect(page.locator('.task-progress')).toHaveCount(0);
  await expect(page.locator('.btn-stop')).toHaveCount(0);
});

test('配置类失败显示「需配置」并可去设置', async ({ page }) => {
  await mockApi(page, {
    stream: [
      'event: session_id\ndata: 1\n\n',
      'event: run_id\ndata: 103\n\n',
      'event: error\ndata: 401 Unauthorized: API Key 未配置\n\n'
    ].join('')
  });
  await page.goto('/chat');

  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('改一下 demo.ts');
  await input.press('Enter');

  await expect(page.locator('.failure-tag')).toHaveText('需配置');
  await expect(page.locator('.failed-hint')).toContainText('模型密钥或模型配置');
  await expect(page.getByRole('button', { name: '去设置' })).toBeVisible();

  await page.getByRole('button', { name: '去设置' }).click();
  await expect(page).toHaveURL(/\/settings/);
});

test('停止后可以继续，并发送固定续写提示', async ({ page }) => {
  await mockApi(page, { streamDelayMs: 8000 });
  await page.goto('/chat');

  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('写一个长任务');
  await input.press('Enter');

  await page.locator('.btn-stop').click();
  const hint = page.locator('.stopped-hint');
  await expect(hint).toContainText('已停止生成');
  await expect(hint.locator('.continue-btn')).toBeEnabled();

  await hint.locator('.continue-btn').click();
  await expect(page.getByText(/请继续完成上一条未完成的回答/)).toBeVisible();
  await expect(page.locator('.btn-stop')).toBeVisible();
  await expect(page.locator('.stopped-hint .continue-btn')).toBeDisabled();
});
