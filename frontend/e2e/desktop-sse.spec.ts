import { expect, test } from '@playwright/test';
import { E2E_TOKEN, mockApi } from './fixtures/api';

/**
 * P7-05：桌面模式 SSE 端到端。
 *
 * 与既有 chat-stream / approval / reconnect 用例的区别：
 * 1. 不预置本地令牌，走 /api/meta → /api/desktop/token 的真实桌面引导路径；
 * 2. 覆盖完整事件序列渲染（thinking / tool / plan / content / done）；
 * 3. 覆盖「审批挂起 → 批准 → 后端继续输出」与「断流 → 按 runId 恢复 → 终态」。
 *
 * 说明：Playwright 的 route.fulfill 无法逐块推送 SSE，
 * 因此「逐块到达的中间态」仍由 Vitest 覆盖；这里验证真实组件与 store 对事件的消费。
 */

test('桌面端无登录页：自动换取本地令牌并消费完整 SSE 事件序列', async ({ page }) => {
  const tokenRequest = page.waitForRequest(
    (req) => req.url().includes('/api/desktop/token') && req.method() === 'GET'
  );
  await mockApi(page, {
    seedToken: false,
    stream: [
      'event: session_id\ndata: 1\n\n',
      'event: run_id\ndata: 101\n\n',
      'event: turn_id\ndata: turn-desktop\n\n',
      'event: thinking\ndata: {"thought":"先读配置"}\n\n',
      'event: tool_call\ndata: {"tool":"read_file","args":"{}"}\n\n',
      'event: tool_result\ndata: {"tool":"read_file","success":true,"content":"ok"}\n\n',
      'event: plan\ndata: {"steps":[{"title":"读取配置","status":"completed"},{"title":"修改入口","status":"in_progress"}],"updatedAt":1}\n\n',
      'event: content\ndata: {"text":"桌面端流式回答"}\n\n',
      'event: content\ndata: {"text":"桌面端流式回答，已收尾。"}\n\n',
      'event: done\ndata: {}\n\n'
    ].join('')
  });

  await page.goto('/chat');

  // 桌面端本地单用户：不经过登录页，令牌由引导接口写入
  await expect(page).toHaveURL(/\/chat/);
  await expect(tokenRequest).resolves.toBeTruthy();
  expect(await page.evaluate(() => localStorage.getItem('token'))).toBe(E2E_TOKEN);

  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('改一下入口');
  await input.press('Enter');

  await expect(page.getByText('桌面端流式回答，已收尾。')).toBeVisible();
  // plan 事件驱动计划条，tool/thinking 进入折叠进度区
  await expect(page.getByText('读取配置')).toBeVisible();
  await expect(page.locator('.progress-toggle')).toBeVisible();
  // done 后回到可输入状态
  await expect(page.locator('.btn-stop')).toHaveCount(0);
  await expect(input).toHaveValue('');
  await expect(input).toBeEnabled();
});

