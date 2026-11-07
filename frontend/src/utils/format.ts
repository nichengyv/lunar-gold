/** 格式化日期时间：2026-10-21T20:30:00 -> 2026-10-21 20:30 */
export function formatDateTime(isoString?: string): string {
  if (!isoString) return '-';
  return isoString.substring(0, 16).replace('T', ' ');
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
