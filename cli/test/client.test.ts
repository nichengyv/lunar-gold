import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import { execute } from '../src/cli.ts'
import { EXIT } from '../src/errors.ts'
import { writeConfigFile } from '../src/config.ts'
import { capture, errorResponse, jsonResponse, startStub, tempHome } from './helpers.ts'

const CLIENT = {
  clientId: 'abcdefghijkl',
  name: 'cli',
  scopes: ['chat:write'],
  createdAt: '2026-11-16T10:00:00',
  lastUsedAt: null,
  revoked: false
}

function envFor(home: string, extra: Record<string, string> = {}): NodeJS.ProcessEnv {
  return {
    LG_CONFIG_PATH: join(home, 'cli', 'config.json'),
    LG_LOCAL_TOKEN_PATH: join(home, 'desktop', 'local-token'),
    ...extra
  }
}

test('client create：缺本地令牌时退出 2 并提示 dev-desktop.ps1', async () => {
  const home = tempHome()
  const { output, out, err } = capture()
  const code = await execute(['client', 'create', '--name', 'cli'], { out, err, env: envFor(home) })
  assert.equal(code, EXIT.USAGE)
  assert.match(output.err, /scripts\/dev-desktop\.ps1/)
  assert.equal(output.err.includes('at '), false)
})

test('client create：用本地令牌换客户端令牌并落盘，不回显明文', async () => {
  const home = tempHome()
  const stub = await startStub((request, response) => {
    assert.equal(request.method, 'POST')
    assert.equal(request.path, '/api/clients')
    assert.equal(request.headers.authorization, 'Bearer local-token-1')
    assert.deepEqual(JSON.parse(request.body), { name: 'cli', scopes: ['chat:write', 'run:read'] })
    jsonResponse(response, { token: 'lgc_abcdefghijkl_supersecret', client: CLIENT })
  })
  try {
    const { output, out, err } = capture()
    const code = await execute(
      [
        'client', 'create',
        '--name', 'cli',
        '--scopes', 'chat:write,run:read',
        '--base-url', stub.baseUrl
      ],
      { out, err, env: envFor(home, { LG_LOCAL_TOKEN: 'local-token-1' }) }
    )
    assert.equal(code, EXIT.OK)
    assert.match(output.out, /abcdefghijkl/)
    assert.equal(output.out.includes('supersecret'), false, '绝不能回显明文令牌')

    const saved = JSON.parse(readFileSync(join(home, 'cli', 'config.json'), 'utf8')) as {
      token: string
      baseUrl: string
    }
    assert.equal(saved.token, 'lgc_abcdefghijkl_supersecret')
    assert.equal(saved.baseUrl, stub.baseUrl)
  } finally {
    await stub.close()
  }
})

test('client show：GET /self，不回显令牌', async () => {
  const home = tempHome()
  const stub = await startStub((request, response) => {
    assert.equal(request.path, '/api/clients/self')
    assert.equal(request.headers.authorization, 'Bearer lgc_abcdefghijkl_secret')
    jsonResponse(response, CLIENT)
  })
  try {
    const { output, out, err } = capture()
    writeConfigFile(join(home, 'cli', 'config.json'), {
      baseUrl: stub.baseUrl,
      token: 'lgc_abcdefghijkl_secret'
    })
    const code = await execute(['client', 'show'], { out, err, env: envFor(home) })
    assert.equal(code, EXIT.OK)
    assert.match(output.out, /abcdefghijkl/)
    assert.equal(output.out.includes('secret'), false)
  } finally {
    await stub.close()
  }
})

test('client revoke：DELETE /self 并清掉本地令牌', async () => {
  const home = tempHome()
  const stub = await startStub((request, response) => {
    assert.equal(request.method, 'DELETE')
    assert.equal(request.path, '/api/clients/self')
    response.writeHead(204)
    response.end()
  })
  try {
    const { output, out, err } = capture()
    const configPath = join(home, 'cli', 'config.json')
    writeConfigFile(configPath, { baseUrl: stub.baseUrl, token: 'lgc_abcdefghijkl_secret' })
    const code = await execute(['client', 'revoke'], { out, err, env: envFor(home) })
    assert.equal(code, EXIT.OK)
    assert.match(output.out, /已撤销/)
    const saved = JSON.parse(readFileSync(configPath, 'utf8')) as { token?: string }
    assert.equal(saved.token, undefined)
  } finally {
    await stub.close()
  }
})

test('撤销后同令牌请求退出 3（401 映射）', async () => {
  const home = tempHome()
  const stub = await startStub((request, response) => {
    errorResponse(response, 401, '客户端令牌无效或已撤销')
  })
  try {
    const { output, out, err } = capture()
    const code = await execute(
      ['client', 'show', '--base-url', stub.baseUrl, '--token', 'lgc_abcdefghijkl_secret'],
      { out, err, env: envFor(home) }
    )
    assert.equal(code, EXIT.AUTH)
    assert.match(output.err, /客户端令牌无效或已撤销/)
    assert.equal(output.err.includes('    at '), false, '不应打印堆栈')
  } finally {
    await stub.close()
  }
})
