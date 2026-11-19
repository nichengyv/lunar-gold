import * as vscode from 'vscode'
import type { PanelEvent } from '../shared/reducer.ts'

export type PanelPhase = 'idle' | 'connecting' | 'running' | 'waiting_approval' | 'failed' | 'stopped'

export interface PanelStatus {
  phase: PanelPhase
  message: string
  active: boolean
  workspaceMismatch: boolean
  canResume: boolean
}

export type PanelAction =
  | { type: 'resume' }
  | { type: 'approve'; approvalId: string; decision: 'APPROVE' | 'REJECT'; remember: 'NONE' | 'SESSION' }
  | { type: 'approveAll' }

const DEFAULT_STATUS: PanelStatus = {
  phase: 'idle',
  message: '尚未发起任务',
  active: false,
  workspaceMismatch: false,
  canResume: false
}

export class PanelProvider implements vscode.WebviewViewProvider {
  private readonly extensionUri: vscode.Uri
  private view: vscode.WebviewView | null = null
  private events: PanelEvent[] = []
  private status: PanelStatus = { ...DEFAULT_STATUS }
  private actionHandler: ((action: PanelAction) => Promise<void>) | null = null

  constructor(extensionUri: vscode.Uri) {
    this.extensionUri = extensionUri
  }

  setActionHandler(handler: (action: PanelAction) => Promise<void>): void {
    this.actionHandler = handler
  }

  resolveWebviewView(view: vscode.WebviewView): void {
    this.view = view
    view.webview.options = {
      enableScripts: true,
      localResourceRoots: [vscode.Uri.joinPath(this.extensionUri, 'media')]
    }
    view.webview.html = this.html(view.webview)
    view.onDidDispose(() => {
      if (this.view === view) this.view = null
    })
    view.webview.onDidReceiveMessage((message: { type?: string }) => {
      void this.handleMessage(message as PanelAction | { type: string })
    })
    void view.webview.postMessage({ type: 'reset' })
    void view.webview.postMessage({ type: 'events', events: this.events })
    void view.webview.postMessage({ type: 'status', status: this.status })
  }

  async show(): Promise<void> {
    if (this.view) {
      this.view.show(true)
      return
    }
    await vscode.commands.executeCommand('lunargold.panel.focus')
  }

  reset(): void {
    this.events = []
    void this.view?.webview.postMessage({ type: 'reset' })
  }

  publish(events: PanelEvent[]): void {
    if (events.length === 0) return
    this.events.push(...events)
    void this.view?.webview.postMessage({ type: 'events', events })
  }

  updateStatus(patch: Partial<PanelStatus>): void {
    this.status = { ...this.status, ...patch }
    void this.view?.webview.postMessage({ type: 'status', status: this.status })
  }

  private async handleMessage(message: PanelAction | { type: string }): Promise<void> {
    if (message.type === 'resume' || message.type === 'approve' || message.type === 'approveAll') {
      await this.actionHandler?.(message as PanelAction)
      return
    }
    const commands: Record<string, string> = {
      newTask: 'lunargold.newTask',
      stop: 'lunargold.stop',
      refreshChanges: 'lunargold.refreshChanges',
      revertRun: 'lunargold.revertRun',
      revertSession: 'lunargold.revertSession'
    }
    const command = commands[message.type]
    if (command) await vscode.commands.executeCommand(command)
  }

  private html(webview: vscode.Webview): string {
    const script = webview.asWebviewUri(vscode.Uri.joinPath(this.extensionUri, 'media', 'panel.js'))
    const style = webview.asWebviewUri(vscode.Uri.joinPath(this.extensionUri, 'media', 'panel.css'))
    const nonce = Array.from({ length: 16 }, () => Math.floor(Math.random() * 16).toString(16)).join('')
    return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource}; script-src 'nonce-${nonce}';">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <link rel="stylesheet" href="${style}">
  <title>Lunar Gold</title>
</head>
<body>
  <header class="toolbar">
    <button data-command="newTask" title="发起一个任务">发起任务</button>
    <button data-command="stop" id="stop-button" class="secondary" title="停止当前生成">停止</button>
    <button data-action="resume" id="resume-button" class="secondary" hidden>重新续传</button>
  </header>
  <section class="status-row">
    <span id="phase" class="phase">空闲</span>
    <span id="status-message"></span>
  </section>
  <div id="workspace-warning" class="notice" hidden>项目工作区与当前打开文件夹不一致，变更可能落在其他目录。</div>
  <div id="notice" class="notice" hidden></div>
  <section id="identity" class="identity" aria-label="任务标识">
    <span>会话 <strong id="session-id">—</strong></span>
    <span>Run <strong id="run-id">—</strong></span>
    <span>Turn <strong id="turn-id">—</strong></span>
  </section>
  <section id="approval-section" hidden>
    <div class="section-title"><span>待审批</span><button data-action="approveAll" id="approve-all" class="secondary" hidden>全部批准</button></div>
    <div id="approvals"></div>
  </section>
  <main id="transcript" aria-live="polite"><div id="empty" class="empty">从「发起任务」开始，流式过程会显示在这里。</div></main>
  <footer class="footer-actions">
    <button data-command="refreshChanges" class="link">刷新变更</button>
    <button data-command="revertRun" class="link">撤回本次 Run</button>
    <button data-command="revertSession" class="link">撤回会话</button>
    <span class="unverified">首版不执行验证</span>
  </footer>
  <script nonce="${nonce}" src="${script}"></script>
</body>
</html>`
  }
}
