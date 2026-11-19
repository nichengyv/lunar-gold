/**
 * 参数解析与子命令分发。
 *
 * 只用 8 条命令（见 spec FR-1），全局参数：--base-url / --token / --json / --quiet / --timeout。
 * 退出码与输出规范见 errors.ts 与 render.ts。
 */
import { parseArgs } from 'node:util'
import { resolveConfig, type ConfigOptions } from './config.ts'
import { createContext, type CommandContext, type Output } from './context.ts'
import { CliError, EXIT, usageError } from './errors.ts'
import { approveCommand } from './commands/approve.ts'
import { changesCommand } from './commands/changes.ts'
import { clientCreate, clientRevoke, clientShow } from './commands/client.ts'
import { doctorCommand } from './commands/doctor.ts'
import { revertCommand } from './commands/revert.ts'
import { runCommand } from './commands/run.ts'
import { stopCommand } from './commands/stop.ts'
import { watchCommand } from './commands/watch.ts'

const OPTIONS = {
  help: { type: 'boolean', short: 'h' },
  json: { type: 'boolean' },
  quiet: { type: 'boolean' },
  verbose: { type: 'boolean' },
  'base-url': { type: 'string' },
  token: { type: 'string' },
  timeout: { type: 'string' },
  session: { type: 'string' },
  run: { type: 'string' },
  project: { type: 'string' },
  deny: { type: 'boolean' },
  remember: { type: 'boolean' },
  yes: { type: 'boolean' },
  'auto-approve': { type: 'string' },
  name: { type: 'string' },
  scopes: { type: 'string' },
  'local-token': { type: 'string' },
  /** 续看轮询间隔（毫秒），主要给测试与调试用 */
  interval: { type: 'string' }
} as const

type Values = Record<string, string | boolean | undefined>

export function helpText(): string {
  return [
    'lg —— Lunar Gold 本地命令行（Node ≥ 24，无构建步骤）',
    '',
    '用法：lg <command> [options]',
    '',
    '命令：',
    '  run <prompt>                         发起任务并跟随流式输出',
    '  watch <runId>                        续看已有 Run（按 seq 增量）',
    '  changes --session <id> [--run <id>]  打印变更集',
    '  approve <approvalId> [--deny] [--remember]  提交审批',
    '  revert --session <id> [--run <id>] [--yes]  撤回变更',
    '  stop --session <id>                  停止当前生成',
    '  client create --name <n> [--scopes a,b]  签发客户端令牌',
    '  client show | revoke                 查看/撤销自身客户端',
    '  doctor                               探测后端契约版本与令牌',
    '',
    '全局参数：',
    '  --base-url <url>   默认 http://127.0.0.1:8080（或 LG_BASE_URL）',
    '  --token <token>    客户端令牌（或 LG_TOKEN / 配置文件）',
    '  --json             stdout 只输出 NDJSON，供脚本消费',
    '  --quiet            人读模式只保留结果与错误',
    '  --timeout <秒>     JSON 请求超时，默认 30',
    '',
    '退出码：0 成功 / 1 内部或网络错误 / 2 用法错误 / 3 认证授权失败',
    '        4 需要人工审批 / 5 任务未完成 / 6 有变更但未验证'
  ].join('\n')
}

export async function execute(argv: string[], overrides: Partial<CommandContext> = {}): Promise<number> {
  const jsonRequested = argv.includes('--json')
  const verbose = argv.includes('--verbose')
  const out: Output = overrides.out ?? process.stdout
  const err: Output = overrides.err ?? process.stderr

  try {
    const parsed = parseArgs({ args: argv, options: OPTIONS, allowPositionals: true, strict: false })
    const values = parsed.values as Values
    const positionals = parsed.positionals

    if (values.help === true) {
      out.write(`${helpText()}\n`)
      return EXIT.OK
    }
    const command = positionals[0]
    if (!command) {
      err.write(`${helpText()}\n`)
      return EXIT.USAGE
    }

    const configOptions: ConfigOptions = {
      baseUrl: stringValue(values, 'base-url'),
      token: stringValue(values, 'token'),
      timeoutSec: numberValue(values, 'timeout', '--timeout'),
      json: values.json === true,
      quiet: values.quiet === true
    }
    const ctx = createContext(resolveConfig(configOptions, overrides.env ?? process.env), {
      out,
      err,
      ...(values.interval !== undefined
        ? { pollMs: Math.max(1, numberValue(values, 'interval', '--interval') ?? 1000) }
        : {}),
      ...overrides
    })

    return await dispatch(command, positionals, values, ctx)
  } catch (error) {
    const cliError = error instanceof CliError
      ? error
      : new CliError(error instanceof Error ? error.message : String(error), EXIT.ERROR)
    if (jsonRequested) {
      out.write(`${JSON.stringify({ type: 'result', exitCode: cliError.exitCode, error: cliError.message })}\n`)
    }
    err.write(`错误：${cliError.message}\n`)
    if (cliError.hint) err.write(`提示：${cliError.hint}\n`)
    if (verbose && error instanceof Error && error.stack) err.write(`${error.stack}\n`)
    return cliError.exitCode
  }
}

