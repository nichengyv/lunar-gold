import * as vscode from 'vscode'
import type { ChangeView } from './shared/types.ts'
import { ChangesView } from './vscode/changesView.ts'
import { Credentials, showBackendError } from './vscode/credentials.ts'
import { PanelProvider } from './vscode/panel.ts'
import { TaskController } from './vscode/taskController.ts'

const COMMANDS = [
  'connect',
  'newTask',
  'openPanel',
  'stop',
  'refreshChanges',
  'revertRun',
  'revertSession',
  'disconnect'
] as const

export function activate(context: vscode.ExtensionContext): void {
  const credentials = new Credentials(context)
  const panel = new PanelProvider(context.extensionUri)
  const tasks = new TaskController(context, credentials, panel)
  const changes = new ChangesView(credentials, tasks)
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider('lunargold.panel', panel),
    tasks,
    changes,
    vscode.commands.registerCommand('lunargold.openChange', async (change: ChangeView) => {
      try {
        await changes.openChange(change)
      } catch (error) {
        await showBackendError(error)
      }
    })
  )
  void tasks.restore().catch(showBackendError)

  for (const command of COMMANDS) {
    context.subscriptions.push(
      vscode.commands.registerCommand(`lunargold.${command}`, async () => {
        try {
          if (command === 'openPanel') {
            await panel.show()
            return
          }
          if (command === 'connect') {
            if (await credentials.connect()) await tasks.resumeSaved()
            return
          }
          if (command === 'disconnect') {
            if (await credentials.disconnect()) tasks.resetAfterDisconnect()
            return
          }
          if (command === 'newTask') {
            await tasks.newTask()
            return
          }
          if (command === 'refreshChanges') {
            if (await credentials.requireHttpContext()) await changes.refresh()
            return
          }
          if (command === 'stop') {
            await tasks.stop()
            return
          }
          if (command === 'revertRun') {
            await changes.revertRun()
            return
          }
          if (command === 'revertSession') {
            await changes.revertSession()
          }
        } catch (error) {
          await showBackendError(error)
        }
      })
    )
  }
}

export function deactivate(): void {
  // 资源均由 context.subscriptions 管理。
}
