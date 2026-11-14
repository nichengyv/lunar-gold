import { expect, test } from '@playwright/test';
import { changeSetFixture, mockApi } from './fixtures/api';

const sessions = [{ id: 1, title: '交付任务', projectId: 1, updateTime: '2026-11-14T10:00:00' }];

async function sendTask(page: Parameters<typeof mockApi>[0]) {
  await page.goto('/chat');
  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('修改 demo.ts');
  await input.press('Enter');
  const card = page.locator('.pfc-card');
  await expect(card).toBeVisible();
  await card.locator('.pfc-header').click();
  return card;
}

test('接受全部改动不会把未验证改写成验证通过', async ({ page }) => {
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
  await expect(card).toContainText('个文件改动待审查');
  await expect(card.locator('.pfc-summary')).toContainText('验证：未验证');
  await expect(card.locator('.pfc-summary')).toContainText('待审查 1');

  await card.getByRole('button', { name: '全部保留' }).click();

  // 批量保留成功后面板会收起，重新展开才能看到交付摘要
  await card.locator('.pfc-header').click();
  await expect(card.locator('.pfc-summary')).toContainText('已接受 1');
  // 关键：接受动作只改交付状态，验证仍是未验证
  await expect(card.locator('.pfc-summary')).toContainText('验证：未验证');
  await expect(card.locator('.pfc-status')).toHaveText('已接受');
});

test('整体撤回遇到外部修改时提示冲突且不覆盖', async ({ page }) => {
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
  await card.getByRole('button', { name: '整体撤回' }).click();

  const confirm = page.locator('.el-message-box');
  await expect(confirm).toBeVisible();
  await confirm.getByRole('button', { name: '整体撤回' }).click();

  await expect(page.getByText(/冲突未覆盖：文件在 Agent 修改后被外部改动/)).toBeVisible();
});
