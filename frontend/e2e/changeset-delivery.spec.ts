import { expect, test, type Page } from '@playwright/test';
import { changeSetFixture, mockApi } from './fixtures/api';

const sessions = [{ id: 1, title: '交付任务', projectId: 1, updateTime: '2026-11-14T10:00:00' }];

/** 发一条任务，等「待审查改动」卡片出现 */
async function sendTask(page: Page) {
  await page.goto('/chat');
  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('修改 demo.ts');
  await input.press('Enter');
  const card = page.locator('.pfc-card');
  await expect(card).toBeVisible();
  return card;
}

test('折叠态即可全部保留，成功后卡片消失', async ({ page }) => {
  const accepted = changeSetFixture({
    status: 'ACCEPTED',
    statusLabel: '已接受',
    accepted: true,
    totals: { files: 1, additions: 2, deletions: 1, pending: 0, accepted: 1, reverted: 0 },
    changes: [{ ...changeSetFixture().changes[0], status: 'ACCEPTED' }]
  });
  await mockApi(page, {
    sessions,
    changeSet: changeSetFixture(),
    changeSetAfterChange: accepted
  });

  const card = await sendTask(page);
  // P6-08：未展开就有批量操作，且不再渲染交付摘要
  await expect(card.locator('.pfc-list')).toHaveCount(0);
  await expect(card.getByRole('button', { name: '全部撤销' })).toBeVisible();
  await expect(card.getByRole('button', { name: '全部保留' })).toBeVisible();
  await expect(card.locator('.pfc-summary')).toHaveCount(0);

  await card.getByRole('button', { name: '全部保留' }).click();

  // 保留成功后没有待审查文件，卡片整体消失
  await expect(card).toHaveCount(0);
});

test('展开只列文件，点整行在工作台打开并显示路径', async ({ page }) => {
  await mockApi(page, { sessions, changeSet: changeSetFixture() });

  const card = await sendTask(page);
  await card.locator('.pfc-header').click();

  const row = card.locator('.pfc-item').first();
  await expect(row).toContainText('demo.ts');
  await expect(row).toContainText('src');
  await expect(row).toContainText('+2');
  await expect(row).toContainText('-1');
  // 旧版摘要、Run 分组与 inline diff 抽屉都不再出现
  await expect(card.locator('.pfc-summary')).toHaveCount(0);
  await expect(card.locator('.pfc-run-head')).toHaveCount(0);
  await expect(card.locator('.pfc-diff')).toHaveCount(0);

  await row.click();

  // 文件在工作台的标签栏打开；详情头部展示相对路径而不是只有文件名
  await expect(page.locator('.wb-tab-file.active')).toContainText('demo.ts');
  // 监控属于开发者视角，不再占用工作台一级标签
  await expect(page.locator('.wb-tabs').getByText('监控')).toHaveCount(0);
  await expect(page.locator('.fe-file-path')).toContainText('src/demo.ts');
  // 编辑器直接铺满工作台，不再像内嵌一层抽屉卡片
  const widths = await page.evaluate(() => {
    const dock = document.querySelector('.wb-file')?.getBoundingClientRect().width ?? 0;
    const pane = document.querySelector('.fe-pane')?.getBoundingClientRect().width ?? 0;
    return { dock, pane };
  });
  expect(Math.abs(widths.dock - widths.pane)).toBeLessThan(2);
  // 编辑器底色与工作台一致，不再出现一块更亮的“抽屉”
  const colors = await page.evaluate(() => ({
    dock: getComputedStyle(document.querySelector('.workbench') as Element).backgroundColor,
    editor: getComputedStyle(document.querySelector('.cm-editor') as Element).backgroundColor
  }));
  expect(colors.editor).toBe(colors.dock);
});

test('保留后编辑器不再标绿，只剩可读内容', async ({ page }) => {
  const accepted = changeSetFixture({
    status: 'ACCEPTED',
    statusLabel: '已接受',
    accepted: true,
    totals: { files: 1, additions: 2, deletions: 1, pending: 0, accepted: 1, reverted: 0 },
    changes: [{ ...changeSetFixture().changes[0], status: 'ACCEPTED' }]
  });
  await mockApi(page, {
    sessions,
    changeSet: changeSetFixture(),
    changeSetAfterChange: accepted
  });

  const card = await sendTask(page);
  await card.locator('.pfc-header').click();
  await card.locator('.pfc-item').first().click();
  // 待审查时新增行有绿底
  await expect(page.locator('.cm-line.cm-line-add').first()).toBeVisible();

  // 全部保留后该文件不再需要审查，绿标应消失
  await card.getByRole('button', { name: '全部保留' }).click();
  await expect(card).toHaveCount(0);
  await expect(page.locator('.cm-line.cm-line-add')).toHaveCount(0);
});

test('全部撤销遇到外部修改时提示冲突且不覆盖', async ({ page }) => {
  await mockApi(page, {
    sessions,
    changeSet: changeSetFixture(),
    revertResult: {
      ok: false,
      count: 0,
      failed: 0,
      skippedAccepted: 0,
      conflicts: [{ id: 9001, filePath: 'src/demo.ts', reason: '文件在 Agent 修改后被外部改动' }]
    }
  });

  const card = await sendTask(page);
  await card.getByRole('button', { name: '全部撤销' }).click();

  const confirm = page.locator('.el-message-box');
  await expect(confirm).toBeVisible();
  await confirm.getByRole('button', { name: '全部撤销' }).click();

  await expect(page.getByText(/冲突未覆盖：文件在 Agent 修改后被外部改动/)).toBeVisible();
});
