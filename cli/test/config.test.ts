import assert from 'node:assert/strict'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import {
  DEFAULT_BASE_URL,
  configPaths,
  parseTokenClientId,
  resolveConfig,
  resolveLocalToken,
  writeConfigFile
} from '../src/config.ts'
import { tempHome } from './helpers.ts'

test('默认值：无 env、无配置文件时用 127.0.0.1:8080', () => {
  const home = tempHome()
  const config = resolveConfig({}, {}, home)
  assert.equal(config.baseUrl, DEFAULT_BASE_URL)
  assert.equal(config.token, null)
  assert.equal(config.json, false)
  assert.equal(config.quiet, false)
  assert.ok(config.configPath.startsWith(home))
})

test('配置优先级：--base-url > LG_BASE_URL > 配置文件 > 默认', () => {
  const home = tempHome()
  const { configPath } = configPaths(home, {})
  writeConfigFile(configPath, { baseUrl: 'http://file-host:1111', token: 'lgc_file_secret' })

  assert.equal(resolveConfig({}, {}, home).baseUrl, 'http://file-host:1111')
  assert.equal(resolveConfig({}, { LG_BASE_URL: 'http://env-host:2222' }, home).baseUrl, 'http://env-host:2222')
  assert.equal(
    resolveConfig({ baseUrl: 'http://flag-host:3333' }, { LG_BASE_URL: 'http://env-host:2222' }, home).baseUrl,
    'http://flag-host:3333'
  )
})

test('令牌优先级：--token > LG_TOKEN > 配置文件', () => {
  const home = tempHome()
  const { configPath } = configPaths(home, {})
  writeConfigFile(configPath, { token: 'lgc_file_secret' })

  assert.equal(resolveConfig({}, {}, home).token, 'lgc_file_secret')
  assert.equal(resolveConfig({}, { LG_TOKEN: 'lgc_env_secret' }, home).token, 'lgc_env_secret')
  assert.equal(
    resolveConfig({ token: 'lgc_flag_secret' }, { LG_TOKEN: 'lgc_env_secret' }, home).token,
    'lgc_flag_secret'
  )
})

test('配置文件损坏时按未配置处理，不抛异常', () => {
  const home = tempHome()
  const { configPath } = configPaths(home, {})
  mkdirSync(join(home, '.lunargold', 'cli'), { recursive: true })
  writeFileSync(configPath, '{oops', 'utf8')
  const config = resolveConfig({}, {}, home)
  assert.equal(config.baseUrl, DEFAULT_BASE_URL)
  assert.equal(config.token, null)
})

test('本地令牌来源：--local-token > LG_LOCAL_TOKEN > 桌面端令牌文件', () => {
  const home = tempHome()
  const { localTokenPath } = configPaths(home, {})
  mkdirSync(join(home, '.lunargold', 'desktop'), { recursive: true })
  writeFileSync(localTokenPath, 'file-local\n', 'utf8')

  assert.equal(resolveLocalToken(undefined, {}, home), 'file-local')
  assert.equal(resolveLocalToken(undefined, { LG_LOCAL_TOKEN: 'env-local' }, home), 'env-local')
  assert.equal(resolveLocalToken('flag-local', { LG_LOCAL_TOKEN: 'env-local' }, home), 'flag-local')
  assert.equal(resolveLocalToken(undefined, {}, tempHome()), null)
})

test('令牌格式解析：lgc_<client_id>_<secret>', () => {
  assert.equal(parseTokenClientId('lgc_abcdefghijkl_0123456789'), 'abcdefghijkl')
  assert.equal(parseTokenClientId('lgc_bad'), null)
  assert.equal(parseTokenClientId('nope_abcdefghijkl_secret'), null)
  assert.equal(parseTokenClientId('lgc__secret'), null)
})

test('令牌格式解析：client_id 定长 12 字符，下划线开头/secret 含下划线都能正确取出', () => {
  // client_id 以 '_' 开头：旧实现按首个 '_' 切分会返回 null
  assert.equal(parseTokenClientId('lgc__abc12345678_secret'), '_abc12345678')
  // client_id 以 '_' 结尾
  assert.equal(parseTokenClientId('lgc_abcdefghijk__secret'), 'abcdefghijk_')
  // secret 自身含下划线
  assert.equal(parseTokenClientId('lgc_abcdefghijkl_se_cret'), 'abcdefghijkl')
  // 第 13 位不是分隔符
  assert.equal(parseTokenClientId('lgc_abcdefghijklXsecret'), null)
  // 缺 secret（刚好 17 字符）
  assert.equal(parseTokenClientId('lgc_abcdefghijkl_'), null)
})

test('--timeout 转毫秒', () => {
  const home = tempHome()
  assert.equal(resolveConfig({ timeoutSec: 5 }, {}, home).timeoutMs, 5000)
})
