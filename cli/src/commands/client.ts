/**
 * lg client create|show|revoke：客户端自管理。
 *
 * create 用本地用户令牌（桌面端生成）换 lgc_ 客户端令牌，并落盘到
 * ~/.lunargold/cli/config.json；之后所有命令只用客户端令牌。
 * show/revoke 只作用于自身（P6-01 只开放 /self）。
 */
import {
  clearStoredToken,
  parseTokenClientId,
  resolveLocalToken,
  writeConfigFile
} from '../config.ts'
import type { CommandContext } from '../context.ts'
import { CliError, EXIT, usageError } from '../errors.ts'
import { request } from '../http.ts'
import type { ApiClientView, CreateClientResult } from '../types.ts'

/** 默认给 CLI 全量 scope；想最小授权就显式传 --scopes */
export const DEFAULT_CLIENT_SCOPES = [
  'chat:write',
  'run:read',
  'changes:read',
  'changes:write',
  'approval:write',
  'project:read',
  'client:admin'
]

export interface ClientCreateOptions {
  name?: string
  scopes?: string[]
  localToken?: string
}

export async function clientCreate(ctx: CommandContext, options: ClientCreateOptions): Promise<number> {
  if (!options.name || options.name.trim().length === 0) {
    throw usageError('client create 需要 --name')
  }
  const localToken = resolveLocalToken(options.localToken, ctx.env)
  if (!localToken) {
    throw usageError(
      '未找到本地令牌：桌面端未启动或未生成令牌',
      '先运行 scripts/dev-desktop.ps1，或用 --local-token / LG_LOCAL_TOKEN 指定'
    )
  }
  const scopes = options.scopes && options.scopes.length > 0 ? options.scopes : [...DEFAULT_CLIENT_SCOPES]
  let result: CreateClientResult
  try {
    result = await request<CreateClientResult>(ctx, {
      method: 'POST',
      path: '/api/clients',
      body: { name: options.name, scopes },
      token: localToken
    })
  } catch (error) {
    if (error instanceof CliError && error.exitCode === EXIT.AUTH) {
      throw new CliError(error.message, EXIT.AUTH, '本地令牌无效：先启动桌面端（scripts/dev-desktop.ps1）生成新令牌')
    }
    throw error
  }

  writeConfigFile(ctx.config.configPath, { baseUrl: ctx.config.baseUrl, token: result.token })
  if (ctx.config.json) {
    ctx.out.write(
      `${JSON.stringify({
        type: 'result',
        exitCode: EXIT.OK,
        client: result.client,
        configPath: ctx.config.configPath
      })}\n`
    )
    return EXIT.OK
  }
  ctx.out.write(`客户端已创建：${result.client.clientId}（${result.client.name}）\n`)
  ctx.out.write(`scope：${result.client.scopes.join(', ')}\n`)
  ctx.out.write(`令牌已保存到 ${ctx.config.configPath}（不会再次回显，丢失请重新签发）\n`)
  return EXIT.OK
}

export async function clientShow(ctx: CommandContext): Promise<number> {
  const client = await request<ApiClientView>(ctx, { path: '/api/clients/self' })
  if (ctx.config.json) {
    ctx.out.write(`${JSON.stringify({ type: 'result', exitCode: EXIT.OK, client })}\n`)
    return EXIT.OK
  }
  ctx.out.write(`clientId：${client.clientId}\n`)
  ctx.out.write(`名称：${client.name}\n`)
  ctx.out.write(`scope：${client.scopes.join(', ') || '（无）'}\n`)
  ctx.out.write(`创建：${client.createdAt}\n`)
  ctx.out.write(`最近使用：${client.lastUsedAt ?? '从未'}\n`)
  ctx.out.write(`状态：${client.revoked ? '已撤销' : '有效'}\n`)
  return EXIT.OK
}

export async function clientRevoke(ctx: CommandContext): Promise<number> {
  const clientId = ctx.config.token ? parseTokenClientId(ctx.config.token) : null
  await request<undefined>(ctx, { method: 'DELETE', path: '/api/clients/self' })
  clearStoredToken(ctx.config.configPath)
  if (ctx.config.json) {
    ctx.out.write(`${JSON.stringify({ type: 'result', exitCode: EXIT.OK, revoked: true, clientId })}\n`)
    return EXIT.OK
  }
  ctx.out.write(`客户端已撤销${clientId ? `：${clientId}` : ''}；本地令牌已从配置中移除\n`)
  return EXIT.OK
}
