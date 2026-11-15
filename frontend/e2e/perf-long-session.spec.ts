import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures/api';

/**
 * P5-11-3：长会话性能剖析。
 *
 * 默认跳过（`PERF=1` 才运行），避免 60 秒用例拖慢日常 E2E；
 * 数据用于验收「500 条会话首屏 < 300ms、DOM < 500、流式无长任务」。
 * 建议对生产构建（vite preview）运行，dev server 的数字不代表用户实际体验。
 */
const ENABLED = process.env.PERF === '1';
const STREAM_SECONDS = Number(process.env.PERF_STREAM_SECONDS ?? 60);

const PARAGRAPH = '这里是一段用于模拟真实长回答的文字，包含中文说明、行内代码 `run()` 与结论。';

function assistantContent(turn: number): string {
  return [
    `## 第 ${turn} 轮结论`,
    '',
    `${PARAGRAPH}${PARAGRAPH}`,
    '',
    '- 检查了入口与依赖',
    '- 确认了异常分支',
    '',
    '```ts',
    `export function check${turn}(value: number): boolean {`,
    '  return value > 0;',
    '}',
    '```',
    '',
    '结论：当前实现可用，建议补充边界用例。'
  ].join('\n');
}

function longSessionMessages(count: number) {
  const messages: Array<{ id: number; role: string; content: string; trace: null }> = [];
  for (let i = 0; i < count; i++) {
    const isUser = i % 2 === 0;
    const turn = Math.floor(i / 2) + 1;
    messages.push({
      id: i + 1,
      role: isUser ? 'USER' : 'ASSISTANT',
      content: isUser ? `第 ${turn} 轮：帮我检查 src/module-${turn}.ts 的实现是否完整。` : assistantContent(turn),
      trace: null
    });
  }
  return messages;
}

type LongTaskEntry = { start: number; duration: number };
type PerfWindow = Window & {
  __longTasks?: LongTaskEntry[];
  fetch: typeof fetch;
};

