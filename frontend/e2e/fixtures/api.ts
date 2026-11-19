import type { Page } from '@playwright/test';
import type { ChangeSetView } from '../../src/types/api';

/**
 * P5-11-1：E2E 后端仿真。
 *
 * 原计划是启动一个 e2e profile 的 Spring 后端（嵌入 PG + ScriptedAgentClient），
 * 但 ScriptedAgentClient 位于 test scope、桌面端登录依赖本机令牌文件，
 * 在 Playwright 外部进程里复现成本高且不稳定。这里改为在浏览器层拦截 /api/**，
 * 用确定性夹具驱动真实前端组件、store、SSE 解析与交互，
 * 覆盖 UI 回归目标；后端契约本身已由 ChangeSetServiceTest 等单测覆盖。
 */
export const E2E_TOKEN = 'e2e-token';

export const E2E_PROJECT = {
  id: 1,
  name: 'E2E 工作区',
  workspacePath: 'D:\\e2e-workspace',
  defaultFlag: true,
  defaultPermission: 'WRITE'
};

const ok = (data: unknown = null) => ({ code: 200, message: 'ok', data });

export function changeSetFixture(overrides: Partial<ChangeSetView> = {}): ChangeSetView {
  const base: ChangeSetView = {
    sessionId: 1,
    projectId: 1,
    runFilter: null,
    status: 'UNVERIFIED',
    statusLabel: '未验证',
    accepted: false,
    verification: { status: 'UNVERIFIED', label: '未验证', message: null, commands: [] },
    approvals: {
      requested: 1,
      resolved: 1,
      items: [{ approvalId: 'ap-1', tool: 'write_file', decision: 'APPROVE', runId: 101, turnId: 'turn-e2e' }]
    },
    totals: { files: 1, additions: 2, deletions: 1, pending: 1, accepted: 0, reverted: 0 },
    runs: [
      {
        runId: 101,
        turnId: 'turn-e2e',
        state: 'SUCCEEDED',
        prompt: '修改 demo.ts',
        completion: { status: 'UNVERIFIED', reason: 'NO_VERIFICATION', filesChanged: true },
        verification: { status: 'UNVERIFIED', label: '未验证', message: '没有可用的验证命令', commands: [] },
        approvals: [],
        files: 1,
        additions: 2,
        deletions: 1
      }
    ],
    changes: [
      {
        id: 9001,
        runId: 101,
        turnId: 'turn-e2e',
        filePath: 'src/demo.ts',
        action: 'modify',
        source: 'agent',
        additions: 2,
        deletions: 1,
        diffText: '@@ -1 +1 @@\n-old\n+new',
        status: 'PENDING'
      }
    ],
    continuePrompt: ''
  };
  return { ...base, ...overrides };
}

export type ApiMockOptions = {
  sessions?: unknown[];
  messages?: Record<number, unknown[]>;
  /** 断点恢复成功后再拉会话历史时返回的完整消息（模拟后端已落库的完整回答） */
  messagesAfterRecovery?: Record<number, unknown[]>;
  runningRuns?: unknown[];
  /** P7-05：运行事件按轮询次数返回不同快照，用于「运行中 → 终态」的断流恢复 */
  runEvents?: Record<number, unknown | ((poll: number) => unknown)>;
  /**
   * P7-05：是否预置本地令牌。桌面端默认由 /api/desktop/token 自动换取，
   * 置 false 可覆盖路由守卫的本地令牌引导路径。
   */
  seedToken?: boolean;
  /** 聊天 SSE 响应体；函数形式可按请求体动态生成 */
  stream?: string | ((body: Record<string, unknown>, attempt: number) => string);
  /** 流式响应前的延迟，用于「生成中停止」场景 */
  streamDelayMs?: number;
  changeSet?: ChangeSetView | null;
  changeSetAfterChange?: ChangeSetView | null;
  pendingChanges?: unknown[];
  revertResult?: unknown;
  /** P5-10-2：会话内审批历史，按 sessionId 返回 */
  approvalHistory?: Record<number, unknown[]>;
  /** P5-10-2：批量允许返回的批次与完成项；默认原样返回请求中的 approvalIds */
  batchId?: string;
  batchResolvedIds?: string[];
  /** P5-11-2：体验诊断页使用的当前用户后端聚合快照 */
  experienceMetrics?: unknown;
};