async function dispatch(
  command: string,
  positionals: string[],
  values: Values,
  ctx: CommandContext
): Promise<number> {
  switch (command) {
    case 'run': {
      const prompt = positionals.slice(1).join(' ').trim()
      if (!prompt) throw usageError('run 需要一个 prompt', '用法：lg run "把 README 的安装步骤补全"')
      const autoApprove = stringValue(values, 'auto-approve')
      if (autoApprove !== undefined && autoApprove !== 'low') {
        throw usageError('--auto-approve 目前只支持 low')
      }
      return runCommand(ctx, {
        prompt,
        sessionId: numberValue(values, 'session', '--session'),
        projectId: numberValue(values, 'project', '--project'),
        autoApproveLow: autoApprove === 'low'
      })
    }
    case 'watch': {
      const runId = requiredNumber(positionals[1], 'watch 需要 runId', '用法：lg watch 123')
      return watchCommand(ctx, { runId })
    }
    case 'changes': {
      return changesCommand(ctx, {
        sessionId: requiredNumber(stringValue(values, 'session'), '--session 必填', '用法：lg changes --session 12 [--run 34]'),
        runId: numberValue(values, 'run', '--run')
      })
    }
    case 'approve': {
      const approvalId = positionals[1]
      if (!approvalId) throw usageError('approve 需要 approvalId', '用法：lg approve <id> [--deny] [--remember]')
      return approveCommand(ctx, {
        approvalId,
        deny: values.deny === true,
        remember: values.remember === true
      })
    }
    case 'revert': {
      return revertCommand(ctx, {
        sessionId: requiredNumber(stringValue(values, 'session'), '--session 必填', '用法：lg revert --session 12 [--run 34] [--yes]'),
        runId: numberValue(values, 'run', '--run'),
        yes: values.yes === true
      })
    }
    case 'stop': {
      return stopCommand(ctx, {
        sessionId: requiredNumber(stringValue(values, 'session'), '--session 必填', '用法：lg stop --session 12')
      })
    }
    case 'client': {
      const action = positionals[1]
      if (action === 'create') {
        return clientCreate(ctx, {
          name: stringValue(values, 'name'),
          scopes: splitScopes(stringValue(values, 'scopes')),
          localToken: stringValue(values, 'local-token')
        })
      }
      if (action === 'show') return clientShow(ctx)
      if (action === 'revoke') return clientRevoke(ctx)
      throw usageError('client 需要 create / show / revoke', '用法：lg client create --name cli --scopes chat:write,run:read')
    }
    case 'doctor':
      return doctorCommand(ctx)
    default:
      throw usageError(`未知命令：${command}`, '运行 lg --help 查看命令列表')
  }
}

function stringValue(values: Values, key: string): string | undefined {
  const value = values[key]
  return typeof value === 'string' ? value : undefined
}

function numberValue(values: Values, key: string, flag: string): number | undefined {
  const raw = stringValue(values, key)
  if (raw === undefined) return undefined
  const parsed = Number(raw)
  if (!Number.isFinite(parsed)) throw usageError(`${flag} 需要数字，收到：${raw}`)
  return parsed
}

function requiredNumber(raw: string | undefined, message: string, hint: string): number {
  if (raw === undefined) throw usageError(message, hint)
  const parsed = Number(raw)
  if (!Number.isFinite(parsed)) throw usageError(`${message}（收到：${raw}）`, hint)
  return parsed
}

function splitScopes(raw: string | undefined): string[] | undefined {
  if (!raw) return undefined
  return raw.split(',').map((item) => item.trim()).filter((item) => item.length > 0)
}
