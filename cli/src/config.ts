/**
 * 配置解析（spec FR-5）。
 *
 * 优先级：
 *   baseUrl: --base-url > LG_BASE_URL > 配置文件 > http://127.0.0.1:8080
 *   token:   --token    > LG_TOKEN    > 配置文件
 *   localToken (仅 client create): --local-token > LG_LOCAL_TOKEN > ~/.lunargold/desktop/local-token
 */
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

export const DEFAULT_BASE_URL = 'http://127.0.0.1:8080'
export const DEFAULT_TIMEOUT_MS = 30_000

export interface CliConfig {
  baseUrl: string
  token: string | null
  timeoutMs: number
  json: boolean
  quiet: boolean
  configPath: string
  localTokenPath: string
}

export interface ConfigFileContent {
  baseUrl?: string
  token?: string
}

export interface ConfigOptions {
  baseUrl?: string
  token?: string
  timeoutSec?: number
  json?: boolean
  quiet?: boolean
}

export function configPaths(home: string, env: NodeJS.ProcessEnv = process.env): {
  configPath: string
  localTokenPath: string
} {
  return {
    configPath: env.LG_CONFIG_PATH || join(home, '.lunargold', 'cli', 'config.json'),
    localTokenPath: env.LG_LOCAL_TOKEN_PATH || join(home, '.lunargold', 'desktop', 'local-token')
  }
}

export function readConfigFile(path: string): ConfigFileContent {
  if (!existsSync(path)) return {}
  try {
    const parsed: unknown = JSON.parse(readFileSync(path, 'utf8'))
    if (parsed === null || typeof parsed !== 'object') return {}
    const value = parsed as Record<string, unknown>
    const result: ConfigFileContent = {}
    if (typeof value.baseUrl === 'string' && value.baseUrl.length > 0) result.baseUrl = value.baseUrl
    if (typeof value.token === 'string' && value.token.length > 0) result.token = value.token
    return result
  } catch {
    // 配置文件损坏时不阻塞命令：按「未配置」处理，用户可用 --token / LG_TOKEN 覆盖
    return {}
  }
}

export function resolveConfig(
  options: ConfigOptions = {},
  env: NodeJS.ProcessEnv = process.env,
  home: string = homedir()
): CliConfig {
  const paths = configPaths(home, env)
  const fileConfig = readConfigFile(paths.configPath)
  const timeoutMs = options.timeoutSec !== undefined
    ? Math.max(1, Math.trunc(options.timeoutSec)) * 1000
    : DEFAULT_TIMEOUT_MS
  return {
    baseUrl: trimTrailingSlash(options.baseUrl || env.LG_BASE_URL || fileConfig.baseUrl || DEFAULT_BASE_URL),
    token: options.token || env.LG_TOKEN || fileConfig.token || null,
    timeoutMs,
    json: options.json === true,
    quiet: options.quiet === true,
    configPath: paths.configPath,
    localTokenPath: paths.localTokenPath
  }
}

/** client create 的本地令牌来源顺序；找不到返回 null */
export function resolveLocalToken(
  override: string | undefined,
  env: NodeJS.ProcessEnv = process.env,
  home: string = homedir()
): string | null {
  if (override) return override.trim()
  if (env.LG_LOCAL_TOKEN) return env.LG_LOCAL_TOKEN.trim()
  const path = configPaths(home, env).localTokenPath
  if (existsSync(path)) {
    const token = readFileSync(path, 'utf8').trim()
    if (token.length > 0) return token
  }
  return null
}

export function writeConfigFile(path: string, content: ConfigFileContent): void {
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, `${JSON.stringify(content, null, 2)}\n`, 'utf8')
  restrictPermissions(path)
}

/** 撤销后清掉本地令牌，避免下一条命令继续拿废令牌撞 401 */
export function clearStoredToken(path: string): void {
  const content = readConfigFile(path)
  if (content.token === undefined) return
  delete content.token
  writeConfigFile(path, content)
}

/** POSIX 下 0600；Windows 无对应语义，跳过 */
export function restrictPermissions(path: string): void {
  if (process.platform === 'win32') return
  try {
    chmodSync(path, 0o600)
  } catch {
    // 权限收紧失败不阻塞主流程，由 doctor 提示
  }
}

/** client_id 固定长度：9 字节 Base64URL 无填充正好 12 字符（与后端 ApiClientService 一致） */
export const CLIENT_ID_CHARS = 12

/** lgc_<12 位 client_id>_<secret>；格式不合法返回 null */
export function parseTokenClientId(token: string): string | null {
  if (!token.startsWith('lgc_')) return null
  const start = 4
  const secretStart = start + CLIENT_ID_CHARS + 1
  // client_id 定长解析，因此 Base64URL 中的 '_' 不会造成误切分
  if (token.length <= secretStart || token.charAt(start + CLIENT_ID_CHARS) !== '_') return null
  return token.slice(start, start + CLIENT_ID_CHARS)
}

function trimTrailingSlash(url: string): string {
  return url.endsWith('/') ? url.slice(0, -1) : url
}
