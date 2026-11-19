/**
 * lg revert：POST /api/file-changes/revert-run?sessionId=&runId=
 *              POST /api/file-changes/revert-session?sessionId=
 * 后端用查询参数而不是 JSON body，按 body 调用会参数缺失。
 */
import type { CommandContext } from '../context.ts'
import { usageError, EXIT } from '../errors.ts'
import { request } from '../http.ts'
import type { RevertResult } from '../types.ts'
import { fetchChangeSet } from './changes.ts'

export interface RevertCommandOptions {
  sessionId: number
  runId?: number
  yes: boolean
}

export async function revertCommand(ctx: CommandContext, options: RevertCommandOptions): Promise<number> {
  if (!options.yes) {
    // 非 TTY（脚本/CI）直接拒绝，避免误删；交互环境先展示影响面再确认
    if (!ctx.isTty) {
      throw usageError(
        '撤回需要显式确认：当前不是交互终端',
        `先看影响面再执行：lg changes --session ${options.sessionId}${options.runId !== undefined ? ` --run ${options.runId}` : ''}，确认后加 --yes`
      )
    }
    const view = await fetchChangeSet(ctx, {
      sessionId: options.sessionId,
      runId: options.runId
    })
    const target = options.runId !== undefined ? `run=${options.runId}` : `session=${options.sessionId}`
    ctx.out.write(`将撤回 ${target} 的 ${view.totals.pending} 个 PENDING 变更：\n`)
    for (const change of view.changes.filter((item) => item.status === 'PENDING')) {
      ctx.out.write(`  ${change.filePath}  +${change.additions ?? 0} -${change.deletions ?? 0}\n`)
    }
    const confirmed = await ctx.confirm('确认撤回？[y/N] ')
    if (!confirmed) {
      ctx.out.write('已取消\n')
      return EXIT.OK
    }
  }

  const result = options.runId !== undefined
    ? await request<RevertResult>(ctx, {
        method: 'POST',
        path: '/api/file-changes/revert-run',
        query: { sessionId: options.sessionId, runId: options.runId }
      })
    : await request<RevertResult>(ctx, {
        method: 'POST',
        path: '/api/file-changes/revert-session',
        query: { sessionId: options.sessionId }
      })

  if (ctx.config.json) {
    ctx.out.write(`${JSON.stringify({ type: 'result', exitCode: EXIT.OK, data: result })}\n`)
    return EXIT.OK
  }
  ctx.out.write(
    `撤回完成：成功 ${result.count} 个，失败 ${result.failed} 个，跳过已接受 ${result.skippedAccepted} 个\n`
  )
  for (const conflict of result.conflicts ?? []) {
    ctx.out.write(`  冲突：${conflict.filePath}（${conflict.reason}）\n`)
  }
  return EXIT.OK
}
