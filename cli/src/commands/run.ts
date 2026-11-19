/**
 * lg run：POST /api/agent/chat/stream，跟随 SSE 直到 done / error / 需要审批。
 *
 * 断流（非 done 结束）时用 runId 调事件接口续看：
 * 流内没有 seq，只能全量回放 + 按内容长度/事件指纹去重（见 README「已知限制」）。
 *
 * P6-06：后端把「客户端断流」与「用户停止」拆开后，Ctrl-C 不能只杀进程，
 * 必须先显式调用 /chat/stop，否则任务会在后台继续执行。
 */
import type { CommandContext } from '../context.ts'
import { CliError, EXIT } from '../errors.ts'
import { httpError, request, send } from '../http.ts'
import { exitForState, summaryFor } from '../outcome.ts'
import { Renderer } from '../render.ts'
import { resumeRun } from '../resume.ts'
import { parseSSE } from '../sse.ts'
import { approveCommand } from './approve.ts'

export interface RunCommandOptions {
  prompt: string
  sessionId?: number
  projectId?: number
  autoApproveLow?: boolean
}

/** 低风险仅指 WRITE；EXECUTE / DESTRUCTIVE 必须人工确认 */
export function isLowRisk(risk: string): boolean {
  return risk === 'WRITE'
}

/**
 * P6-06：Ctrl-C 视为用户主动停止。
 * 已知 sessionId 时先请求 /chat/stop，再以「未完成」退出；请求失败也按中断退出，
 * 不把停止失败的错误盖在用户的中断意图上。
 */
async function stopOnInterrupt(
  ctx: CommandContext,
  currentSessionId: () => number | null,
  run: () => Promise<number>
): Promise<number> {
  let interrupted = false
  const onInterrupt = (): void => {
    if (interrupted) return
    interrupted = true
    const sessionId = currentSessionId()
    const stopping = sessionId === null
      ? Promise.resolve()
      : request<null>(ctx, {
          method: 'POST',
          path: '/api/agent/chat/stop',
          query: { sessionId }
        }).then(() => undefined).catch(() => undefined)
    void stopping.finally(() => process.exit(EXIT.INCOMPLETE))
  }
  process.once('SIGINT', onInterrupt)
  try {
    return await run()
  } finally {
    process.off('SIGINT', onInterrupt)
  }
}

export async function runCommand(ctx: CommandContext, options: RunCommandOptions): Promise<number> {
  const renderer = new Renderer({
    json: ctx.config.json,
    quiet: ctx.config.quiet,
    out: ctx.out,
    err: ctx.err
  })
  const body: Record<string, unknown> = { prompt: options.prompt }
  if (options.sessionId !== undefined) body.sessionId = options.sessionId
  if (options.projectId !== undefined) body.projectId = options.projectId

  const response = await send(ctx, {
    method: 'POST',
    path: '/api/agent/chat/stream',
    body,
    timeoutMs: null
  })
  if (!response.ok) throw await httpError(response)
  if (!response.body) throw new CliError('后端没有返回流式响应体', EXIT.ERROR)

  return stopOnInterrupt(
    ctx,
    () => renderer.state.sessionId ?? options.sessionId ?? null,
    async () => {
      let stoppedForApproval = false
      await parseSSE(response.body as ReadableStream<Uint8Array>, async (event) => {
        const terminal = renderer.handleSse(event.type, event.data)
        if (event.type !== 'approval_required') return terminal

        const approval = renderer.state.pendingApproval
        if (options.autoApproveLow === true && approval && isLowRisk(approval.risk)) {
          await approveCommand(ctx, { approvalId: approval.approvalId, deny: false, remember: false })
          renderer.state.pendingApproval = null
          if (!ctx.config.json && !ctx.config.quiet) {
            ctx.out.write(`[auto-approve] ${approval.approvalId}\n`)
          }
          return terminal
        }
        // 非交互场景：停在审批上，退出码 4，让脚本决定是否 lg approve
        stoppedForApproval = true
        return true
      })

      const finish = (summary: Parameters<Renderer['printResult']>[0]): void => {
        renderer.finishOutput()
        renderer.printResult(summary)
      }

      if (stoppedForApproval) {
        renderer.printPendingApprovalHint()
        finish(summaryFor(renderer, 'WAITING_APPROVAL', EXIT.APPROVAL, null))
        return EXIT.APPROVAL
      }

      if (renderer.state.error) {
        finish(summaryFor(renderer, 'FAILED', EXIT.INCOMPLETE, renderer.state.error))
        return EXIT.INCOMPLETE
      }

      if (renderer.state.done) {
        const exitCode = exitForState('SUCCEEDED', renderer)
        finish(summaryFor(renderer, 'SUCCEEDED', exitCode, null))
        return exitCode
      }

      if (renderer.state.runId === null) {
        finish(summaryFor(renderer, 'UNKNOWN', EXIT.INCOMPLETE, '流在收到 run_id 之前中断，无法续看'))
        return EXIT.INCOMPLETE
      }

      const outcome = await resumeRun(ctx, renderer, {
        runId: renderer.state.runId,
        printInitialResume: true
      })
      const exitCode = exitForState(outcome.state, renderer)
      finish(summaryFor(renderer, outcome.state, exitCode, outcome.errorMessage))
      return exitCode
    }
  )
}
