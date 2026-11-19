import { readFile } from 'node:fs/promises'
import { homedir, hostname } from 'node:os'
import { join } from 'node:path'
import * as vscode from 'vscode'
import {
  normalizeLoopbackBaseUrl,
  normalizeStoredClientToken,
  probeBackend,
  validateClientToken
} from '../shared/connection.ts'
import { ApiError, request, type HttpContext, type RequestOptions } from '../shared/http.ts'
import { ALL_CLIENT_SCOPES, type CreateClientResult } from '../shared/types.ts'

const CLIENT_TOKEN_KEY = 'lunargold.clientToken'
export const ACTIVE_TASK_STATE_KEY = 'lunargold.activeTask'

export class Credentials {
  private readonly context: vscode.ExtensionContext

  constructor(context: vscode.ExtensionContext) {
    this.context = context
  }

  baseUrl(): string {
    const configured = vscode.workspace.getConfiguration('lunargold').get<string>('baseUrl', 'http://127.0.0.1:8080')
    return normalizeLoopbackBaseUrl(configured)
  }

  async httpContext(): Promise<HttpContext | null> {
    const token = normalizeStoredClientToken(await this.context.secrets.get(CLIENT_TOKEN_KEY))
    if (!token) return null
    return { baseUrl: this.baseUrl(), token, fetchImpl: fetch }
  }

  /** 所有业务命令的统一入口；未连接时先引导，不抛出未处理堆栈。 */
  async requireHttpContext(): Promise<HttpContext | null> {
    const existing = await this.httpContext()
    if (existing) return existing
    const action = await vscode.window.showInformationMessage('Lunar Gold 尚未连接本机后端。', '连接本机后端')
    if (!action) return null
    return await this.connect() ? this.httpContext() : null
  }

  async request<T>(options: RequestOptions): Promise<T | null> {
    const ctx = await this.requireHttpContext()
    if (!ctx) return null
    return request<T>(ctx, options)
  }

  async connect(): Promise<boolean> {
    try {
      const baseUrl = this.baseUrl()
      const anonymous: HttpContext = { baseUrl, fetchImpl: fetch }
      await probeBackend(anonymous)

      const storedValue = await this.context.secrets.get(CLIENT_TOKEN_KEY)
      const storedToken = normalizeStoredClientToken(storedValue)
      if (storedValue && !storedToken) await this.context.secrets.delete(CLIENT_TOKEN_KEY)
      if (storedToken) {
        try {
          const client = await validateClientToken(anonymous, storedToken)
          await this.showConnected(client.name)
          return true
        } catch (error) {
          if (!(error instanceof ApiError) || error.kind !== 'auth') throw error
          await this.context.secrets.delete(CLIENT_TOKEN_KEY)
        }
      }

      const localToken = await this.readLocalToken()
      if (!localToken) {
        return this.offerManualToken(
          storedToken
            ? '已保存的客户端令牌无效或 scope 不足，且未找到桌面端本地用户令牌。'
            : '未找到桌面端本地用户令牌。请先启动桌面端后端，或粘贴已有 lgc_ 客户端令牌。'
        )
      }

      let issued: CreateClientResult
      try {
        issued = await request<CreateClientResult>(anonymous, {
          method: 'POST',
          path: '/api/clients',
          token: localToken,
          body: { name: `vscode-${hostname()}`, scopes: [...ALL_CLIENT_SCOPES] }
        })
      } catch (error) {
        if (error instanceof ApiError && error.kind === 'auth') {
          return this.offerManualToken(`桌面端本地用户令牌无效：${error.message}`)
        }
        throw error
      }

      const token = normalizeStoredClientToken(issued.token)
      if (!token) throw new ApiError('后端签发的客户端令牌格式不正确。', 'contract')
      // 签发后先保存，避免后续 health 网络抖动导致唯一一次明文令牌丢失。
      await this.context.secrets.store(CLIENT_TOKEN_KEY, token)
      const client = await validateClientToken(anonymous, token)
      await this.showConnected(client.name)
      return true
    } catch (error) {
      await showBackendError(error)
      return false
    }
  }

