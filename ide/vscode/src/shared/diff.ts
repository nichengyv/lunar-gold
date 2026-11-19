export type BeforeRebuildResult =
  | { ok: true; text: string }
  | { ok: false; reason: string }

/**
 * 用 SimpleDiff 的单块差异和当前文件重建变更前内容。
 * 差异被截断、格式不完整或新增块与磁盘不匹配时，必须降级，不能猜测原文。
 */
export function reconstructBefore(diffText: string | null, currentText: string): BeforeRebuildResult {
  if (!diffText?.trim()) return failure('后端没有可用于重建的逐行差异')

  const lines = diffText.split('\n').map((line) => line.endsWith('\r') ? line.slice(0, -1) : line)
  if (lines.at(-1) === '') lines.pop()
  const header = lines.shift()
  const match = header?.match(/^@@ -(\d+),(\d+) \+(\d+),(\d+) @@$/)
  if (!match) return failure('差异不是 SimpleDiff 的单块格式')

  const oldStart = Number(match[1]) - 1
  const oldCount = Number(match[2])
  const newStart = Number(match[3]) - 1
  const newCount = Number(match[4])
  if (oldStart < 0 || newStart < 0 || oldStart !== newStart) {
    return failure('差异块位置无效，无法安全重建原文件')
  }

  const removed: string[] = []
  const added: string[] = []
  for (const line of lines) {
    if (line.startsWith('…（省略 ')) return failure('差异包含省略行，无法完整重建原文件')
    if (line.startsWith('- ')) removed.push(line.slice(2))
    else if (line.startsWith('+ ')) added.push(line.slice(2))
    else return failure('差异包含无法识别的行，无法安全重建原文件')
  }
  if (removed.length !== oldCount || added.length !== newCount) {
    return failure('差异行数与块头不一致，无法安全重建原文件')
  }

  const after = splitLines(currentText)
  if (newStart > after.length || newStart + added.length > after.length) {
    return mismatch()
  }
  for (let index = 0; index < added.length; index++) {
    if (after[newStart + index] !== added[index]) return mismatch()
  }

  const before = [...after]
  before.splice(newStart, added.length, ...removed)
  const eol = currentText.includes('\r\n') ? '\r\n' : '\n'
  return { ok: true, text: before.join(eol) }
}

function splitLines(text: string): string[] {
  if (!text) return []
  return text.split('\n').map((line) => line.endsWith('\r') ? line.slice(0, -1) : line)
}

function mismatch(): BeforeRebuildResult {
  return failure('当前文件与变更后的差异块不匹配，可能已被再次修改')
}

function failure(reason: string): BeforeRebuildResult {
  return { ok: false, reason }
}