test('桌面端审批卡：批准后后端继续输出并恢复为完成', async ({ page }) => {
  // 审批挂起期间 Run 保持 WAITING_APPROVAL；用户批准后由事件轮询续上后续输出
  let approved = false;
  await mockApi(page, {
    seedToken: false,
    stream: [
      'event: session_id\ndata: 1\n\n',
      'event: run_id\ndata: 102\n\n',
      'event: turn_id\ndata: turn-approval\n\n',
      'event: content\ndata: {"text":"准备写入文件。"}\n\n',
      `event: approval_required\ndata: ${JSON.stringify({
        approvalId: 'ap-1',
        tool: 'write_file',
        risk: 'WRITE',
        label: '写入文件',
        preview: {
          kind: 'write',
          path: 'src/demo.ts',
          newFile: false,
          additions: 1,
          deletions: 0,
          diff: '@@\n+hello'
        },
        expiresAt: Date.now() + 120_000
      })}\n\n`
    ].join(''),
    runEvents: {
      102: () =>
        approved
          ? {
              runId: 102,
              state: 'SUCCEEDED',
              events: [
                { seq: 1, type: 'MODEL_CHUNK', payload: JSON.stringify({ text: '已批准继续' }) }
              ]
            }
          : { runId: 102, state: 'WAITING_APPROVAL', events: [] }
    },
    messagesAfterRecovery: {
      1: [
        { id: 1, role: 'USER', content: '修改 demo.ts', trace: null },
        { id: 2, role: 'ASSISTANT', content: '准备写入文件。已批准继续', trace: null }
      ]
    }
  });

  await page.goto('/chat');
  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('修改 demo.ts');
  await input.press('Enter');

  const card = page.locator('.approval-card');
  await expect(card).toBeVisible();
  await expect(card).toContainText('写入文件');

  const submitted = page.waitForRequest(
    (req) => req.url().includes('/api/agent/approvals/ap-1') && req.method() === 'POST'
  );
  // 「本会话总是允许」也含「允许」，必须锚定到主按钮「允许A」
  await card.getByRole('button', { name: /^允许/ }).click();
  expect((await submitted).postDataJSON()).toMatchObject({ decision: 'APPROVE' });
  approved = true;

  await expect(card).toHaveCount(0);
  await expect(page.getByText('准备写入文件。已批准继续')).toBeVisible();
  await expect(page.locator('.stream-status')).toContainText('已恢复');
});

test('桌面端断流：按 runId 从运行中续到成功终态', async ({ page }) => {
  await mockApi(page, {
    seedToken: false,
    // 连接在 done 之前被掐断，只收到半截正文
    stream: [
      'event: session_id\ndata: 1\n\n',
      'event: run_id\ndata: 42\n\n',
      'event: turn_id\ndata: turn-disconnected\n\n',
      'event: content\ndata: {"text":"部分内容"}\n\n'
    ].join(''),
    runEvents: {
      42: (poll) =>
        poll === 1
          ? {
              runId: 42,
              state: 'RUNNING',
              events: [{ seq: 1, type: 'MODEL_CHUNK', payload: JSON.stringify({ text: '部分内容' }) }]
            }
          : {
              runId: 42,
              state: 'SUCCEEDED',
              events: [
                { seq: 1, type: 'MODEL_CHUNK', payload: JSON.stringify({ text: '部分内容' }) },
                { seq: 2, type: 'MODEL_CHUNK', payload: JSON.stringify({ text: '，已续上' }) }
              ]
            }
    },
    messagesAfterRecovery: {
      1: [
        { id: 1, role: 'USER', content: '写一段', trace: null },
        { id: 2, role: 'ASSISTANT', content: '部分内容，已续上', trace: null }
      ]
    }
  });

  await page.goto('/chat');
  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('写一段');
  await input.press('Enter');

  await expect(page.getByText('部分内容，已续上')).toBeVisible();
  await expect(page.locator('.stream-status')).toContainText('已恢复');
  await expect(page.locator('.btn-send')).toBeVisible();
});

test('桌面端断流：Run 已终态失败时展示原因而不是一直等待', async ({ page }) => {
  await mockApi(page, {
    seedToken: false,
    stream: [
      'event: session_id\ndata: 1\n\n',
      'event: run_id\ndata: 43\n\n',
      'event: content\ndata: {"text":"执行到一半"}\n\n'
    ].join(''),
    runEvents: {
      43: () => ({
        runId: 43,
        state: 'FAILED',
        errorCode: 'PROCESS_RESTARTED',
        errorMessage: '应用重启，原执行线程已终止',
        events: [{ seq: 1, type: 'MODEL_CHUNK', payload: JSON.stringify({ text: '执行到一半' }) }]
      })
    }
  });

  await page.goto('/chat');
  const input = page.getByPlaceholder(/输入消息/);
  await input.fill('继续跑');
  await input.press('Enter');

  await expect(page.locator('.failed-text')).toContainText('应用重启，原执行线程已终止');
  await expect(page.locator('.stream-status')).toContainText('连接中断，已保留已生成的内容');
  // 已生成的内容不能因为失败被清空
  await expect(page.getByText('执行到一半')).toBeVisible();
});