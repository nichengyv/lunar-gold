import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures/api';

test('发送消息后流式回答并回到可输入状态', async ({ page }) => {
  await mockApi(page);
  await page.goto('/chat');

  const input = page.getByPlaceholder(/输入消息/);
  await expect(input).toBeVisible();
  await input.fill('你好');
  await input.press('Enter');

  await expect(page.getByText('你好，我是 E2E 助手。')).toBeVisible();
  await expect(page.locator('.btn-stop')).toHaveCount(0);
  await expect(page.locator('.btn-send')).toBeVisible();
  await expect(input).toHaveValue('');
});
