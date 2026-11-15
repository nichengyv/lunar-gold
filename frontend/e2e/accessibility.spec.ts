import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockApi } from './fixtures/api';

const sessions = [
  { id: 1, title: '当前会话', projectId: 1, updateTime: '2026-11-14T10:00:00' },
  { id: 2, title: '另一个会话', projectId: 1, updateTime: '2026-11-14T09:00:00' }
];

test('快捷键面板焦点不逃逸，关闭后回到原处', async ({ page }) => {
  await mockApi(page, { sessions });
  await page.goto('/chat');

  const input = page.getByPlaceholder(/输入消息/);
  await input.focus();
  await page.keyboard.press('Control+/');

  const dialog = page.getByRole('dialog', { name: '键盘快捷键' });
  await expect(dialog).toBeVisible();
  await expect(dialog).toHaveAttribute('aria-modal', 'true');

  const close = page.getByRole('button', { name: '关闭快捷键面板' });
  await expect(close).toBeFocused();

  // 面板内只有关闭按钮可聚焦，Tab / Shift+Tab 都应留在面板内
  await page.keyboard.press('Tab');
  await expect(close).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(close).toBeFocused();

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(input).toBeFocused();
});

test('会话项可用键盘聚焦并切换', async ({ page }) => {
  await mockApi(page, { sessions });
  await page.goto('/chat');

  const target = page.locator('.session-main', { hasText: '另一个会话' });
  await expect(target).toBeVisible();
  await target.focus();
  await page.keyboard.press('Enter');

  await expect(target).toHaveAttribute('aria-current', 'true');
  // 切换后输入框仍可用（Ctrl+K 聚焦由单测/手工覆盖，浏览器会拦截该组合键）
  await expect(page.getByPlaceholder(/输入消息/)).toBeEnabled();
});

test('审批队列有区域语义并可键盘决策', async ({ page }) => {
  await mockApi(page, {
    sessions,
    stream: [
      'event: session_id\ndata: 1\n\n',
      'event: run_id\ndata: 102\n\n',
      `event: content\ndata: ${JSON.stringify({ text: '准备写入文件。' })}\n\n`,
      `event: approval_required\ndata: ${JSON.stringify({
        approvalId: 'ap-a11y',
        tool: 'write_file',
        risk: 'WRITE',
        label: '写入文件',
        preview: { kind: 'write', path: 'src/a11y.ts', newFile: false, additions: 1, deletions: 0, diff: '@@\n+ok' },
        expiresAt: Date.now() + 120_000
      })}\n\n`,
      'event: done\ndata: {}\n\n'
    ].join(''),
    runEvents: { 102: { runId: 102, state: 'WAITING_APPROVAL', events: [] } }
  });
  await page.goto('/chat');

  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('修改 a11y.ts');
  await input.press('Enter');

  const region = page.getByRole('region', { name: '待审批队列' });
  await expect(region).toBeVisible();
  await expect(region.getByRole('group', { name: '待审批：src/a11y.ts' })).toBeVisible();
  // 审批到达通过 aria-live 播报
  await expect(page.locator('.sr-only[aria-live="polite"]')).toContainText('有 1 项审批待确认');

  await input.evaluate((el) => (el as HTMLTextAreaElement).blur());
  const resolved = page.waitForRequest(
    (req) => req.url().includes('/api/agent/approvals/ap-a11y') && req.method() === 'POST'
  );
  await page.keyboard.press('a');
  expect((await resolved).postDataJSON()).toMatchObject({ decision: 'APPROVE' });
  await expect(region).toHaveCount(0);
});

for (const path of ['/chat', '/settings']) {
  test(`${path} axe critical 为 0`, async ({ page }) => {
    await mockApi(page, { sessions });
    await page.goto(path);
    if (path === '/chat') {
      await page.getByPlaceholder(/输入消息/).waitFor();
    } else {
      await page.locator('.settings-page').waitFor();
      await page.getByRole('button', { name: '外观' }).click();
      await page.getByRole('heading', { name: '减少动效' }).waitFor();
    }

    const results = await new AxeBuilder({ page }).analyze();
    const critical = results.violations.filter((v) => v.impact === 'critical');
    const summary = critical.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
    expect(critical, summary.join('\n')).toEqual([]);
  });
}
