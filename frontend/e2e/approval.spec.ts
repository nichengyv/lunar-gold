import { expect, test } from '@playwright/test';
import { approvalStream, mockApi, multiApprovalStream } from './fixtures/api';

async function triggerApproval(page: Parameters<typeof mockApi>[0]) {
  await mockApi(page, {
    stream: approvalStream(),
    // 审批挂起时后端轮次停在 WAITING_APPROVAL，前端恢复轮询不会重拉历史把卡片冲掉
    runEvents: { 102: { runId: 102, state: 'WAITING_APPROVAL', events: [] } }
  });
  await page.goto('/chat');
  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('修改 demo.ts');
  await input.press('Enter');
  const card = page.locator('.approval-card');
  await expect(card).toBeVisible();
  return card;
}

test('允许审批后提交 APPROVE 并移除卡片', async ({ page }) => {
  const card = await triggerApproval(page);
  await expect(card.getByText('需要你确认')).toBeVisible();
  await expect(card).toContainText('写入文件');

  const submitted = page.waitForRequest(
    (req) => req.url().includes('/api/agent/approvals/ap-1') && req.method() === 'POST'
  );
  // 「本会话总是允许」也含「允许」，必须锚定到主按钮「允许A」
  await card.getByRole('button', { name: /^允许/ }).click();

  expect((await submitted).postDataJSON()).toMatchObject({ decision: 'APPROVE' });
  await expect(card).toHaveCount(0);
});

test('拒绝审批后提交 REJECT 并移除卡片', async ({ page }) => {
  const card = await triggerApproval(page);

  const submitted = page.waitForRequest(
    (req) => req.url().includes('/api/agent/approvals/ap-1') && req.method() === 'POST'
  );
  await card.getByRole('button', { name: /^拒绝/ }).click();

  expect((await submitted).postDataJSON()).toMatchObject({ decision: 'REJECT' });
  await expect(card).toHaveCount(0);
});

/** P5-10-2：发送一条会触发多个审批的消息，返回输入框以便失焦 */
async function triggerMultiApproval(
  page: Parameters<typeof mockApi>[0],
  items: Array<{ id: string; tool: string; risk: string; path?: string; command?: string }>
) {
  await mockApi(page, {
    stream: multiApprovalStream(items),
    runEvents: { 102: { runId: 102, state: 'WAITING_APPROVAL', events: [] } }
  });
  await page.goto('/chat');
  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('执行多项操作');
  await input.press('Enter');
  await expect(page.locator('.approval-card')).toHaveCount(items.length);
  // 输入框仍有焦点时快捷键不生效，先失焦模拟真实键盘操作
  await input.evaluate((el) => (el as HTMLTextAreaElement).blur());
  return input;
}

test('J/K 在当前审批队列移动，A/D 只处理选中项', async ({ page }) => {
  await triggerMultiApproval(page, [
    { id: 'ap-1', tool: 'write_file', risk: 'WRITE', path: 'src/first.ts' },
    { id: 'ap-2', tool: 'edit_file', risk: 'WRITE', path: 'src/second.ts' }
  ]);

  const selected = page.locator('.approval-card.selected');
  await expect(selected).toContainText('first.ts');

  await page.keyboard.press('j');
  await expect(selected).toContainText('second.ts');

  const approved = page.waitForRequest(
    (req) => req.url().includes('/api/agent/approvals/ap-2') && req.method() === 'POST'
  );
  await page.keyboard.press('a');
  expect((await approved).postDataJSON()).toMatchObject({ decision: 'APPROVE' });

  // 选中项被处理后收口到剩余项
  await expect(selected).toContainText('first.ts');
  const rejected = page.waitForRequest(
    (req) => req.url().includes('/api/agent/approvals/ap-1') && req.method() === 'POST'
  );
  await page.keyboard.press('d');
  expect((await rejected).postDataJSON()).toMatchObject({ decision: 'REJECT' });
});

test('混合队列批量只提交低风险项，高危仍需逐条处理', async ({ page }) => {
  await triggerMultiApproval(page, [
    { id: 'ap-1', tool: 'write_file', risk: 'WRITE', path: 'src/first.ts' },
    { id: 'ap-2', tool: 'edit_file', risk: 'WRITE', path: 'src/second.ts' },
    { id: 'ap-3', tool: 'run_command', risk: 'EXECUTE', command: 'rm -rf build' }
  ]);

  const batchRequested = page.waitForRequest(
    (req) => req.url().includes('/api/agent/approvals/batch') && req.method() === 'POST'
  );
  await page.getByRole('button', { name: /允许全部低风险（2）/ }).click();

  expect((await batchRequested).postDataJSON()).toEqual({
    sessionId: 1,
    approvalIds: ['ap-1', 'ap-2']
  });

  // 低风险卡片自动消失，命令审批保留且批量入口隐藏
  await expect(page.locator('.approval-card')).toHaveCount(1);
  await expect(page.locator('[data-approval-id="ap-3"]')).toContainText('rm -rf build');
  await expect(page.locator('.approval-batch-btn')).toHaveCount(0);
});

test('追踪页回看审批历史来源，切会话不串历史', async ({ page }) => {
  await mockApi(page, {
    sessions: [
      { id: 1, title: '当前会话', projectId: 1, updateTime: '2026-11-14T10:00:00' },
      { id: 2, title: '另一个会话', projectId: 1, updateTime: '2026-11-14T09:00:00' }
    ],
    approvalHistory: {
      1: [
        { approvalId: 'ap-a', tool: 'write_file', risk: 'WRITE', state: 'APPROVED', decisionSource: 'USER', decisionTime: '2026-11-14T10:01:00', createTime: '2026-11-14T10:00:30', rememberSession: false },
        { approvalId: 'ap-b', tool: 'edit_file', risk: 'WRITE', state: 'APPROVED', decisionSource: 'BATCH', decisionTime: '2026-11-14T10:02:00', createTime: '2026-11-14T10:00:40', rememberSession: false },
        { approvalId: 'ap-c', tool: 'run_command', risk: 'EXECUTE', state: 'REJECTED', decisionSource: 'TIMEOUT', decisionTime: '2026-11-14T10:03:00', createTime: '2026-11-14T10:01:00', rememberSession: false },
        { approvalId: 'ap-d', tool: 'delete_file', risk: 'DESTRUCTIVE', state: 'REJECTED', decisionSource: 'SESSION_STOP', decisionTime: '2026-11-14T10:04:00', createTime: '2026-11-14T10:02:00', rememberSession: false },
        { approvalId: 'ap-e', tool: 'write_file', risk: 'WRITE', state: 'APPROVED', decisionSource: null, decisionTime: '2026-11-14T10:05:00', createTime: '2026-11-14T10:03:00', rememberSession: true }
      ],
      2: []
    }
  });
  await page.goto('/chat');
  // 打开已有会话（不在运行中，才能验证切会话隔离）
  await page.locator('.session-item', { hasText: '当前会话' }).click();

  // 打开右侧工作台，再切到追踪页
  await page.getByTitle('任务详情侧边栏').click();
  await page.getByRole('button', { name: /追踪/ }).click();
  const history = page.locator('.trace-approval-history');
  await expect(history).toBeVisible();
  await expect(history).toContainText('本人 · 批量');
  await expect(history).toContainText('超时自动拒绝');
  await expect(history).toContainText('会话停止');
  await expect(history).toContainText('来源未知');
  await expect(history).toContainText('本会话总是允许');

  // 切到另一个会话：旧会话历史不能串过来
  await page.locator('.session-item', { hasText: '另一个会话' }).click();
  await expect(page.locator('.trace-approval-history')).toHaveCount(0);
});
