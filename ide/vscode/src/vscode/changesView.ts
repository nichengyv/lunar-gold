import { isAbsolute, relative, resolve, sep } from 'node:path'
import * as vscode from 'vscode'
import { reconstructBefore } from '../shared/diff.ts'
import { request, type HttpContext } from '../shared/http.ts'
import type { ChangeSetView, ChangeView, ProjectSummary, RevertResult } from '../shared/types.ts'
import { Credentials, showBackendError } from './credentials.ts'
import type { TaskController } from './taskController.ts'

const MAX_TEXT_FILE_BYTES = 512 * 1024

interface RunGroup {
  runId: number | null
  changes: ChangeView[]
}

interface ChangeElement {
  item: vscode.TreeItem
  group?: RunGroup
}

interface CurrentFile {
  text: string | null
  problem: string | null
}

export class ChangesView implements vscode.TreeDataProvider<ChangeElement>, vscode.Disposable {
  private readonly emitter = new vscode.EventEmitter<ChangeElement | undefined | null | void>()
  readonly onDidChangeTreeData = this.emitter.event
  private readonly beforeDocuments = new MemoryDocumentProvider()
  private readonly patchDocuments = new MemoryDocumentProvider()
  private readonly disposables: vscode.Disposable[] = []
  private view: ChangeSetView | null = null
  private refreshing = false
  private refreshPending = false

  constructor(private readonly credentials: Credentials, private readonly tasks: TaskController) {
    this.disposables.push(
      vscode.window.registerTreeDataProvider('lunargold.changes', this),
      vscode.workspace.registerTextDocumentContentProvider('lunargold-before', this.beforeDocuments),
      vscode.workspace.registerTextDocumentContentProvider('lunargold-patch', this.patchDocuments),
      tasks.onChangesRefresh(() => {
        void this.refresh().catch(showBackendError)
      }),
      this.beforeDocuments,
      this.patchDocuments
    )
  }

  get changeSet(): ChangeSetView | null {
    return this.view
  }

  getTreeItem(element: ChangeElement): vscode.TreeItem {
    return element.item
  }

  getChildren(element?: ChangeElement): ChangeElement[] {
    if (element?.group) return fileElements(element.group.changes)
    if (element || !this.view) return []
    const groups = new Map<string, RunGroup>()
    for (const change of this.view.changes) {
      const runId = change.runId ?? null
      const key = runId === null ? 'none' : String(runId)
      const group = groups.get(key) ?? { runId, changes: [] }
      group.changes.push(change)
      groups.set(key, group)
    }
    return [...groups.values()].map((group) => {
      const additions = group.changes.reduce((sum, change) => sum + (change.additions ?? 0), 0)
      const deletions = group.changes.reduce((sum, change) => sum + (change.deletions ?? 0), 0)
      const item = new vscode.TreeItem(
        group.runId === null ? '未关联 Run' : `Run #${group.runId}`,
        vscode.TreeItemCollapsibleState.Expanded
      )
      item.id = `lunargold.run.${group.runId ?? 'none'}`
      item.description = `${group.changes.length} 个文件 · +${additions} -${deletions}`
      item.contextValue = 'lunargold.run'
      item.iconPath = new vscode.ThemeIcon('git-commit')
      return { item, group }
    })
  }

  /** 自动刷新不弹连接引导；手动命令会在 extension 入口先完成连接检查。 */
  async refresh(): Promise<void> {
    if (this.refreshing) {
      this.refreshPending = true
      return
    }
    this.refreshing = true
    try {
      do {
        this.refreshPending = false
        await this.load()
      } while (this.refreshPending)
    } finally {
      this.refreshing = false
    }
  }

