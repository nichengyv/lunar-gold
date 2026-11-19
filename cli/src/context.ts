/**
 * 命令执行上下文：把「进程副作用」收在这里，命令实现只依赖这个接口，
 * 测试就能用假 fetch / 假 TTY / 假确认函数跑完整流程，不必起子进程。
 */
import { createInterface } from 'node:readline/promises'
import type { CliConfig } from './config.ts'

export interface Output {
  write(text: string): void
}

export interface CommandContext {
  config: CliConfig
  out: Output
  err: Output
  env: NodeJS.ProcessEnv
  fetchImpl: typeof fetch
  isTty: boolean
  confirm: (question: string) => Promise<boolean>
  sleep: (ms: number) => Promise<void>
  /** 续看轮询间隔（毫秒）；测试调小以避免真实等待 */
  pollMs: number
}

export const DEFAULT_POLL_MS = 1000
export const RESUME_BACKOFF_MS = [1000, 2000, 4000]

async function stdinConfirm(question: string): Promise<boolean> {
  const rl = createInterface({ input: process.stdin, output: process.stdout })
  try {
    const answer = await rl.question(question)
    return /^y(es)?$/i.test(answer.trim())
  } finally {
    rl.close()
  }
}

export function createContext(
  config: CliConfig,
  overrides: Partial<CommandContext> = {}
): CommandContext {
  return {
    config,
    out: process.stdout,
    err: process.stderr,
    env: process.env,
    fetchImpl: globalThis.fetch.bind(globalThis),
    isTty: process.stdin.isTTY === true && process.stdout.isTTY === true,
    confirm: stdinConfirm,
    sleep: (ms: number) => new Promise((resolve) => setTimeout(resolve, ms)),
    pollMs: DEFAULT_POLL_MS,
    ...overrides
  }
}
