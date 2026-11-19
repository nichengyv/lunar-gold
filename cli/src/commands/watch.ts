/**
 * lg watch：在 run 进程退出后继续看同一个 Run。
 * 复用 resumeRun + Renderer，按 seq 单调去重，不重复输出已消费事件。
 */
import type { CommandContext } from '../context.ts'
import { exitForState, summaryFor } from '../outcome.ts'
import { Renderer } from '../render.ts'
import { resumeRun } from '../resume.ts'

export interface WatchCommandOptions {
  runId: number
  /** 可选：从某个 seq 之后开始看 */
  sinceSeq?: number
}

export async function watchCommand(ctx: CommandContext, options: WatchCommandOptions): Promise<number> {
  const renderer = new Renderer({
    json: ctx.config.json,
    quiet: ctx.config.quiet,
    out: ctx.out,
    err: ctx.err
  })
  const outcome = await resumeRun(ctx, renderer, {
    runId: options.runId,
    initialSeq: options.sinceSeq,
    printInitialResume: false
  })
  renderer.finishOutput()
  const exitCode = exitForState(outcome.state, renderer)
  renderer.printResult(summaryFor(renderer, outcome.state, exitCode, outcome.errorMessage))
  return exitCode
}
