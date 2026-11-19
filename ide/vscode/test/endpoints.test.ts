import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import { EXTENSION_ENDPOINTS } from '../src/shared/endpoints.ts'

interface Contract {
  contractVersion: number
  endpoints: Array<{ method: string; path: string; scope: string; group: string }>
}

function loadContract(): Contract {
  const path = join(import.meta.dirname, '..', '..', '..', 'src', 'test', 'resources', 'contract', 'public-api.json')
  return JSON.parse(readFileSync(path, 'utf8')) as Contract
}

test('客户端令牌只调用公开子集端点', () => {
  const allowed = new Set(loadContract().endpoints.map((item) => `${item.method} ${item.path}`))
  for (const endpoint of EXTENSION_ENDPOINTS.filter((item) => item.tokenKind === 'client')) {
    assert.ok(allowed.has(`${endpoint.method} ${endpoint.path}`), `清单外调用：${endpoint.method} ${endpoint.path}`)
  }
})

test('唯一 local 例外是 POST /api/clients', () => {
  const contract = loadContract()
  const allowed = new Set(contract.endpoints.map((item) => `${item.method} ${item.path}`))
  const local = EXTENSION_ENDPOINTS.filter((item) => item.tokenKind === 'local')
  assert.deepEqual(local.map((item) => `${item.method} ${item.path}`), ['POST /api/clients'])
  assert.ok(!allowed.has('POST /api/clients'))
  assert.equal(contract.contractVersion, 1)
})

test('端点无重复且覆盖任务闭环', () => {
  const keys = EXTENSION_ENDPOINTS.map((item) => `${item.method} ${item.path}`)
  assert.equal(new Set(keys).size, keys.length)
  const features = new Set(EXTENSION_ENDPOINTS.map((item) => item.feature))
  for (const feature of ['connect', 'disconnect', 'newTask', 'stop', 'resume', 'changes', 'revertRun', 'revertSession', 'approval', 'approvalRestore']) {
    assert.ok(features.has(feature), `缺少功能映射：${feature}`)
  }
})