  async openChange(change: ChangeView): Promise<void> {
    const task = this.tasks.currentTask
    if (!task) {
      await vscode.window.showInformationMessage('Lunar Gold：当前窗口没有可审查的任务。')
      return
    }
    const ctx = await this.credentials.requireHttpContext()
    if (!ctx) return
    const workspacePath = await this.workspacePath(ctx, task.projectId, task.workspacePath)
    const fileUri = workspaceFileUri(workspacePath, change.filePath)
    const current = await this.readCurrent(fileUri)
    let reason = current.problem

    if (!reason) {
      const action = change.action.toLowerCase()
      if (action === 'create') {
        if (current.text === null) {
          reason = '新建文件当前不存在，可能已被删除或撤回'
        } else {
          const rebuilt = current.text === '' && !change.diffText
            ? { ok: true as const, text: '' }
            : reconstructBefore(change.diffText, current.text)
          if (rebuilt.ok && rebuilt.text === '') {
            await this.showNativeDiff(change, fileUri, '', false)
            return
          }
          reason = rebuilt.ok ? '新建记录的变更前内容不为空，无法核对差异基线' : rebuilt.reason
        }
      } else if (action === 'delete') {
        if (current.text !== null) {
          reason = '文件删除后又被重新创建，当前内容已不能代表变更后的状态'
        } else {
          const rebuilt = reconstructBefore(change.diffText, '')
          if (rebuilt.ok) {
            await this.showNativeDiff(change, fileUri, rebuilt.text, true)
            return
          }
          reason = rebuilt.reason
        }
      } else if (current.text === null) {
        reason = '当前文件不存在，无法与变更后的内容核对'
      } else {
        const rebuilt = reconstructBefore(change.diffText, current.text)
        if (rebuilt.ok) {
          await this.showNativeDiff(change, fileUri, rebuilt.text, false)
          return
        }
        reason = rebuilt.reason
      }
    }

    await this.showPatch(change, fileUri, current, reason ?? '当前文件不可读')
  }

  async revertRun(): Promise<void> {
    const runId = this.tasks.currentTask?.runId
    if (!runId) {
      await vscode.window.showInformationMessage('Lunar Gold：当前任务还没有可撤回的 Run。')
      return
    }
    await this.revert(runId)
  }

  async revertSession(): Promise<void> {
    await this.revert(null)
  }

  dispose(): void {
    this.emitter.dispose()
    for (const disposable of this.disposables) disposable.dispose()
  }

  private async revert(runId: number | null): Promise<void> {
    const sessionId = this.tasks.currentTask?.sessionId
    if (!sessionId) {
      await vscode.window.showInformationMessage('Lunar Gold：当前窗口没有可撤回的会话。')
      return
    }
    const ctx = await this.credentials.requireHttpContext()
    if (!ctx) return
    // 确认前重新拉取影响面，不能用过期 TreeView 计算撤回文件数。
    const view = await request<ChangeSetView>(ctx, {
      path: '/api/file-changes/changeset',
      query: { sessionId, runId: runId ?? undefined }
    })
    const pending = view.changes.filter((change) => change.status === 'PENDING')
    const target = runId === null ? `会话 #${sessionId}` : `Run #${runId}`
    if (pending.length === 0) {
      await this.refresh()
      await vscode.window.showInformationMessage(`Lunar Gold：${target} 没有 PENDING 文件变更。`)
      return
    }
    const paths = pending.slice(0, 12).map((change) => change.filePath)
    if (pending.length > paths.length) paths.push(`…另有 ${pending.length - paths.length} 个文件`)
    const confirmed = await vscode.window.showWarningMessage(
      `确认撤回 ${target} 的 ${pending.length} 个文件变更？`,
      {
        modal: true,
        detail: `${paths.join('\n')}\n\n此操作会修改项目工作区中的磁盘文件；外部再次修改的文件会作为冲突保留。`
      },
      '确认撤回'
    )
    if (!confirmed) return
    if (this.tasks.currentTask?.sessionId !== sessionId) {
      await vscode.window.showWarningMessage('Lunar Gold：会话已切换，撤回已取消。')
      return
    }

    const result = await request<RevertResult>(ctx, {
      method: 'POST',
      path: runId === null ? '/api/file-changes/revert-session' : '/api/file-changes/revert-run',
      query: { sessionId, runId: runId ?? undefined }
    })
    await this.refresh().catch(showBackendError)
    const summary = `撤回完成：成功 ${result.count} 个，失败 ${result.failed} 个，跳过已接受 ${result.skippedAccepted} 个。`
    const conflicts = result.conflicts ?? []
    if (!result.ok || conflicts.length > 0) {
      const details = conflicts.map((conflict) => `${conflict.filePath ?? target}：${conflict.reason}`).join('\n')
      await vscode.window.showWarningMessage(`Lunar Gold：${summary}${details ? `\n${details}` : ''}`)
      return
    }
    await vscode.window.showInformationMessage(`Lunar Gold：${summary}`)
  }

