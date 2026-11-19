import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import { CLI_ENDPOINTS, COMMAND_ENDPOINTS } from '../src/endpoints.ts'

interface Contract {
  contractVersion: number
  endpoints: Array<{ method: string; path: string; scope: string; group: string }>
}

function loadContract(): Contract {
  const path = join(import.meta.dirname, '..', '..', 'src', 'test', 'resources', 'contract', 'public-api.json')
  return JSON.parse(readFileSync(path, 'utf8')) as Contract
}

test('客户端令牌只调用公开子集里的端点（清单外即失败）', () => {
  const contract = loadContract()
  const allowed = new Set(contract.endpoints.map((endpoint) => `${endpoint.method} ${endpoint.path}`))
  for (const endpoint of CLI_ENDPOINTS.filter((item) => item.tokenKind === 'client')) {
    assert.ok(
      allowed.has(`${endpoint.method} ${endpoint.path}`),
      `${endpoint.command} 调用了清单外端点：${endpoint.method} ${endpoint.path}`
    )
  }
})

test('本地令牌例外只有 POST /api/clients，且它确实不在公开子集', () => {
  const contract = loadContract()
  const allowed = new Set(contract.endpoints.map((endpoint) => `${endpoint.method} ${endpoint.path}`))
  const local = CLI_ENDPOINTS.filter((endpoint) => endpoint.tokenKind === 'local')
  assert.deepEqual(local.map((endpoint) => `${endpoint.method} ${endpoint.path}`), ['POST /api/clients'])
  assert.ok(!allowed.has('POST /api/clients'))
})

test('端点清单无重复，且覆盖 8 条命令', () => {
  const keys = CLI_ENDPOINTS.map((endpoint) => `${endpoint.method} ${endpoint.path}`)
  assert.equal(new Set(keys).size, keys.length)
  assert.deepEqual(Object.keys(COMMAND_ENDPOINTS).sort(), [
    'approve',
    'changes',
    'client',
    'doctor',
    'revert',
    'run',
    'stop',
    'watch'
  ])
})

test('COMMAND_ENDPOINTS 与 CLI_ENDPOINTS 双向一致', () => {
  const fromList = new Map<string, Set<string>>()
  for (const endpoint of CLI_ENDPOINTS) {
    const key = `${endpoint.method} ${endpoint.path}`
    if (!fromList.has(endpoint.command)) fromList.set(endpoint.command, new Set())
    fromList.get(endpoint.command)?.add(key)
  }
  for (const [command, endpoints] of Object.entries(COMMAND_ENDPOINTS)) {
    const actual = fromList.get(command)
    assert.ok(actual, `命令 ${command} 没有任何端点`)
    assert.deepEqual([...(actual ?? [])].sort(), [...endpoints].sort())
  }
})

test('契约版本与 CLI 期望一致', () => {
  assert.equal(loadContract().contractVersion, 1)
})
