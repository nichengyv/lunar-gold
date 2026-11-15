import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures/api';

const sessions = [
  { id: 1, title: '当前会话', projectId: 1, updateTime: '2026-11-14T10:00:00' }
];

/** 文档级横向滚动：桌面端任意档位都不应出现 */
async function horizontalOverflow(page: import('@playwright/test').Page): Promise<number> {
  return page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth
  );
}

test('960×640：无横向滚动，输入区与发送按钮可见', async ({ page }) => {
  await page.setViewportSize({ width: 960, height: 640 });
  await mockApi(page, { sessions });
  await page.goto('/chat');

  await expect(page.getByPlaceholder(/输入消息/)).toBeVisible();
  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(1);

  const input = page.getByPlaceholder(/输入消息/);
  await expect(input).toBeInViewport();
  await expect(page.getByRole('button', { name: '发送消息' })).toBeInViewport();

  // <1024 自动收起为图标栏（宽度过渡结束后为 56px）
  const sidebar = page.locator('.sidebar');
  await expect(sidebar).toHaveClass(/collapsed/);
  await expect
    .poll(async () => Math.round((await sidebar.boundingBox())?.width ?? 0))
    .toBeLessThanOrEqual(57);

  await page.screenshot({ path: test.info().outputPath('responsive-960x640.png') });
});

test('900×700：侧栏图标栏，工作台以浮层打开且不挤压正文', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 700 });
  await mockApi(page, { sessions });
  await page.goto('/chat');

  const sidebar = page.locator('.sidebar');
  await expect(sidebar).toHaveClass(/collapsed/);
  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(1);

  const dockButton = page.getByRole('button', { name: '任务详情侧边栏' });
  await expect(dockButton).toBeVisible();
  await dockButton.click();

  const dock = page.locator('.workbench');
  await expect(dock).toBeVisible();
  await expect(page.locator('.dock-backdrop')).toBeVisible();
  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(1);

  // 浮层入场动画（translateX 8px）结束后应完全落在视口内
  await expect
    .poll(async () => {
      const b = await dock.boundingBox();
      return Math.round((b?.x ?? 0) + (b?.width ?? 0));
    })
    .toBeLessThanOrEqual(900);
  const dockBox = await dock.boundingBox();
  expect(dockBox?.x ?? 0).toBeGreaterThanOrEqual(0);

  await page.screenshot({ path: test.info().outputPath('responsive-900x700-dock.png') });

  // 点遮罩左侧空白处收起浮层
  await page.locator('.dock-backdrop').click({ position: { x: 40, y: 120 } });
  await expect(dock).toHaveCount(0);
});

test('767×700：侧栏抽屉默认收起，菜单按钮可打开', async ({ page }) => {
  await page.setViewportSize({ width: 767, height: 700 });
  await mockApi(page, { sessions });
  await page.goto('/chat');

  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(1);

  const sidebar = page.locator('.sidebar');
  const closedBox = await sidebar.boundingBox();
  expect(closedBox?.x ?? 0).toBeLessThan(0);

  await page.getByRole('button', { name: '展开侧栏' }).click();
  await expect(sidebar).toHaveClass(/open/);
  const openBox = await sidebar.boundingBox();
  expect(openBox?.x ?? -1).toBeGreaterThanOrEqual(0);

  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(1);
  await page.screenshot({ path: test.info().outputPath('responsive-767x700.png') });
});

test('1280×800：侧栏展开，三栏无横向滚动', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await mockApi(page, { sessions });
  await page.goto('/chat');

  const sidebar = page.locator('.sidebar');
  await expect(sidebar).not.toHaveClass(/collapsed/);
  const box = await sidebar.boundingBox();
  expect(box?.width ?? 0).toBeGreaterThanOrEqual(279);

  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(1);
  await expect(page.getByPlaceholder(/输入消息/)).toBeInViewport();
});
