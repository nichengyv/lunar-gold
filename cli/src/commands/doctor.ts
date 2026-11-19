/**
 * lg doctor：探测后端兼容性（GET /api/health，不走 ApiResponse 信封）。
 * 断言 status == UP 且 apiContractVersion 与 CLI 常量一致，并检查令牌格式与配置目录可写。
 */
import { accessSync, constants, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { parseTokenClientId } from '../config.ts'
import type { CommandContext } from '../context.ts'
import { EXIT } from '../errors.ts'
import { requestRaw } from '../http.ts'
import { EXPECTED_API_CONTRACT_VERSION, type HealthInfo } from '../types.ts'

interface Check {
  name: string
  ok: boolean
  detail: string
}

export async function doctorCommand(ctx: CommandContext): Promise<number> {
  const checks: Check[] = []
  let health: HealthInfo | null = null
  try {
    health = await requestRaw<HealthInfo>(ctx, { path: '/api/health' })
    checks.push({ name: '后端连通', ok: true, detail: ctx.config.baseUrl })
  } catch (error) {
    checks.push({
      name: '后端连通',
      ok: false,
      detail: error instanceof Error ? error.message : String(error)
    })
  }

  if (health) {
    checks.push({
      name: 'health.status',
      ok: health.status === 'UP',
      detail: health.status
    })
    const versionOk = health.apiContractVersion === EXPECTED_API_CONTRACT_VERSION
    checks.push({
      name: 'apiContractVersion',
      ok: versionOk,
      detail: versionOk
        ? String(health.apiContractVersion)
        : `后端 ${health.apiContractVersion} / CLI ${EXPECTED_API_CONTRACT_VERSION}；CLI 与后端契约版本不一致，请升级 CLI 或后端`
    })
    checks.push({
      name: 'serverVersion',
      ok: typeof health.serverVersion === 'string' && health.serverVersion.length > 0,
      detail: health.serverVersion
    })
  }

  const token = ctx.config.token
  if (token) {
    const clientId = parseTokenClientId(token)
    checks.push({
      name: '令牌格式',
      ok: clientId !== null,
      detail: clientId !== null ? `client_id=${clientId}` : '不是 lgc_<client_id>_<secret> 格式'
    })
  }

  const configDir = dirname(ctx.config.configPath)
  let writable = true
  try {
    mkdirSync(configDir, { recursive: true })
    accessSync(configDir, constants.W_OK)
  } catch {
    writable = false
  }
  checks.push({ name: '配置目录可写', ok: writable, detail: configDir })

  const failed = checks.filter((check) => !check.ok)
  const exitCode = failed.length === 0 ? EXIT.OK : EXIT.ERROR

  if (ctx.config.json) {
    ctx.out.write(`${JSON.stringify({ type: 'result', exitCode, checks, health })}\n`)
    return exitCode
  }
  for (const check of checks) {
    ctx.out.write(`${check.ok ? '✓' : '✗'} ${check.name}：${check.detail}\n`)
  }
  if (failed.length > 0) {
    ctx.err.write(`doctor 未通过：${failed.map((check) => check.name).join(', ')}\n`)
  }
  return exitCode
}
