/**
 * 退出码与错误类型。
 *
 * 退出码是 CLI 的稳定契约（见 spec FR-6），脚本与 CI 依赖它判断结果，
 * 因此所有命令只通过 CliError 携带退出码，不在命令内部随手 process.exit。
 */
export const EXIT = {
  OK: 0,
  ERROR: 1,
  USAGE: 2,
  AUTH: 3,
  APPROVAL: 4,
  INCOMPLETE: 5,
  UNVERIFIED: 6
} as const

export type ExitCode = (typeof EXIT)[keyof typeof EXIT]

export class CliError extends Error {
  readonly exitCode: ExitCode
  readonly hint: string | null

  constructor(message: string, exitCode: ExitCode = EXIT.ERROR, hint: string | null = null) {
    super(message)
    this.name = 'CliError'
    this.exitCode = exitCode
    this.hint = hint
  }
}

/** 用法错误：参数缺失/冲突、非 TTY 未加 --yes */
export function usageError(message: string, hint: string | null = null): CliError {
  return new CliError(message, EXIT.USAGE, hint)
}