export function defaultStream(): string {
  return [
    'event: session_id\ndata: 1\n\n',
    'event: run_id\ndata: 101\n\n',
    'event: turn_id\ndata: turn-e2e\n\n',
    `event: content\ndata: ${JSON.stringify({ text: '你好，我是 E2E 助手。' })}\n\n`,
    'event: done\ndata: {}\n\n'
  ].join('');
}

export function approvalStream(): string {
  return [
    'event: session_id\ndata: 1\n\n',
    'event: run_id\ndata: 102\n\n',
    'event: turn_id\ndata: turn-approval\n\n',
    `event: content\ndata: ${JSON.stringify({ text: '准备写入文件。' })}\n\n`,
    `event: approval_required\ndata: ${JSON.stringify({
      approvalId: 'ap-1',
      tool: 'write_file',
      risk: 'WRITE',
      label: '写入文件',
      preview: { kind: 'write', path: 'src/demo.ts', newFile: false, additions: 1, deletions: 0, diff: '@@\n+hello' },
      expiresAt: Date.now() + 120_000
    })}\n\n`
  ].join('');
}

/** P5-10-2：同一轮内多个审批，用于 J/K 与批量允许场景 */
export function multiApprovalStream(
  items: Array<{ id: string; tool: string; risk: string; path?: string; command?: string }>
): string {
  return [
    'event: session_id\ndata: 1\n\n',
    'event: run_id\ndata: 102\n\n',
    'event: turn_id\ndata: turn-approval\n\n',
    `event: content\ndata: ${JSON.stringify({ text: '准备执行多项操作。' })}\n\n`,
    ...items.map((item) => {
      const preview = item.command
        ? { kind: 'command', command: item.command }
        : {
            kind: 'write',
            path: item.path ?? 'src/demo.ts',
            newFile: false,
            additions: 1,
            deletions: 0,
            diff: '@@\n+hello'
          };
      return `event: approval_required\ndata: ${JSON.stringify({
        approvalId: item.id,
        tool: item.tool,
        risk: item.risk,
        label: item.tool,
        preview,
        expiresAt: Date.now() + 120_000
      })}\n\n`;
    })
  ].join('');
}