test.describe('@perf 长会话性能', () => {
  test.skip(!ENABLED, '设置 PERF=1 运行长会话性能剖析');
  test.setTimeout((STREAM_SECONDS + 90) * 1000);

  test('500 条消息首屏、DOM 与 60 秒流式长任务', async ({ page }) => {
    const chunkMs = 33; // 约 30 chunk/s，与真实模型输出接近
    await page.addInitScript(
      ({ durationMs, intervalMs }) => {
        const perfWindow = window as PerfWindow;
        perfWindow.__longTasks = [];
        try {
          new PerformanceObserver((list) => {
            for (const entry of list.getEntries()) {
              perfWindow.__longTasks!.push({ start: Math.round(entry.startTime), duration: Math.round(entry.duration) });
            }
          }).observe({ type: 'longtask', buffered: true });
        } catch {
          // 不支持 longtask 的浏览器降级为不采集，不阻塞用例
        }

        const originalFetch = window.fetch.bind(window);
        window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
          const url =
            typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
          if (!url.includes('/api/agent/chat/stream')) return originalFetch(input as never, init);

          const encoder = new TextEncoder();
          const body = new ReadableStream<Uint8Array>({
            start(controller) {
              const send = (event: string, data: string) =>
                controller.enqueue(encoder.encode(`event: ${event}\ndata: ${data}\n\n`));
              send('session_id', '1');
              send('run_id', '900');
              send('turn_id', 'turn-perf');

              let tick = 0;
              let elapsed = 0;
              const timer = setInterval(() => {
                tick += 1;
                elapsed += intervalMs;
                // 每 10 个 chunk 形成一个新段落，模拟 Markdown 逐段增长
                const delta =
                  tick % 10 === 0
                    ? `第 ${tick / 10} 段：流式内容持续追加，用于观察主线程长任务。\n\n`
                    : '流式文本';
                send('content', JSON.stringify({ text: delta }));
                if (elapsed >= durationMs) {
                  clearInterval(timer);
                  send('done', '{}');
                  controller.close();
                }
              }, intervalMs);
            }
          });
          return Promise.resolve(
            new Response(body, {
              status: 200,
              headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' }
            })
          );
        };
      },
      { durationMs: STREAM_SECONDS * 1000, intervalMs: chunkMs }
    );

    await mockApi(page, {
      // 先放一个短会话：进入页面会自动选中第一条，测量前先切走，避免点击当前会话是空操作
      sessions: [
        { id: 2, title: '短会话', projectId: 1, updateTime: '2026-11-14T10:00:00' },
        { id: 1, title: '长会话性能', projectId: 1, updateTime: '2026-11-14T10:00:00' }
      ],
      messages: {
        1: longSessionMessages(500),
        2: [{ id: 900, role: 'USER', content: '短会话的唯一消息', trace: null }]
      }
    });

    await page.goto('/chat');
    await page.getByText('短会话').first().click();
    await expect(page.getByText('短会话的唯一消息')).toBeVisible();

    const firstMessage = page.locator('[data-msg-id]').first();

    // 首屏：从会话点击事件到第一条消息进入 DOM（含会话接口与首次渲染，不含 Playwright 的点击等待）
    await page.evaluate(() => {
      const perfWindow = window as PerfWindow & { __clickAt?: number; __firstScreen?: number | null };
      perfWindow.__clickAt = undefined;
      perfWindow.__firstScreen = null;
      document.addEventListener(
        'click',
        () => {
          perfWindow.__clickAt = performance.now();
        },
        { capture: true, once: true }
      );
      const zone = document.querySelector('.messages');
      if (!zone) return;
      const observer = new MutationObserver(() => {
        if (!perfWindow.__clickAt || document.querySelector('[data-msg-id]') == null) return;
        perfWindow.__firstScreen = performance.now() - perfWindow.__clickAt;
        observer.disconnect();
      });
      observer.observe(zone, { childList: true, subtree: true });
    });

    await page.getByText('长会话性能').first().click();
    await expect(firstMessage).toBeVisible();
    const firstScreenMs = Math.round(
      (await page.evaluate(() => (window as PerfWindow & { __firstScreen?: number | null }).__firstScreen)) ?? -1
    );

    const dom = await page.evaluate(() => {
      const zone = document.querySelector('.messages');
      return {
        messageZone: zone ? zone.querySelectorAll('*').length : 0,
        renderedMessages: document.querySelectorAll('[data-msg-id]').length
      };
    });

    // 流式 60 秒：输入一句触发 mock 流，结束后读取长任务
    const input = page.getByPlaceholder(/输入消息/);
    await input.fill('继续补充说明');
    const streamStart = await page.evaluate(() => performance.now());
    await input.press('Enter');
    await expect(page.locator('.btn-send')).toBeVisible({ timeout: (STREAM_SECONDS + 30) * 1000 });
    await expect(page.getByText('流式内容持续追加').first()).toBeVisible();

    const longTasks = await page.evaluate((startedAt) => {
      // 只统计流式开始之后的长任务，排除页面启动与会话加载
      const list = ((window as PerfWindow).__longTasks ?? []).filter((task) => task.start >= startedAt);
      return {
        count: list.length,
        max: list.length ? Math.max(...list.map((task) => task.duration)) : 0,
        over50: list.filter((task) => task.duration > 50).map((task) => task.duration)
      };
    }, streamStart);

    const report = { firstScreenMs, dom, longTasks: { count: longTasks.count, max: longTasks.max, over50: longTasks.over50.length } };
    console.log(`[perf] ${JSON.stringify(report)}`);

    expect(firstScreenMs).toBeLessThan(300);
    expect(dom.messageZone).toBeLessThan(500);
    expect(dom.renderedMessages).toBeLessThan(60);
    expect(longTasks.over50).toEqual([]);
  });
});