  private async load(): Promise<void> {
    const task = this.tasks.currentTask
    const ctx = await this.credentials.httpContext()
    if (!task?.sessionId || !ctx) {
      this.view = null
      this.emitter.fire()
      return
    }
    const view = await request<ChangeSetView>(ctx, {
      path: '/api/file-changes/changeset',
      query: { sessionId: task.sessionId }
    })
    // 请求期间允许任务终结或断开，但不能让旧会话覆盖新会话的列表。
    if (this.tasks.currentTask?.sessionId !== task.sessionId) return
    this.view = view
    this.beforeDocuments.refresh()
    this.patchDocuments.refresh()
    this.emitter.fire()
  }

  private async workspacePath(
    ctx: HttpContext,
    projectId: number,
    savedPath: string | null
  ): Promise<string> {
    if (savedPath) return savedPath
    const projects = await request<ProjectSummary[]>(ctx, { path: '/api/projects' })
    const workspacePath = projects.find((project) => project.id === projectId)?.workspacePath
    if (!workspacePath) throw new Error('项目未设置工作区路径，无法打开磁盘 diff。')
    return workspacePath
  }

  private async readCurrent(uri: vscode.Uri): Promise<CurrentFile> {
    try {
      const stat = await vscode.workspace.fs.stat(uri)
      if ((stat.type & vscode.FileType.File) === 0) {
        return { text: null, problem: '当前路径不是普通文件' }
      }
      if (stat.size > MAX_TEXT_FILE_BYTES) {
        return { text: null, problem: '当前文件超过 512 KiB，已避免按文本重建差异' }
      }
      const bytes = await vscode.workspace.fs.readFile(uri)
      if (bytes.subarray(0, 512).includes(0)) {
        return { text: null, problem: '当前文件疑似二进制，不能按文本重建差异' }
      }
      const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
      return { text, problem: null }
    } catch (error) {
      const code = (error as { code?: string }).code
      if (code === 'FileNotFound' || code === 'ENOENT') return { text: null, problem: null }
      const detail = error instanceof Error ? error.message : String(error)
      return { text: null, problem: `无法读取当前文本文件：${detail}` }
    }
  }

  private async showNativeDiff(
    change: ChangeView,
    fileUri: vscode.Uri,
    before: string,
    rightEmpty: boolean
  ): Promise<void> {
    const beforeUri = virtualUri('lunargold-before', 'before', change)
    this.beforeDocuments.set(beforeUri, before)
    let rightUri = fileUri
    if (rightEmpty) {
      rightUri = virtualUri('lunargold-before', 'after', change)
      this.beforeDocuments.set(rightUri, '')
    }
    await vscode.commands.executeCommand(
      'vscode.diff',
      beforeUri,
      rightUri,
      `${change.filePath} · Run #${change.runId ?? '—'}（Lunar Gold）`,
      { preview: true }
    )
  }

