import assert from 'node:assert/strict'
import { test } from 'node:test'
import { EXIT } from '../src/errors.ts'
import { changesCommand } from '../src/commands/changes.ts'
import { errorResponse, jsonResponse, startStub, testConfig, testContext } from './helpers.ts'

const VIEW = {
  sessionId: 12,
  projectId: 3,
  runFilter: 34,
  status: 'PENDING',
  statusLabel: '待处理',
  accepted: false,
  totals: { files: 2, additions: 10, deletions: 2, pending: 1, accepted: 1, reverted: 0 },
  runs: [],
  changes: [
    {
      id: 1,
      runId: 34,
      turnId: 'turn-a',
      filePath: 'src/a.ts',
      action: 'MODIFY',
      source: 'write_file',
      additions: 7,
      deletions: 1,
      diffText: '',
      status: 'PENDING',
      createTime: '2026-11-16T10:00:00'
    },
    {
      id: 2,
      runId: 34,
      turnId: 'turn-a',
      filePath: 'src/b.ts',
      action: 'CREATE',
      source: 'write_file',
      additions: 3,
      deletions: 1,
      diffText: '',
      status: 'ACCEPTED',
      createTime: '2026-11-16T10:00:00'
    }
  ],
  continuePrompt: '还剩 1 个文件未处理'
}

test('changes：请求带 sessionId 与 runId，人读输出汇总', async () => {
  const stub = await startStub((request, response) => {
    assert.equal(request.path, '/api/file-changes/changeset')
    assert.equal(request.query.get('sessionId'), '12')
    assert.equal(request.query.get('runId'), '34')
    jsonResponse(response, VIEW)
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl }) })
    const code = await changesCommand(ctx, { sessionId: 12, runId: 34 })
    assert.equal(code, EXIT.OK)
    assert.match(ctx.output.out, /src\/a\.ts/)
    assert.match(ctx.output.out, /\+7 -1/)
    assert.match(ctx.output.out, /汇总：2 个文件，\+10 -2/)
    assert.match(ctx.output.out, /还剩 1 个文件未处理/)
  } finally {
    await stub.close()
  }
})

test('changes --json：ChangeSetView 原样透传到 data', async () => {
  const stub = await startStub((request, response) => {
    jsonResponse(response, VIEW)
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl, json: true }) })
    await changesCommand(ctx, { sessionId: 12 })
    const line = JSON.parse(ctx.output.out.trim()) as Record<string, unknown>
    assert.equal(line.type, 'result')
    assert.deepEqual(line.data, VIEW)
  } finally {
    await stub.close()
  }
})

test('changes：没有 --run 时不带 runId 参数', async () => {
  const stub = await startStub((request, response) => {
    assert.equal(request.query.get('runId'), null)
    jsonResponse(response, { ...VIEW, changes: [], totals: { files: 0, additions: 0, deletions: 0, pending: 0, accepted: 0, reverted: 0 } })
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl }) })
    await changesCommand(ctx, { sessionId: 12 })
    assert.match(ctx.output.out, /没有文件变更/)
  } finally {
    await stub.close()
  }
})

test('changes：403 透出后端 message 并退出 3（经 execute 包装）', async () => {
  const stub = await startStub((request, response) => {
    errorResponse(response, 403, '缺少 scope: changes:read')
  })
  try {
    const ctx = testContext({ config: testConfig({ baseUrl: stub.baseUrl }) })
    await assert.rejects(
      () => changesCommand(ctx, { sessionId: 12 }),
      (error: unknown) => {
        assert.equal((error as { exitCode?: number }).exitCode, EXIT.AUTH)
        return true
      }
    )
  } finally {
    await stub.close()
  }
})
