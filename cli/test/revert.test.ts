import assert from 'node:assert/strict'
import { join } from 'node:path'
import { test } from 'node:test'
import { execute } from '../src/cli.ts'
import { EXIT } from '../src/errors.ts'
import { revertCommand } from '../src/commands/revert.ts'
import {
  capture,
  errorResponse,
  jsonResponse,
  startStub,
  tempHome,
  testConfig,
  testContext
} from './helpers.ts'

const RESULT = { ok: true, count: 3, failed: 0, skippedAccepted: 1, conflicts: [] }

const CHANGESET = {
  sessionId: 12,
  projectId: null,
  runFilter: null,
  status: 'PENDING',
  statusLabel: '待处理',
  accepted: false,
  totals: { files: 1, additions: 2, deletions: 1, pending: 1, accepted: 0, reverted: 0 },
  runs: [],
  changes: [
    {
      id: 1,
      runId: 34,
      turnId: 'turn-a',
      filePath: 'src/a.ts',
      action: 'MODIFY',
      source: 'write_file',
      additions: 2,
      deletions: 1,
      diffText: '',
      status: 'PENDING',
      createTime: '2026-11-16T10:00:00'
    }
  ],
  continuePrompt: null
}

test('revert 非 TTY 且没有 --yes：退出 2，且不发写请求', async () => {
  const stub = await startStub((request, response) => {
    jsonResponse(response, CHANGESET)
  })
  try {
    const { output, out, err } = capture()
    const home = tempHome()
    const code = await execute(
      ['revert', '--session', '12', '--base-url', stub.baseUrl],
      {
        out,
        err,
        isTty: false,
        env: {
          LG_CONFIG_PATH: join(home, 'config.json'),
          LG_LOCAL_TOKEN_PATH: join(home, 'local-token')
        }
      }
    )
    assert.equal(code, EXIT.USAGE)
    assert.match(output.err, /--yes/)
    assert.equal(stub.requests.length, 0)
  } finally {
    await stub.close()
  }
})

test('revert --yes：查询参数调用 revert-session，无 body', async () => {
  const stub = await startStub((request, response) => {
    if (request.path === '/api/file-changes/revert-session') {
      assert.equal(request.query.get('sessionId'), '12')
      assert.equal(request.body, '')
      jsonResponse(response, RESULT)
      return
    }
    errorResponse(response, 404, 'unexpected')
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl }) })
    const code = await revertCommand(ctx, { sessionId: 12, yes: true })
    assert.equal(code, EXIT.OK)
    assert.equal(stub.requests[0].method, 'POST')
    assert.match(ctx.output.out, /撤回完成：成功 3 个/)
  } finally {
    await stub.close()
  }
})

test('revert --run：调用 revert-run 且带 runId 查询参数', async () => {
  const stub = await startStub((request, response) => {
    assert.equal(request.path, '/api/file-changes/revert-run')
    assert.equal(request.query.get('sessionId'), '12')
    assert.equal(request.query.get('runId'), '34')
    assert.equal(request.body, '')
    jsonResponse(response, RESULT)
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl }) })
    const code = await revertCommand(ctx, { sessionId: 12, runId: 34, yes: true })
    assert.equal(code, EXIT.OK)
  } finally {
    await stub.close()
  }
})

test('revert 交互确认：展示影响面后确认才发请求', async () => {
  const stub = await startStub((request, response) => {
    if (request.path === '/api/file-changes/changeset') {
      jsonResponse(response, CHANGESET)
      return
    }
    jsonResponse(response, RESULT)
  })
  try {
    const ctx = testContext({
      config: testConfig({ baseUrl: stub.baseUrl }),
      isTty: true,
      confirm: async () => true
    })
    const code = await revertCommand(ctx, { sessionId: 12, yes: false })
    assert.equal(code, EXIT.OK)
    assert.match(ctx.output.out, /将撤回 session=12 的 1 个 PENDING 变更/)
    assert.equal(stub.requests.at(-1)?.path, '/api/file-changes/revert-session')
  } finally {
    await stub.close()
  }
})

test('revert 交互确认拒绝：不发写请求，退出 0', async () => {
  const stub = await startStub((request, response) => {
    jsonResponse(response, CHANGESET)
  })
  try {
    const ctx = testContext({
      config: testConfig({ baseUrl: stub.baseUrl }),
      isTty: true,
      confirm: async () => false
    })
    const code = await revertCommand(ctx, { sessionId: 12, yes: false })
    assert.equal(code, EXIT.OK)
    assert.match(ctx.output.out, /已取消/)
    assert.equal(stub.requests.some((item) => item.method === 'POST'), false)
  } finally {
    await stub.close()
  }
})
