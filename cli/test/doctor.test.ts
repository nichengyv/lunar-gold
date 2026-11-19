import assert from 'node:assert/strict'
import { join } from 'node:path'
import { test } from 'node:test'
import { execute } from '../src/cli.ts'
import { EXIT } from '../src/errors.ts'
import { capture, startStub, tempHome } from './helpers.ts'

function healthBody(apiContractVersion: number, status = 'UP'): Record<string, unknown> {
  return {
    status,
    version: '0.0.1',
    serverVersion: '0.0.1-test',
    apiContractVersion,
    uptimeMs: 1234,
    startedAt: '2026-11-16T10:00:00',
    timestamp: '2026-11-16T10:01:00'
  }
}

function envFor(home: string): NodeJS.ProcessEnv {
  return {
    LG_CONFIG_PATH: join(home, 'cli', 'config.json'),
    LG_LOCAL_TOKEN_PATH: join(home, 'desktop', 'local-token')
  }
}

test('doctor：契约版本一致退出 0', async () => {
  const home = tempHome()
  const stub = await startStub((request, response) => {
    assert.equal(request.path, '/api/health')
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify(healthBody(1)))
  })
  try {
    const { output, out, err } = capture()
    const code = await execute(
      ['doctor', '--base-url', stub.baseUrl, '--token', 'lgc_abcdefghijkl_secret'],
      { out, err, env: envFor(home) }
    )
    assert.equal(code, EXIT.OK)
    assert.match(output.out, /apiContractVersion/)
  } finally {
    await stub.close()
  }
})

test('doctor：契约版本不匹配退出 1 并提示升级', async () => {
  const home = tempHome()
  const stub = await startStub((request, response) => {
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify(healthBody(2)))
  })
  try {
    const { output, out, err } = capture()
    const code = await execute(
      ['doctor', '--base-url', stub.baseUrl, '--token', 'lgc_abcdefghijkl_secret'],
      { out, err, env: envFor(home) }
    )
    assert.equal(code, EXIT.ERROR)
    assert.match(output.out, /CLI 与后端契约版本不一致/)
  } finally {
    await stub.close()
  }
})

test('doctor：health 不通退出 1，不打印堆栈', async () => {
  const home = tempHome()
  const { output, out, err } = capture()
  const code = await execute(
    ['doctor', '--base-url', 'http://127.0.0.1:1', '--token', 'lgc_abcdefghijkl_secret'],
    { out, err, env: envFor(home) }
  )
  assert.equal(code, EXIT.ERROR)
  assert.match(output.out, /后端连通/)
  assert.equal(output.err.includes('    at '), false)
})
