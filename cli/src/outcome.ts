/**
 * 运行结果的统一收口：状态 → 退出码 → result 摘要。
 * run / watch 共用，避免两处各写一套判定。
 */
import { EXIT, type ExitCode } from './errors.ts'
import type { Renderer, RunResultSummary } from './render.ts'

/** done/终态 → 退出码：有文件变更记 6（首版不触发 Verify） */
export function exitForState(state: string, renderer: Renderer): ExitCode {
  if (state === 'SUCCEEDED') return renderer.filesChanged() ? EXIT.UNVERIFIED : EXIT.OK
  return EXIT.INCOMPLETE
}

export function summaryFor(
  renderer: Renderer,
  state: string,
  exitCode: ExitCode,
  errorMessage: string | null
): RunResultSummary {
  return {
    exitCode,
    status: state,
    sessionId: renderer.state.sessionId,
    runId: renderer.state.runId,
    turnId: renderer.state.turnId,
    filesChanged: renderer.filesChanged(),
    completion: renderer.state.completion?.message ?? null,
    error: errorMessage ?? renderer.state.error
  }
}
