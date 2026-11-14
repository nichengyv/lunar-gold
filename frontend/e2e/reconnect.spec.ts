import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures/api';

test('刷新或切回会话时按 runId 续上未完成回答', async ({ page }) => {
  await mockApi(page, {
    sessions: [{ id: 1, title: '断线恢复', projectId: 1, updateTime: '2026-11-14T10:00:00' }],
    messages: { 1: [{ id: 1, role: 'USER', content: '继续写', trace: null }] },
    // 恢复成功后前端会重拉历史，后端此时已落库完整回答
    messagesAfterRecovery: {
      1: [
        { id: 1, role: 'USER', content: '继续写', trace: null },
        { id: 2, role: 'ASSISTANT', content: '恢复后的内容', trace: null }
      ]
    },
    runningRuns: [{ runId: 42, sessionId: 1, state: 'RUNNING' }],
    runEvents: {
      42: {
        runId: 42,
        state: 'SUCCEEDED',
        events: [
          { seq: 1, type: 'MODEL_CHUNK', payload: JSON.stringify({ text: '恢复后的内容' }) }
        ]
      }
    }
  });

  await page.goto('/chat');
  await page.getByText('断线恢复').first().click();

  await expect(page.getByText('恢复后的内容')).toBeVisible();
  await expect(page.getByText('已恢复：内容已从断点续上')).toBeVisible();
});
