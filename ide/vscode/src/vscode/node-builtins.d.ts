// 扩展宿主自带 Node；仅声明实际使用的内置模块，避免为类型额外引入依赖。
declare const process: { platform: string }
declare module 'node:fs/promises' {
  export function readFile(path: string, encoding: 'utf8'): Promise<string>
}

declare module 'node:os' {
  export function homedir(): string
  export function hostname(): string
}

declare module 'node:path' {
  export const sep: string
  export function join(...parts: string[]): string
  export function resolve(...parts: string[]): string
  export function relative(from: string, to: string): string
  export function isAbsolute(path: string): boolean
}
