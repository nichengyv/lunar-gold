/**
 * 测试辅助：捕获输出、临时配置目录、本地 stub HTTP 服务。
 * 不依赖任何第三方测试库，只用 node:test + node:assert。
 */
import { mkdtempSync } from 'node:fs'
import { createServer, type ServerResponse } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DEFAULT_TIMEOUT_MS, type CliConfig } from '../src/config.ts'
import { createContext, type CommandContext, type Output } from '../src/context.ts'

export interface Captured {
  out: string
  err: string
}

export interface TestContext extends CommandContext {
  output: Captured
  sleeps: number[]
}

export function capture(): { output: Captured; out: Output; err: Output } {
  const output: Captured = { out: '', err: '' }
  return {
    output,
    out: { write: (text: string) => { output.out += text } },
    err: { write: (text: string) => { output.err += text } }
  }
}

export function tempHome(): string {
  return mkdtempSync(join(tmpdir(), 'lg-cli-test-'))
}

export function testConfig(overrides: Partial<CliConfig> = {}): CliConfig {
  const home = tempHome()
  return {
    baseUrl: 'http://127.0.0.1:1',
    token: 'lgc_abcdefghijkl_secret',
    timeoutMs: DEFAULT_TIMEOUT_MS,
    json: false,
    quiet: false,
    configPath: join(home, 'cli', 'config.json'),
    localTokenPath: join(home, 'desktop', 'local-token'),
    ...overrides
  }
}

export function testContext(overrides: Partial<CommandContext> = {}): TestContext {
  const { output, out, err } = capture()
  const sleeps: number[] = []
  const ctx = createContext(testConfig(), {
    out,
    err,
    isTty: false,
    sleep: async (ms: number) => { sleeps.push(ms) },
    pollMs: 1,
    ...overrides
  })
  return Object.assign(ctx, { output, sleeps })
}

export interface StubRequest {
  method: string
  path: string
  query: URLSearchParams
  headers: Record<string, string | string[] | undefined>
  body: string
}

export interface StubServer {
  baseUrl: string
  requests: StubRequest[]
  close(): Promise<void>
}

export type StubHandler = (request: StubRequest, response: ServerResponse) => void | Promise<void>

export async function startStub(handler: StubHandler): Promise<StubServer> {
  const requests: StubRequest[] = []
  const server = createServer((req, res) => {
    const chunks: Buffer[] = []
    req.on('data', (chunk: Buffer) => chunks.push(chunk))
    req.on('end', () => {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1')
      const request: StubRequest = {
        method: req.method ?? 'GET',
        path: url.pathname,
        query: url.searchParams,
        headers: req.headers,
        body: Buffer.concat(chunks).toString('utf8')
      }
      requests.push(request)
      void Promise.resolve(handler(request, res)).catch((error: unknown) => {
        if (!res.headersSent) res.writeHead(500)
        res.end(String(error))
      })
    })
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  const port = typeof address === 'object' && address !== null ? address.port : 0
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    requests,
    close: () => new Promise<void>((resolve, reject) => {
      server.closeAllConnections()
      server.close((error) => (error ? reject(error) : resolve()))
    })
  }
}

export function jsonResponse(res: ServerResponse, data: unknown, status = 200): void {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ code: status === 200 ? 200 : status, message: status === 200 ? 'success' : 'error', data, timestamp: Date.now() }))
}

export function errorResponse(res: ServerResponse, status: number, message: string): void {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ code: status, message, data: null, timestamp: Date.now() }))
}

export function sseText(frames: Array<{ event: string; data: string }>): string {
  return frames.map((frame) => `event: ${frame.event}\ndata: ${frame.data}\n\n`).join('')
}

export function sseResponse(res: ServerResponse, body: string): void {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive'
  })
  res.end(body)
}
