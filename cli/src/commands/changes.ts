/**
 * lg changes：GET /api/file-changes/changeset?sessionId=&runId=
 */
import type { CommandContext } from '../context.ts'
import { EXIT } from '../errors.ts'
import { request } from '../http.ts'
import type { ChangeSetView, ChangeView } from '../types.ts'

export interface ChangesCommandOptions {
  sessionId: number
  runId?: number
}

export async function fetchChangeSet(
  ctx: CommandContext,
  options: ChangesCommandOptions
): Promise<ChangeSetView> {
  return request<ChangeSetView>(ctx, {
    path: '/api/file-changes/changeset',
    query: { sessionId: options.sessionId, runId: options.runId }
  })
}

export async function changesCommand(ctx: CommandContext, options: ChangesCommandOptions): Promise<number> {
  const view = await fetchChangeSet(ctx, options)
  if (ctx.config.json) {
    ctx.out.write(`${JSON.stringify({ type: 'result', exitCode: EXIT.OK, data: view })}\n`)
    return EXIT.OK
  }

  const changes = view.changes ?? []
  if (!ctx.config.quiet) {
    if (changes.length === 0) {
      ctx.out.write(`没有文件变更（session=${view.sessionId}）\n`)
    } else {
      for (const change of changes) {
        ctx.out.write(`${formatChange(change)}\n`)
      }
    }
    const totals = view.totals
    ctx.out.write(
      `汇总：${totals.files} 个文件，+${totals.additions} -${totals.deletions}`
        + `（pending ${totals.pending} / accepted ${totals.accepted} / reverted ${totals.reverted}）`
        + `；状态 ${view.statusLabel ?? view.status}\n`
    )
    if (view.continuePrompt) ctx.out.write(`继续提示：${view.continuePrompt}\n`)
  }
  return EXIT.OK
}

function formatChange(change: ChangeView): string {
  const additions = change.additions ?? 0
  const deletions = change.deletions ?? 0
  return `${pad(change.status, 9)}${pad(change.action, 8)}${change.filePath}  +${additions} -${deletions}`
}

function pad(text: string, width: number): string {
  const value = text ?? ''
  return value.length >= width ? `${value} ` : value.padEnd(width, ' ')
}
