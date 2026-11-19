/**
 * 断线续看（T6 的核心）：run 中断后与 watch 都走这里。
 *
 * 依据 GET /api/agent/runs/{runId}/events?sinceSeq=，按 seq 单调去重；
 * 连接失败最多重试 3 次，退避 1s/2s/4s，重试前打印 [resume from seq=N]。
 */
import type { CommandContext } from './context.ts'
import { RESUME_BACKOFF_MS } from './context.ts'
import { CliError, EXIT } from './errors.ts'
import { request } from './http.ts'
import type { Renderer } from './render.ts'
import type { RunEventsData } from './types.ts'

export const TERMINAL_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])
const MAX_RETRIES = 3

export interface ResumeOptions {
  runId: number
  initialSeq?: number
  /** run 断流后的首次续看需要提示；watch 首次拉取不需要 */
  printInitialResume?: boolean
}

export interface ResumeOutcome {
  state: string
  errorMessage: string | null
  lastSeq: number
}

export async function resumeRun(
  ctx: CommandContext,
  renderer: Renderer,
  options: ResumeOptions
): Promise<ResumeOutcome> {
  let lastSeq = options.initialSeq ?? renderer.state.lastSeq
  let failures = 0
  if (options.printInitialResume) renderer.printResume(lastSeq)

  for (;;) {
    try {
      const data = await request<RunEventsData>(ctx, {
        path: `/api/agent/runs/${options.runId}/events`,
        query: { sinceSeq: lastSeq > 0 ? lastSeq : undefined }
      })
      failures = 0
      for (const item of data.events) {
        if (typeof item.seq === 'number' && item.seq <= lastSeq) continue
        renderer.handleReplay(item)
      }
      lastSeq = Math.max(lastSeq, renderer.state.lastSeq)
      if (TERMINAL_STATES.has(data.state)) {
        return { state: data.state, errorMessage: data.errorMessage, lastSeq }
      }
      await ctx.sleep(ctx.pollMs)
    } catch (error) {
      if (!(error instanceof CliError)) throw error
      // 认证/授权失败重试也没用，直接上抛
      if (error.exitCode === EXIT.AUTH) throw error
      failures += 1
      if (failures > MAX_RETRIES) throw error
      renderer.printResume(lastSeq)
      await ctx.sleep(RESUME_BACKOFF_MS[Math.min(failures - 1, RESUME_BACKOFF_MS.length - 1)])
    }
  }
}
