/**
 * lg approve：POST /api/agent/approvals/{approvalId}
 * body 固定 { decision: APPROVE|REJECT, remember: NONE|SESSION }。
 * 审批失效/不属于当前用户等业务错误一律退出 4，message 原样透出。
 */
import type { CommandContext } from '../context.ts'
import { EXIT } from '../errors.ts'
import { request } from '../http.ts'

export interface ApproveCommandOptions {
  approvalId: string
  deny?: boolean
  remember?: boolean
}

export async function approveCommand(ctx: CommandContext, options: ApproveCommandOptions): Promise<number> {
  const body = {
    decision: options.deny === true ? 'REJECT' : 'APPROVE',
    remember: options.remember === true ? 'SESSION' : 'NONE'
  }
  await request<null>(ctx, {
    method: 'POST',
    path: `/api/agent/approvals/${encodeURIComponent(options.approvalId)}`,
    body,
    businessExitCode: EXIT.APPROVAL
  })
  if (ctx.config.json) {
    ctx.out.write(
      `${JSON.stringify({
        type: 'result',
        exitCode: EXIT.OK,
        approvalId: options.approvalId,
        decision: body.decision,
        remember: body.remember
      })}\n`
    )
  } else if (!ctx.config.quiet) {
    ctx.out.write(`审批已提交：${options.approvalId} → ${body.decision}（remember=${body.remember}）\n`)
  }
  return EXIT.OK
}
