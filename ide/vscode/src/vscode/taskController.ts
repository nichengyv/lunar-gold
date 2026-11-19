import * as vscode from 'vscode'
import { request, send, toApiError, type HttpContext } from '../shared/http.ts'
import { PanelReducer, type PanelEvent } from '../shared/reducer.ts'
import { resumeRun } from '../shared/resume.ts'
import { parseSSE } from '../shared/sse.ts'
import type { ApprovalHistoryItem, ApprovalRequired, ProjectSummary, SessionSummary } from '../shared/types.ts'
import { ACTIVE_TASK_STATE_KEY, Credentials, showBackendError } from './credentials.ts'
import type { PanelAction, PanelProvider } from './panel.ts'

export interface TaskSnapshot {
  projectId: number
  workspacePath: string | null
  sessionId: number | null
  runId: number | null
  lastSeq: number
  active: boolean
  workspaceMismatch: boolean
}

interface ProjectPick extends vscode.QuickPickItem { project: ProjectSummary }
interface SessionPick extends vscode.QuickPickItem { sessionId: number | null }

export class TaskController implements vscode.Disposable {
  private readonly context: vscode.ExtensionContext
  private readonly credentials: Credentials
  private readonly panel: PanelProvider
  private readonly statusBar: vscode.StatusBarItem
  private readonly changesEmitter = new vscode.EventEmitter<void>()
  readonly onChangesRefresh = this.changesEmitter.event

  private reducer = new PanelReducer()
  private readonly pendingApprovals = new Map<string, ApprovalRequired>()
  private task: TaskSnapshot | null = null
  private active = false
  private pumping = false
  private terminated = false
  private abortController: AbortController | null = null

