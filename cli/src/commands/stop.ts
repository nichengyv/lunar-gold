/**
 * lg stop：POST /api/agent/chat/stop?sessionId=
 */
import type { CommandContext } from '../context.ts'
import { EXIT } from '../errors.ts'
import { request } from '../http.ts'

export interface StopCommandOptions {
  sessionId: number
}

export async function stopCommand(ctx: CommandContext, options: StopCommandOptions): Promise<number> {
  await request<null>(ctx, {
    method: 'POST',
    path: '/api/agent/chat/stop',
    query: { sessionId: options.sessionId }
  })
  if (ctx.config.json) {
    ctx.out.write(`${JSON.stringify({ type: 'result', exitCode: EXIT.OK, stoppedSessionId: options.sessionId })}\n`)
  } else if (!ctx.config.quiet) {
    ctx.out.write(`已请求停止：session=${options.sessionId}\n`)
  }
  return EXIT.OK
}
