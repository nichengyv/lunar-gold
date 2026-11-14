import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures/api';

test('生成中停止后保留已生成内容', async ({ page }) => {
  await mockApi(page, { streamDelayMs: 5000 });
  await page.goto('/chat');

  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('写一段很长的回答');
  await input.press('Enter');

  const stop = page.locator('.btn-stop');
  await expect(stop).toBeVisible();
  await stop.click();

  await expect(page.locator('.stopped-hint')).toContainText('已停止生成');
  await expect(page.locator('.btn-send')).toBeVisible();
  await expect(input).toBeEnabled();
});