  constructor(context: vscode.ExtensionContext, credentials: Credentials, panel: PanelProvider) {
    this.context = context
    this.credentials = credentials
    this.panel = panel
    this.statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100)
    this.statusBar.name = 'Lunar Gold'
    this.statusBar.command = 'lunargold.openPanel'
    this.statusBar.show()
    panel.setActionHandler((action) => this.handlePanelAction(action))
    this.updateStatus('idle', '尚未发起任务')
  }

  get currentTask(): TaskSnapshot | null {
    return this.task ? { ...this.task } : null
  }

  async newTask(): Promise<void> {
    if (this.active) {
      await this.panel.show()
      await vscode.window.showInformationMessage('已有未结束的 Lunar Gold 任务；请先停止或等待完成。')
      return
    }
    if (vscode.env.remoteName) {
      await vscode.window.showWarningMessage('Lunar Gold 首版不支持 SSH / WSL / Dev Container 远程窗口，请在本地窗口使用。')
      return
    }

    this.active = true // 从选择项目开始占用单任务锁，防止重复发起。
    let started = false
    try {
      const ctx = await this.credentials.requireHttpContext()
      if (!ctx) return
      const projects = await request<ProjectSummary[]>(ctx, { path: '/api/projects' })
      if (projects.length === 0) {
        await vscode.window.showWarningMessage('后端暂无项目，请先在 Web 工作台创建项目。')
        return
      }
      const project = await this.pickProject(projects)
      if (!project) return
      const sessionId = await this.pickSession(ctx, project.id)
      if (sessionId === undefined) return
      const workspace = await this.checkWorkspace(project)
      if (workspace === null) return
      const prompt = await vscode.window.showInputBox({
        title: 'Lunar Gold 发起任务',
        prompt: `项目：${project.name}${sessionId === null ? ' · 新会话' : ` · 会话 ${sessionId}`}`,
        placeHolder: '描述要让智能体完成的任务',
        ignoreFocusOut: true
      })
      if (!prompt?.trim()) return

      this.reducer = new PanelReducer()
      this.pendingApprovals.clear()
      this.terminated = false
      this.reducer.state.sessionId = sessionId
      this.task = {
        projectId: project.id,
        workspacePath: project.workspacePath,
        sessionId,
        runId: null,
        lastSeq: 0,
        active: true,
        workspaceMismatch: workspace.mismatch
      }
      this.panel.reset()
      this.panel.updateStatus({ workspaceMismatch: workspace.mismatch, canResume: false })
      this.updateStatus('connecting', '正在发起任务…', true)
      await this.persist()
      await this.panel.show()
      started = true
      await this.stream(ctx, prompt.trim())
    } catch (error) {
      if (started) {
        if (this.task) await this.handlePumpError(error)
      } else {
        await showBackendError(error)
      }
    } finally {
      if (!started) {
        this.active = false
        this.updateStatus('idle', '尚未发起任务')
      }
    }
  }

  async restore(): Promise<void> {
    if (this.pumping) return
    const saved = this.context.workspaceState.get<TaskSnapshot>(ACTIVE_TASK_STATE_KEY)
    if (!saved) return
    this.task = { ...saved, workspacePath: saved.workspacePath ?? null }
    this.reducer = new PanelReducer()
    this.pendingApprovals.clear()
    this.reducer.state.sessionId = saved.sessionId
    this.reducer.state.runId = saved.runId
    this.reducer.state.lastSeq = saved.lastSeq
    this.active = saved.active
    this.terminated = !saved.active
    // 回放只有 Run 内事件，先恢复外层身份，避免重载后会话与 Run 显示为“—”。
    this.panel.publish([
      { type: 'session', sessionId: saved.sessionId },
      { type: 'run', runId: saved.runId }
    ])
    this.panel.updateStatus({ workspaceMismatch: saved.workspaceMismatch })
    this.changesEmitter.fire()
    if (!saved.active) {
      this.updateStatus('idle', saved.runId ? `上次 Run #${saved.runId} 已结束` : '尚未发起任务')
      return
    }
    if (!saved.runId) {
      this.updateStatus('failed', '保存的任务缺少 runId，无法续传；可停止后重新发起。', true, true)
      return
    }
    const ctx = await this.credentials.httpContext()
    if (!ctx) {
      this.updateStatus('connecting', `连接后端后可恢复 Run #${saved.runId}`, true, true)
      return
    }
    await this.resumeSaved()
  }

  async resumeSaved(): Promise<void> {
    if (!this.task?.active || !this.task.runId || this.pumping) return
    const ctx = await this.credentials.requireHttpContext()
    if (!ctx) return
    await this.restoreApprovals(ctx)
    const controller = new AbortController()
    this.abortController = controller
    this.pumping = true
    this.active = true
    const pending = this.pendingApprovals.values().next().value as ApprovalRequired | undefined
    if (pending) this.updateStatus('waiting_approval', `等待审批：${pending.tool}`, true)
    else this.updateStatus('connecting', `正在从 seq=${this.task.lastSeq} 恢复 Run #${this.task.runId}…`, true)
    try {
      await this.resume(ctx, controller.signal)
    } catch (error) {
      if (this.task) await this.handlePumpError(error)
    } finally {
      if (this.abortController === controller) this.abortController = null
      this.pumping = false
    }
  }

  private async stream(ctx: HttpContext, prompt: string): Promise<void> {
    if (!this.task) return
    const controller = new AbortController()
    this.abortController = controller
    this.pumping = true
    try {
      const response = await send(ctx, {
        method: 'POST',
        path: '/api/agent/chat/stream',
        body: {
          prompt,
          projectId: this.task.projectId,
          ...(this.task.sessionId === null ? {} : { sessionId: this.task.sessionId })
        },
        timeoutMs: null,
        signal: controller.signal
      })
      if (!response.ok) throw await toApiError(response)
      if (!response.body) throw new Error('后端未返回 SSE 响应体。')
      await parseSSE(response.body, (event) => {
        const events = this.reducer.handleSse(event.type, event.data)
        this.publish(events)
        return this.reducer.state.done || this.reducer.state.error !== null
      })
      if (this.reducer.state.done || this.reducer.state.error !== null) return
      if (!this.reducer.state.runId) throw new Error('SSE 在返回 runId 前中断，无法续传。')
      this.panel.updateStatus({ message: '连接中断，正在续传…' })
      await this.resume(ctx, controller.signal)
    } finally {
      if (this.abortController === controller) this.abortController = null
      this.pumping = false
    }
  }

  private async resume(ctx: HttpContext, signal?: AbortSignal): Promise<void> {
    const runId = this.task?.runId ?? this.reducer.state.runId
    if (!runId) throw new Error('缺少 runId，无法续传。')
    const outcome = await resumeRun(
      { http: ctx, pollMs: 1000, sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)) },
      this.reducer,
      {
        runId,
        initialSeq: this.task?.lastSeq ?? this.reducer.state.lastSeq,
        signal,
        onEvents: (events) => this.publish(events),
        onRetry: (lastSeq, attempt) => {
          this.updateStatus('connecting', `已断线，正在第 ${attempt}/3 次重连（seq=${lastSeq}）`, true)
        }
      }
    )
    if (outcome.state === 'SUCCEEDED' && !this.reducer.state.done) {
      this.publish([{ type: 'done', completion: this.reducer.state.completion }])
    } else if (outcome.state === 'FAILED') {
      const message = outcome.errorMessage ?? this.reducer.state.error ?? '任务失败'
      if (!this.reducer.state.error) this.publish([{ type: 'error', message }])
      this.finish('FAILED')
    } else if (outcome.state === 'CANCELLED') {
      this.finish('CANCELLED')
    }
  }

  private publish(events: PanelEvent[]): void {
    if (!this.task || this.terminated) return
    this.panel.publish(events)
    this.task.sessionId = this.reducer.state.sessionId ?? this.task.sessionId
    this.task.runId = this.reducer.state.runId ?? this.task.runId
    this.task.lastSeq = this.reducer.state.lastSeq
    for (const event of events) {
      if (event.type === 'approval') {
        this.pendingApprovals.set(event.approval.approvalId, event.approval)
      } else if (event.type === 'approval_resolved') {
        if (event.approvalId) this.pendingApprovals.delete(event.approvalId)
        else this.pendingApprovals.clear()
      }
    }
    this.reducer.state.pendingApproval = this.pendingApprovals.values().next().value ?? null

    for (const event of events) {
      if (event.type === 'file_change') this.changesEmitter.fire()
      else if (event.type === 'done') this.finish('SUCCEEDED')
      else if (event.type === 'error') this.finish('FAILED')
    }
    if (this.active && !this.reducer.state.done && !this.reducer.state.error) {
      const pending = this.pendingApprovals.values().next().value as ApprovalRequired | undefined
      if (pending) this.updateStatus('waiting_approval', `等待审批：${pending.tool}`, true)
      else if (this.reducer.state.runId) this.updateStatus('running', this.runningMessage(), true)
    }
    void this.persist()
  }

  private finish(state: 'SUCCEEDED' | 'FAILED' | 'CANCELLED'): void {
    this.active = false
    this.terminated = true
    if (this.task) this.task.active = false
    if (this.pendingApprovals.size > 0) {
      this.pendingApprovals.clear()
      this.reducer.state.pendingApproval = null
      this.panel.publish([{ type: 'approval_resolved', approvalId: null, decision: state }])
    }
    if (state === 'SUCCEEDED') this.updateStatus('idle', `Run #${this.task?.runId ?? '—'} 已完成 · 未验证`)
    else if (state === 'CANCELLED') this.updateStatus('stopped', `Run #${this.task?.runId ?? '—'} 已停止`)
    else this.updateStatus('failed', this.reducer.state.error ?? '任务失败')
    this.changesEmitter.fire()
    void this.persist()
  }

  private async handlePumpError(error: unknown): Promise<void> {
    if (!this.task || (!this.active && !this.task.active)) return
    const hasRun = Boolean(this.task.runId)
    this.active = hasRun
    if (this.task) this.task.active = hasRun
    this.updateStatus(
      'failed',
      error instanceof Error ? error.message : String(error),
      hasRun,
      hasRun
    )
    await this.persist()
    await showBackendError(error)
  }

  async stop(): Promise<void> {
    const task = this.task
    if (!task?.active) {
      await vscode.window.showInformationMessage('Lunar Gold：当前没有正在执行的任务。')
      return
    }
    if (!task.sessionId) {
      await vscode.window.showWarningMessage('Lunar Gold：任务尚未返回 sessionId，暂时无法停止。')
      return
    }
    const confirmed = await vscode.window.showWarningMessage(
      `确认停止 ${task.runId ? `Run #${task.runId}` : '当前任务'}？后端会取消生成并拒绝待审批。`,
      { modal: true },
      '停止任务'
    )
    if (!confirmed) return
    const ctx = await this.credentials.requireHttpContext()
    if (!ctx) return
    await request<undefined>(ctx, {
      method: 'POST',
      path: '/api/agent/chat/stop',
      query: { sessionId: task.sessionId }
    })
    if (this.task !== task) return
    // 后端取消与本地断流同时执行；abort 后的异步错误不会覆盖“已停止”状态。
    this.abortController?.abort()
    this.finish('CANCELLED')
    await this.persist()
  }

  private async handlePanelAction(action: PanelAction): Promise<void> {
    try {
      if (action.type === 'resume') {
        await this.resumeSaved()
        return
      }
      const ctx = await this.credentials.requireHttpContext()
      if (!ctx) return
      if (action.type === 'approve') {
        await request<undefined>(ctx, {
          method: 'POST',
          path: `/api/agent/approvals/${encodeURIComponent(action.approvalId)}`,
          body: { decision: action.decision, remember: action.remember }
        })
        this.publish([{ type: 'approval_resolved', approvalId: action.approvalId, decision: action.decision }])
        return
      }
      await this.approveAll(ctx)
    } catch (error) {
      if (action.type !== 'resume') {
        await this.restoreApprovals().catch(() => undefined)
        // 重新渲染卡片，恢复点击时被禁用的按钮；服务端已处理时这里会是空列表。
        this.publish([...this.pendingApprovals.values()].map((approval) => ({ type: 'approval', approval })))
      }
      await showBackendError(error)
    }
  }

  private async approveAll(ctx: HttpContext): Promise<void> {
    const sessionId = this.task?.sessionId
    if (!sessionId) throw new Error('缺少 sessionId，无法批量审批。')
    if (this.pendingApprovals.size === 0) {
      await this.restoreApprovals(ctx)
      return
    }
    const result = await request<{ resolvedIds: string[] }>(ctx, {
      method: 'POST',
      path: '/api/agent/approvals/batch',
      body: { sessionId, approvalIds: [...this.pendingApprovals.keys()] }
    })
    this.publish((result.resolvedIds ?? []).map((approvalId) => ({
      type: 'approval_resolved', approvalId, decision: 'APPROVE'
    })))
    await this.restoreApprovals(ctx)
  }

  /** 历史接口只含审批元数据；沿用已知的 preview，重载后则以工具名和风险恢复卡片。 */
  private async restoreApprovals(existing?: HttpContext): Promise<void> {
    const sessionId = this.task?.sessionId
    const ctx = existing ?? await this.credentials.httpContext()
    if (!sessionId || !ctx) return
    const history = await request<ApprovalHistoryItem[]>(ctx, {
      path: `/api/agent/sessions/${sessionId}/approvals`
    })
    if (this.task?.sessionId !== sessionId) return
    const pending = history.filter((item) => item.state === 'PENDING')
    const pendingIds = new Set(pending.map((item) => item.approvalId))
    const events: PanelEvent[] = []
    for (const approvalId of this.pendingApprovals.keys()) {
      if (!pendingIds.has(approvalId)) {
        events.push({ type: 'approval_resolved', approvalId, decision: 'RESOLVED' })
      }
    }
    for (const item of pending) {
      const previous = this.pendingApprovals.get(item.approvalId)
      events.push({
        type: 'approval',
        approval: {
          approvalId: item.approvalId,
          tool: item.tool,
          risk: item.risk,
          preview: previous?.preview ?? null,
          expiresAt: previous?.expiresAt ?? null
        }
      })
    }
    if (events.length > 0) this.publish(events)
  }

  private updateStatus(
    phase: 'idle' | 'connecting' | 'running' | 'waiting_approval' | 'failed' | 'stopped',
    message: string,
    active = false,
    canResume = false
  ): void {
    const labels = {
      idle: '$(circle-outline) Lunar Gold',
      connecting: '$(sync~spin) Lunar Gold：连接中',
      running: '$(rocket) Lunar Gold：运行中',
      waiting_approval: '$(warning) Lunar Gold：等待审批',
      failed: '$(error) Lunar Gold：失败',
      stopped: '$(stop-circle) Lunar Gold：已停止'
    }
    this.statusBar.text = phase === 'running' && this.task?.runId
      ? `$(rocket) Lunar Gold：运行中 #${this.task.runId}`
      : labels[phase]
    this.statusBar.tooltip = message
    this.panel.updateStatus({ phase, message, active, canResume })
  }

  private runningMessage(): string {
    return this.task?.runId ? `Run #${this.task.runId} 正在执行` : '任务正在启动'
  }

  private async persist(): Promise<void> {
    if (this.task) await this.context.workspaceState.update(ACTIVE_TASK_STATE_KEY, { ...this.task })
  }

  private async pickProject(projects: ProjectSummary[]): Promise<ProjectSummary | null> {
    const items: ProjectPick[] = projects.map((project) => ({
      label: project.name,
      description: project.workspacePath ?? '未设置工作区',
      detail: project.description ?? undefined,
      project
    }))
    const picked = await vscode.window.showQuickPick<ProjectPick>(items, {
      title: '选择 Lunar Gold 项目',
      placeHolder: '任务与文件变更都归属于该项目'
    })
    return picked?.project ?? null
  }

  /** null 表示取消；新会话与既有会话分别用返回值 null / number 表示，因此外层用 undefined 判取消。 */
  private async pickSession(ctx: HttpContext, projectId: number): Promise<number | null | undefined> {
    const sessions = await request<SessionSummary[]>(ctx, {
      path: '/api/agent/sessions',
      query: { projectId }
    })
    const items: SessionPick[] = [
      { label: '$(add) 新会话', description: '由后端创建会话', sessionId: null },
      ...sessions.map((session) => ({
        label: session.title || `会话 ${session.id}`,
        description: `#${session.id}${session.lastRunState ? ` · ${session.lastRunState}` : ''}`,
        detail: session.updateTime,
        sessionId: session.id
      }))
    ]
    const picked = await vscode.window.showQuickPick<SessionPick>(items, {
      title: '选择会话',
      placeHolder: '可继续既有会话，或创建新会话'
    })
    return picked ? picked.sessionId : undefined
  }

  /** null 为取消或已请求打开新窗口；mismatch=true 表示用户明确选择继续。 */
  private async checkWorkspace(project: ProjectSummary): Promise<{ mismatch: boolean } | null> {
    if (!project.workspacePath) return { mismatch: false }
    const folders = vscode.workspace.workspaceFolders ?? []
    if (folders.some((folder) => samePath(folder.uri.fsPath, project.workspacePath as string))) {
      return { mismatch: false }
    }
    const action = await vscode.window.showWarningMessage(
      `项目「${project.name}」的工作区是 ${project.workspacePath}，与当前打开文件夹不一致。磁盘 diff 与撤回会作用于项目工作区。`,
      { modal: true },
      '打开该文件夹',
      '仍要继续',
      '取消'
    )
    if (action === '打开该文件夹') {
      await vscode.commands.executeCommand('vscode.openFolder', vscode.Uri.file(project.workspacePath), false)
      return null
    }
    return action === '仍要继续' ? { mismatch: true } : null
  }

  resetAfterDisconnect(): void {
    this.abortController?.abort()
    this.abortController = null
    this.task = null
    this.active = false
    this.pumping = false
    this.terminated = false
    this.reducer = new PanelReducer()
    this.pendingApprovals.clear()
    this.panel.reset()
    this.updateStatus('idle', '尚未发起任务')
    this.changesEmitter.fire()
  }

  dispose(): void {
    this.abortController?.abort()
    this.statusBar.dispose()
    this.changesEmitter.dispose()
  }
}

function samePath(left: string, right: string): boolean {
  const normalize = (value: string): string => {
    const path = value.trim().replace(/[\\/]+/g, '/').replace(/\/+$/, '')
    return process.platform === 'win32' ? path.toLowerCase() : path
  }
  return normalize(left) === normalize(right)
}