  private async showPatch(
    change: ChangeView,
    fileUri: vscode.Uri,
    current: CurrentFile,
    reason: string
  ): Promise<void> {
    const patchUri = virtualUri('lunargold-patch', 'patch', change, '.patch')
    const patch = [
      'Lunar Gold 变更预览（后端精简差异，非完整 unified diff）',
      `文件：${change.filePath}`,
      `Run：${change.runId ?? '—'}`,
      `动作：${actionLabel(change.action)}    来源：${change.source}    状态：${change.status}`,
      `无法显示原生对比：${reason}`,
      '',
      '以下内容仅作审查，不代表完整文件，也不会修改磁盘。',
      '────────────────────────────────────────',
      change.diffText || '（后端未提供逐行差异）'
    ].join('\n')
    this.patchDocuments.set(patchUri, patch)
    const patchDocument = await vscode.workspace.openTextDocument(patchUri)
    await vscode.window.showTextDocument(patchDocument, { preview: true, viewColumn: vscode.ViewColumn.One })
    if (current.text !== null) {
      const currentDocument = await vscode.workspace.openTextDocument(fileUri)
      await vscode.window.showTextDocument(currentDocument, { preview: true, viewColumn: vscode.ViewColumn.Beside })
    }
    await vscode.window.showInformationMessage(`Lunar Gold：${reason}，已显示精简差异预览。`)
  }
}

class MemoryDocumentProvider implements vscode.TextDocumentContentProvider, vscode.Disposable {
  private readonly emitter = new vscode.EventEmitter<vscode.Uri>()
  readonly onDidChange = this.emitter.event
  private readonly documents = new Map<string, string>()

  provideTextDocumentContent(uri: vscode.Uri): string {
    return this.documents.get(uri.toString()) ?? ''
  }

  set(uri: vscode.Uri, text: string): void {
    this.documents.set(uri.toString(), text)
    this.emitter.fire(uri)
  }

  refresh(): void {
    for (const key of this.documents.keys()) this.emitter.fire(vscode.Uri.parse(key))
  }

  dispose(): void {
    this.documents.clear()
    this.emitter.dispose()
  }
}

function fileElements(changes: ChangeView[]): ChangeElement[] {
  return changes.map((change) => {
    const item = new vscode.TreeItem(change.filePath, vscode.TreeItemCollapsibleState.None)
    item.id = `lunargold.change.${change.id}`
    item.description = `${actionLabel(change.action)} +${change.additions ?? 0} -${change.deletions ?? 0} · ${change.status}`
    item.tooltip = [
      change.filePath,
      `动作：${actionLabel(change.action)}`,
      `来源：${change.source}`,
      `状态：${change.status}`,
      `增删：+${change.additions ?? 0} / -${change.deletions ?? 0}`
    ].join('\n')
    item.contextValue = 'lunargold.change'
    item.iconPath = new vscode.ThemeIcon(iconForAction(change.action))
    item.command = {
      command: 'lunargold.openChange',
      title: '审查 Lunar Gold 变更',
      arguments: [change]
    }
    return { item }
  })
}

function workspaceFileUri(workspacePath: string, filePath: string): vscode.Uri {
  if (!filePath.trim() || isAbsolute(filePath)) throw new Error('后端返回的变更路径不是有效的相对路径。')
  const root = resolve(workspacePath)
  const target = resolve(root, filePath)
  const rel = relative(root, target)
  if (!rel || rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) {
    throw new Error(`变更路径不在项目工作区内：${filePath}`)
  }
  return vscode.Uri.file(target)
}

function virtualUri(scheme: string, kind: string, change: ChangeView, suffix = ''): vscode.Uri {
  return vscode.Uri.from({
    scheme,
    path: `/${kind}/${change.id}/${change.filePath}${suffix}`
  })
}

function actionLabel(action: string): string {
  switch (action.toLowerCase()) {
    case 'create': return '新增'
    case 'modify': return '修改'
    case 'delete': return '删除'
    case 'command': return '命令'
    default: return action
  }
}

function iconForAction(action: string): string {
  switch (action.toLowerCase()) {
    case 'create': return 'new-file'
    case 'delete': return 'trash'
    case 'command': return 'terminal'
    default: return 'diff-modified'
  }
}
