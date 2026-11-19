import { ApiError, request, type HttpContext } from './http.ts'
import type { PanelEvent, PanelReducer } from './reducer.ts'
import type { RunEventsData } from './types.ts'

export const TERMINAL_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])
export const RESUME_BACKOFF_MS = [1000, 2000, 4000] as const
const MAX_RETRIES = 3

export interface ResumeContext {
  http: HttpContext
  pollMs?: number
  sleep: (ms: number) => Promise<void>
}

export interface ResumeOptions {
  runId: number
  initialSeq?: number
  signal?: AbortSignal
  onEvents?: (events: PanelEvent[]) => void
  onRetry?: (lastSeq: number, attempt: number) => void
}

export interface ResumeOutcome {
  state: string
  errorMessage: string | null
  lastSeq: number
}

/** runId + sinceSeq 轮询续传；认证失败与业务错误不重试。 */
export async function resumeRun(
  ctx: ResumeContext,
  reducer: PanelReducer,
  options: ResumeOptions
): Promise<ResumeOutcome> {
  let lastSeq = options.initialSeq ?? reducer.state.lastSeq
  let failures = 0

  for (;;) {
    options.signal?.throwIfAborted()
    try {
      const data = await request<RunEventsData>(ctx.http, {
        path: `/api/agent/runs/${options.runId}/events`,
        query: { sinceSeq: lastSeq > 0 ? lastSeq : undefined },
        signal: options.signal
      })
      failures = 0
      for (const item of data.events) {
        if (typeof item.seq === 'number' && item.seq <= lastSeq) continue
        const events = reducer.handleReplay(item)
        options.onEvents?.(events)
      }
      lastSeq = Math.max(lastSeq, reducer.state.lastSeq)
      if (TERMINAL_STATES.has(data.state)) {
        return { state: data.state, errorMessage: data.errorMessage, lastSeq }
      }
      await ctx.sleep(ctx.pollMs ?? 1000)
    } catch (error) {
      if (options.signal?.aborted) throw error
      if (!(error instanceof ApiError) || !error.retryable) throw error
      failures += 1
      if (failures > MAX_RETRIES) throw error
      options.onRetry?.(lastSeq, failures)
      await ctx.sleep(RESUME_BACKOFF_MS[failures - 1])
    }
  }
}
