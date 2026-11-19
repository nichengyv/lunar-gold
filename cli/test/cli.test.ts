import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { join } from 'node:path'
import { test } from 'node:test'

const MAIN = join(import.meta.dirname, '..', 'src', 'main.ts')

function run(args: string[]): { code: number | null; stdout: string; stderr: string } {
  const result = spawnSync(process.execPath, [MAIN, ...args], {
    cwd: join(import.meta.dirname, '..', '..'),
    encoding: 'utf8',
    env: { ...process.env, LG_CONFIG_PATH: join(import.meta.dirname, 'tmp-config.json') }
  })
  return { code: result.status, stdout: result.stdout, stderr: result.stderr }
}

test('--help 退出 0 并列出 8 条命令', () => {
  const { code, stdout } = run(['--help'])
  assert.equal(code, 0)
  for (const command of ['run', 'watch', 'changes', 'approve', 'revert', 'stop', 'client', 'doctor']) {
    assert.match(stdout, new RegExp(`\\b${command}\\b`))
  }
})

test('未知命令退出 2', () => {
  const { code, stderr } = run(['bogus'])
  assert.equal(code, 2)
  assert.match(stderr, /未知命令/)
})

test('无参数退出 2 并打印用法', () => {
  const { code, stderr } = run([])
  assert.equal(code, 2)
  assert.match(stderr, /用法/)
})

test('run 缺 prompt 退出 2', () => {
  const { code, stderr } = run(['run'])
  assert.equal(code, 2)
  assert.match(stderr, /prompt/)
})

test('changes 缺 --session 退出 2', () => {
  const { code, stderr } = run(['changes'])
  assert.equal(code, 2)
  assert.match(stderr, /--session/)
})