  async disconnect(): Promise<boolean> {
    const token = normalizeStoredClientToken(await this.context.secrets.get(CLIENT_TOKEN_KEY))
    if (!token) {
      await this.clearLocalState()
      await vscode.window.showInformationMessage('Lunar Gold 本地连接状态已清除。')
      return true
    }
    const confirmation = await vscode.window.showWarningMessage(
      '将撤销此 VS Code 客户端令牌，并清除本窗口保存的任务状态。其他客户端不受影响。',
      { modal: true },
      '断开并撤销'
    )
    if (!confirmation) return false
    try {
      await request<undefined>({ baseUrl: this.baseUrl(), token, fetchImpl: fetch }, {
        method: 'DELETE',
        path: '/api/clients/self'
      })
    } catch (error) {
      // 401 表示后端已不接受该令牌，本地继续清理；403 时撤销未成功，保留令牌以便重试。
      if (!(error instanceof ApiError) || error.status !== 401) {
        await showBackendError(error)
        return false
      }
    }
    await this.clearLocalState()
    await vscode.window.showInformationMessage('Lunar Gold 客户端已撤销，本地凭据与任务状态已清除。')
    return true
  }

  private async offerManualToken(reason: string): Promise<boolean> {
    const action = await vscode.window.showWarningMessage(reason, '粘贴客户端令牌', '重新连接')
    if (action === '重新连接') return this.connect()
    if (action !== '粘贴客户端令牌') return false
    const input = await vscode.window.showInputBox({
      title: '连接 Lunar Gold 本机后端',
      prompt: '粘贴 lgc_ 客户端令牌；只会保存到 VS Code SecretStorage。',
      password: true,
      ignoreFocusOut: true
    })
    const token = normalizeStoredClientToken(input)
    if (!token) {
      await vscode.window.showWarningMessage('未提供有效的 lgc_ 客户端令牌，连接已取消。')
      return false
    }
    try {
      const anonymous: HttpContext = { baseUrl: this.baseUrl(), fetchImpl: fetch }
      const client = await validateClientToken(anonymous, token)
      await this.context.secrets.store(CLIENT_TOKEN_KEY, token)
      await this.showConnected(client.name)
      return true
    } catch (error) {
      if (error instanceof ApiError && error.kind === 'auth') {
        return this.offerManualToken(`客户端令牌校验失败：${error.message}`)
      }
      await showBackendError(error)
      return false
    }
  }

  private async readLocalToken(): Promise<string | null> {
    const override = vscode.workspace.getConfiguration('lunargold').get<string>('localTokenPath', '').trim()
    const path = override || join(homedir(), '.lunargold', 'desktop', 'local-token')
    try {
      return (await readFile(path, 'utf8')).trim() || null
    } catch (error) {
      if ((error as { code?: string }).code === 'ENOENT') return null
      const detail = error instanceof Error ? error.message : String(error)
      throw new Error(`无法读取桌面端本地用户令牌 ${path}：${detail}`)
    }
  }

  private async showConnected(name: string): Promise<void> {
    await vscode.window.showInformationMessage(`Lunar Gold 已连接：${name}。`)
  }

  private async clearLocalState(): Promise<void> {
    await this.context.secrets.delete(CLIENT_TOKEN_KEY)
    await this.context.workspaceState.update(ACTIVE_TASK_STATE_KEY, undefined)
  }
}

export async function showBackendError(error: unknown): Promise<void> {
  const detail = error instanceof Error ? error.message : String(error)
  const hint = error instanceof ApiError ? error.hint : null
  const message = `Lunar Gold：${detail}${hint ? `\n${hint}` : ''}`
  if (error instanceof ApiError && error.kind === 'auth') {
    const action = await vscode.window.showWarningMessage(message, '重新连接')
    if (action) await vscode.commands.executeCommand('lunargold.connect')
    return
  }
  await vscode.window.showErrorMessage(message)
}
