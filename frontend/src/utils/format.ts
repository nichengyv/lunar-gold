/** 格式化日期时间：2026-10-21T20:30:00 -> 2026-10-21 20:30 */
export function formatDateTime(isoString?: string): string {
  if (!isoString) return '-';
  return isoString.substring(0, 16).replace('T', ' ');
}

/** 相对时间：刚刚 / 5 分钟前 / 3 小时前 / 2 天前，超过一周退回日期；用于侧栏任务列表 */
export function formatRelativeTime(isoString?: string): string {
  if (!isoString) return '-';
  const time = new Date(isoString).getTime();
  if (Number.isNaN(time)) return formatDateTime(isoString);
  const diff = Date.now() - time;
  if (diff < 60_000) return '刚刚';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`;
  return isoString.substring(0, 10);
}

export function formatFileSize(bytes?: number): string {
  if (!bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  let size = bytes;
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024;
    i++;
  }
  return size.toFixed(1) + ' ' + units[i];
}

export function getFileIcon(ext?: string): string {
  const map: Record<string, string> = {
    pdf: 'file-text',
    doc: 'file-text',
    docx: 'file-text',
    xls: 'file-spreadsheet',
    xlsx: 'file-spreadsheet',
    txt: 'file-text',
    md: 'file-text',
    csv: 'file-spreadsheet'
  };
  return map[ext?.toLowerCase() || ''] || 'file';
}

export async function copyToClipboard(text: string): Promise<void> {
  await navigator.clipboard.writeText(text);
}