export async function mockApi(page: Page, options: ApiMockOptions = {}): Promise<void> {
  if (options.seedToken !== false) {
    await page.addInitScript((token) => {
      localStorage.setItem('token', token);
      localStorage.setItem('username', 'e2e');
    }, E2E_TOKEN);
  }

  let changeSet = options.changeSet ?? null;
  let streamAttempt = 0;
  const runEventPolls: Record<number, number> = {};
  // 一旦前端按 runId 拉过运行事件，就认为后端已把完整回答落库
  let runEventsFetched = false;

  const meta = {
    mode: 'DESKTOP',
    version: 'e2e',
    authMode: 'LOCAL_SINGLE_USER',
    capabilities: ['CHAT', 'KNOWLEDGE', 'LOCAL_WORKSPACE', 'COMMAND_EXEC', 'GIT', 'MCP_READ', 'MCP_WRITE', 'LSP', 'APPROVAL'],
    download: { enabled: false, latestVersion: null }
  };

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    // 只拦截后端接口，避免误伤 /src/api/*.ts 等前端模块请求
    if (!path.startsWith('/api/')) return route.continue();

    if (path === '/api/meta') {
      return route.fulfill({ json: meta });
    }
    if (path === '/api/health') {
      return route.fulfill({ json: ok({ status: 'UP' }) });
    }
    if (path === '/api/desktop/token' || path === '/api/auth/local-token') {
      return route.fulfill({ json: ok({ token: E2E_TOKEN }) });
    }
    if (path === '/api/projects' && method === 'GET') {
      return route.fulfill({ json: ok([E2E_PROJECT]) });
    }
    if (/^\/api\/projects\/\d+$/.test(path)) {
      return route.fulfill({ json: ok(E2E_PROJECT) });
    }
    // 工作台文件标签打开时读取文件内容；返回一段文本避免编辑器拿到 null
    if (/^\/api\/projects\/\d+\/workspace\/file$/.test(path) && method === 'GET') {
      return route.fulfill({ json: ok('const demo = 1;\n') });
    }
    if (path === '/api/settings/defaults') {
      return route.fulfill({ json: ok({ model: 'e2e-model', reasoning: 'STANDARD', permission: 'WRITE' }) });
    }
    if (path === '/api/agent/metrics/experience' && method === 'GET') {
      return route.fulfill({
        json: ok(options.experienceMetrics ?? {
          generatedAt: '2026-11-14T12:00:00Z',
          windowRuns: 0,
          firstToken: { p95Ms: null, sampleCount: 0 },
          tool: { failureRate: null, totalCount: 0, failedCount: 0, scope: 'PROCESS' },
          approval: { averageWaitMs: null, sampleCount: 0, byEntry: [] },
          tokens: { averageTotalTokens: null, totalTokens: 0, sampleCount: 0 }
        })
      });
    }
    if (path === '/api/agent/sessions' && method === 'GET') {
      return route.fulfill({ json: ok(options.sessions ?? []) });
    }
    // P5-10-2：会话内审批历史
    const approvalsPath = path.match(/^\/api\/agent\/sessions\/(\d+)\/approvals$/);
    if (approvalsPath && method === 'GET') {
      return route.fulfill({ json: ok(options.approvalHistory?.[Number(approvalsPath[1])] ?? []) });
    }
    if (/^\/api\/agent\/sessions\/\d+\/messages$/.test(path)) {
      const sessionId = Number(path.split('/')[4]);
      const history =
        (runEventsFetched && options.messagesAfterRecovery?.[sessionId]) ||
        options.messages?.[sessionId] ||
        [];
      return route.fulfill({ json: ok(history) });
    }
    if (path === '/api/agent/runs/running') {
      return route.fulfill({ json: ok(options.runningRuns ?? []) });
    }
    if (/^\/api\/agent\/runs\/\d+\/events$/.test(path)) {
      runEventsFetched = true;
      const runId = Number(path.split('/')[4]);
      runEventPolls[runId] = (runEventPolls[runId] ?? 0) + 1;
      const entry = options.runEvents?.[runId];
      const snapshot =
        typeof entry === 'function'
          ? entry(runEventPolls[runId])
          : (entry ?? { runId, state: 'SUCCEEDED', events: [] });
      return route.fulfill({ json: ok(snapshot) });
    }
    if (path === '/api/agent/chat/stream') {
      streamAttempt += 1;
      if (options.streamDelayMs) {
        await new Promise((resolve) => setTimeout(resolve, options.streamDelayMs));
      }
      const body = options.stream;
      const text =
        typeof body === 'function'
          ? body({}, streamAttempt)
          : typeof body === 'string'
            ? body
            : defaultStream();
      try {
        return await route.fulfill({
          status: 200,
          headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' },
          body: text
        });
      } catch {
        // 前端主动 abort（停止生成）后请求已断开，忽略
        return;
      }
    }
    if (path === '/api/agent/chat/stop') {
      return route.fulfill({ json: ok(null) });
    }
    // P5-10-2：批量允许必须排在单条审批路由之前
    if (path === '/api/agent/approvals/batch' && method === 'POST') {
      const body = request.postDataJSON() as { approvalIds?: string[] };
      return route.fulfill({
        json: ok({
          batchId: options.batchId ?? 'bt-e2e',
          resolvedIds: options.batchResolvedIds ?? body.approvalIds ?? []
        })
      });
    }
    if (/^\/api\/agent\/approvals\//.test(path)) {
      return route.fulfill({ json: ok(null) });
    }
    if (path === '/api/file-changes' && method === 'GET') {
      return route.fulfill({ json: options.pendingChanges ?? [] });
    }
    if (path === '/api/file-changes/changeset') {
      return route.fulfill({ json: changeSet });
    }
    if (path === '/api/file-changes/accept-all' || /^\/api\/file-changes\/\d+\/accept$/.test(path)) {
      if (options.changeSetAfterChange !== undefined) changeSet = options.changeSetAfterChange;
      // 这两个接口返回结构化结果而不是统一响应包装
      return route.fulfill({ json: { ok: true, count: 1 } });
    }
    if (path === '/api/file-changes/revert-session') {
      if (options.changeSetAfterChange !== undefined) changeSet = options.changeSetAfterChange;
      return route.fulfill({
        json:
          options.revertResult ?? { ok: true, count: 1, failed: 0, skippedAccepted: 0, conflicts: [] }
      });
    }
    if (/^\/api\/file-changes\/\d+\/revert$/.test(path)) {
      return route.fulfill({ json: { ok: true, error: null } });
    }
    if (path === '/api/file-changes/revert-run' || path === '/api/file-changes/revert-all') {
      return route.fulfill({
        json: options.revertResult ?? { ok: true, count: 1, failed: 0, skippedAccepted: 0, conflicts: [] }
      });
    }

    return route.fulfill({ json: ok(null) });
  });
}
