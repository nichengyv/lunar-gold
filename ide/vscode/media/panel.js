(() => {
  const vscode = acquireVsCodeApi()
  const transcript = document.getElementById('transcript')
  const approvalsRoot = document.getElementById('approvals')
  const approvalSection = document.getElementById('approval-section')
  const approvals = new Map()
  const phaseLabels = {
    idle: '空闲', connecting: '连接中', running: '运行中',
    waiting_approval: '等待审批', failed: '失败', stopped: '已停止'
  }

  function element(tag, className, text) {
    const node = document.createElement(tag)
    if (className) node.className = className
    if (text !== undefined) node.textContent = String(text)
    return node
  }

  function clearEmpty() {
    document.getElementById('empty')?.remove()
  }

  function appendContent(text) {
    clearEmpty()
    let block = transcript.lastElementChild
    if (!block || !block.classList.contains('content-block')) {
      block = element('div', 'content-block')
      transcript.appendChild(block)
    }
    block.textContent += text
    block.scrollIntoView({ block: 'nearest' })
  }

  function appendEvent(event) {
    if (event.type === 'session') {
      document.getElementById('session-id').textContent = event.sessionId ?? '—'
      return
    }
    if (event.type === 'run') {
      document.getElementById('run-id').textContent = event.runId ?? '—'
      return
    }
    if (event.type === 'turn') {
      document.getElementById('turn-id').textContent = event.turnId
      return
    }
    if (event.type === 'content') {
      appendContent(event.text)
      return
    }
    clearEmpty()
    if (event.type === 'tool') {
      const text = event.phase === 'call'
        ? `→ ${event.tool}${event.args ? ` ${event.args}` : ''}`
        : `← ${event.tool} ${event.success ? '成功' : '失败'}${event.costMs === null ? '' : ` · ${event.costMs}ms`}${event.error ? ` · ${event.error}` : ''}`
      transcript.appendChild(element('div', `tool-row${event.phase === 'result' && !event.success ? ' failed' : ''}`, text))
    } else if (event.type === 'file_change') {
      transcript.appendChild(element('div', 'file-row', `± ${event.path}  +${event.additions} −${event.deletions}`))
    } else if (event.type === 'approval') {
      addApproval(event.approval)
    } else if (event.type === 'approval_resolved') {
      if (event.approvalId) removeApproval(event.approvalId)
      else {
        approvals.clear()
        renderApprovals()
      }
    } else if (event.type === 'done') {
      const terminal = element('div', 'terminal', event.completion?.message || '任务结束')
      terminal.appendChild(element('span', 'unverified', '未验证'))
      transcript.appendChild(terminal)
    } else if (event.type === 'error') {
      transcript.appendChild(element('div', 'error-row', event.message))
    }
  }

  function addApproval(approval) {
    approvals.set(approval.approvalId, approval)
    renderApprovals()
  }

  function removeApproval(id) {
    approvals.delete(id)
    renderApprovals()
  }

  function renderApprovals() {
    approvalsRoot.replaceChildren()
    approvalSection.hidden = approvals.size === 0
    document.getElementById('approve-all').hidden = approvals.size < 2
    for (const approval of approvals.values()) {
      const card = element('article', 'approval-card')
      card.dataset.approvalId = approval.approvalId
      card.appendChild(element('h3', '', approval.tool))
      card.appendChild(element('p', 'risk', `风险：${approval.risk || '未标注'}`))
      const preview = approval.preview || {}
      if (preview.path) card.appendChild(element('p', '', `文件：${preview.path}`))
      if (preview.command) card.appendChild(element('p', '', `命令：${preview.command}`))
      if (preview.note) card.appendChild(element('p', '', `说明：${preview.note}`))
      if (preview.additions !== undefined || preview.deletions !== undefined) {
        card.appendChild(element('p', '', `变更：+${preview.additions || 0} −${preview.deletions || 0}`))
      }
      if (approval.expiresAt !== null && approval.expiresAt !== undefined) {
        card.appendChild(element('p', '', `过期：${approval.expiresAt}`))
      }
      const actions = element('div', 'approval-actions')
      actions.appendChild(actionButton('批准', 'approve', 'APPROVE', 'NONE'))
      actions.appendChild(actionButton('批准并记住', 'approve', 'APPROVE', 'SESSION'))
      actions.appendChild(actionButton('拒绝', 'approve secondary', 'REJECT', 'NONE'))
      card.appendChild(actions)
      approvalsRoot.appendChild(card)
    }
  }

  function actionButton(label, className, decision, remember) {
    const button = element('button', className, label)
    button.dataset.action = 'approve'
    button.dataset.decision = decision
    button.dataset.remember = remember
    return button
  }

  document.body.addEventListener('click', (event) => {
    const button = event.target.closest('button')
    if (!button) return
    if (button.dataset.command) {
      vscode.postMessage({ type: button.dataset.command })
      return
    }
    if (button.dataset.action === 'approve') {
      const card = button.closest('[data-approval-id]')
      card.querySelectorAll('button').forEach((item) => { item.disabled = true })
      vscode.postMessage({
        type: 'approve', approvalId: card.dataset.approvalId,
        decision: button.dataset.decision, remember: button.dataset.remember
      })
      return
    }
    if (button.dataset.action) vscode.postMessage({ type: button.dataset.action })
  })

  window.addEventListener('message', (message) => {
    const data = message.data
    if (data.type === 'reset') {
      transcript.replaceChildren()
      const empty = element('div', 'empty', '等待任务事件…')
      empty.id = 'empty'
      transcript.appendChild(empty)
      for (const id of ['session-id', 'run-id', 'turn-id']) document.getElementById(id).textContent = '—'
      approvals.clear()
      renderApprovals()
      return
    }
    if (data.type === 'events') {
      for (const event of data.events) appendEvent(event)
      return
    }
    if (data.type === 'status') {
      const status = data.status
      document.getElementById('phase').textContent = phaseLabels[status.phase] || status.phase
      document.getElementById('status-message').textContent = status.message || ''
      document.getElementById('stop-button').hidden = !status.active
      document.getElementById('resume-button').hidden = !status.canResume
      document.getElementById('workspace-warning').hidden = !status.workspaceMismatch
      const notice = document.getElementById('notice')
      notice.hidden = !status.message || status.phase === 'idle' || status.phase === 'running'
      notice.textContent = status.message || ''
    }
  })
})()
