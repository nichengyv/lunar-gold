'use strict';

/**
 * P3-06 本地崩溃诊断：白名单记录、可读错误卡片、脱敏 ZIP 导出。
 * 不依赖渲染进程 Node 能力，不包含远程上传，也不复制用户数据文件。
 */

const crypto = require('node:crypto');
const { execFile } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const ACTION_SCHEME = 'lunargold-diagnostic://';
const MAX_REPORTS = 10;

let ctx = null;
let active = null;
let retrying = false;
let exporting = false;

function init(options) {
  ctx = {
    ...options,
    diagnosticsDir: path.join(options.dataDir, 'diagnostics'),
    reportsDir: path.join(options.dataDir, 'diagnostics', 'reports'),
    sessionMarker: path.join(options.dataDir, 'diagnostics', 'session.json'),
    sidecarLog: path.join(options.dataDir, 'sidecar.log'),
    updaterLog: path.join(path.dirname(options.dataDir), 'updater.log')
  };
  fs.mkdirSync(ctx.reportsDir, { recursive: true });

  // 错误页只是 data: URL，按钮通过自定义导航回到主进程，页面本身无 Node 权限。
  ctx.electron.app.on('web-contents-created', (_event, contents) => {
    contents.on('will-navigate', (event, url) => {
      if (!url.startsWith(ACTION_SCHEME)) return;
      event.preventDefault();
      handleAction(url).catch((err) => {
        ctx.electron.dialog.showErrorBox('诊断操作失败', sanitizeText(err && err.message ? err.message : String(err)));
      });
    });
  });
}

function isReady() {
  return Boolean(ctx);
}

/** 会话标记只在正常退出时删除；主进程硬崩溃后，下次启动可据此补提示。 */
function beginSession() {
  let previous = null;
  try {
    previous = JSON.parse(fs.readFileSync(ctx.sessionMarker, 'utf8'));
  } catch (err) {
    if (err && err.code !== 'ENOENT') previous = { corrupt: true };
  }
  const marker = {
    startedAt: new Date().toISOString(),
    pid: process.pid,
    appVersion: ctx.electron.app.getVersion()
  };
  fs.writeFileSync(ctx.sessionMarker, JSON.stringify(marker, null, 2));
  return previous;
}

function markCleanExit() {
  if (!ctx) return;
  try {
    fs.rmSync(ctx.sessionMarker, { force: true });
  } catch {
    // 清理失败只会在下次启动多一条提示，不影响退出。
  }
}

function classifyJavaError(message) {
  const text = String(message || '');
  if (/Port \d+ was already in use|BindException|Address already in use/i.test(text)) {
    return 'PORT_UNAVAILABLE';
  }
  if (
    /DESKTOP_PG_RUNTIME|PostgreSQL|PSQLException|Flyway|initdb|pg_ctl|postmaster|pgvector|database .*failed|data directory|Connection refused/i.test(
      text
    )
  ) {
    return 'PG_INIT_FAILED';
  }
  return 'JAVA_START_FAILED';
}

function recommendationFor(code) {
  if (code === 'PG_INIT_FAILED') return '请检查数据目录权限和磁盘空间，然后重试；若反复失败，请导出诊断包。';
  if (code === 'PORT_UNAVAILABLE') return '本地端口被占用或暂不可达。可以重试，应用会重新选择空闲端口。';
  if (code.startsWith('ELECTRON_RENDERER')) return '可以重新加载界面；本地项目、对话和知识库不会因导出诊断而被复制。';
  if (code === 'ELECTRON_MAIN_PREVIOUS_EXIT') return '可以继续启动；如果问题反复出现，请导出上次失败后的诊断包。';
  if (code.startsWith('ELECTRON_MAIN')) return '建议重启应用；若问题反复出现，请导出诊断包。';
  return '查看详细日志确认原因后重试；若仍失败，请导出诊断包。';
}

/** 只生成白名单字段，原始 Error、env、用户目录结构不会落进报告。 */
function record(input) {
  const now = new Date();
  const code = String(input.code || 'UNKNOWN_ERROR')
    .toUpperCase()
    .replace(/[^A-Z0-9_-]/g, '_')
    .slice(0, 80);
  const value = {
    schemaVersion: 1,
    id: `${now.toISOString().replace(/[:.]/g, '-')}-${crypto.randomBytes(3).toString('hex')}`,
    timestamp: now.toISOString(),
    appVersion: ctx.electron.app.getVersion(),
    electronVersion: process.versions.electron || 'unknown',
    nodeVersion: process.version,
    os: {
      platform: process.platform,
      release: os.release(),
      arch: process.arch
    },
    source: String(input.source || 'unknown').slice(0, 40),
    code,
    title: sanitizeText(input.title || '应用遇到问题', 160),
    summary: sanitizeText(input.summary || '', 2000),
    details: sanitizeText(input.details || '', 20000),
    recommendation: recommendationFor(code)
  };

  try {
    fs.mkdirSync(ctx.reportsDir, { recursive: true });
    fs.writeFileSync(path.join(ctx.reportsDir, `${value.id}-${code}.json`), JSON.stringify(value, null, 2));
    pruneReports();
  } catch {
    // 磁盘只读/已满时仍显示错误卡片，不能让诊断功能制造二次崩溃。
    value.persistenceWarning = '诊断报告未能写入磁盘；可直接尝试导出 ZIP。';
  }
  return value;
}

function report(input, options = {}) {
  const value = record(input);
  show(value, options);
  return value;
}

function show(value, options = {}) {
  const win = ctx.getWindow && ctx.getWindow();
  if (!win || win.isDestroyed()) return;
  const html = errorPage(value, options);
  const url = `data:text/html;charset=UTF-8,${encodeURIComponent(html)}`;
  active = { record: value, url, contentsId: win.webContents.id, ...options };
  win.loadURL(url).catch((err) => {
    console.error('[diagnostics] 错误页加载失败：', err && err.message);
  });
}

function isShowing(contents) {
  const win = ctx && ctx.getWindow && ctx.getWindow();
  return Boolean(
    active &&
      win &&
      !win.isDestroyed() &&
      active.contentsId === contents.id &&
      contents.getURL() === active.url
  );
}

async function handleAction(url) {
  const parsed = new URL(url);
  const id = parsed.searchParams.get('id');
  if (!active || active.record.id !== id) return;
  const action = parsed.hostname;
  if (action === 'exit') {
    ctx.electron.app.quit();
    return;
  }
  if (action === 'export') {
    if (exporting) return;
    exporting = true;
    try {
      await exportWithDialog(active.record);
    } finally {
      exporting = false;
    }
    return;
  }
  if (action === 'retry' && active.onRetry && !retrying) {
    retrying = true;
    try {
      await active.onRetry(active.record);
    } finally {
      retrying = false;
    }
  }
}

async function exportWithDialog(value) {
  const { dialog, app } = ctx.electron;
  const win = ctx.getWindow && ctx.getWindow();
  const target = win && !win.isDestroyed() ? win : null;
  const saveOptions = {
    title: '导出 Lunar Gold 诊断包',
    defaultPath: path.join(app.getPath('downloads'), `LunarGold-diagnostic-${value.code}-${value.id}.zip`),
    filters: [{ name: 'ZIP 压缩包', extensions: ['zip'] }],
    properties: ['createDirectory', 'showOverwriteConfirmation']
  };
  const result = target
    ? await dialog.showSaveDialog(target, saveOptions)
    : await dialog.showSaveDialog(saveOptions);
  if (result.canceled || !result.filePath) return;
  try {
    await exportRecord(value, result.filePath);
    const messageOptions = {
      type: 'info',
      title: 'Lunar Gold',
      message: '诊断包已导出',
      detail: `保存位置：${result.filePath}\n\n诊断包只保存在你选择的位置，不会自动上传。`,
      buttons: ['知道了'],
      noLink: true
    };
    if (target) await dialog.showMessageBox(target, messageOptions);
    else await dialog.showMessageBox(messageOptions);
  } catch (err) {
    dialog.showErrorBox('诊断包导出失败', sanitizeText(err && err.message ? err.message : String(err)));
  }
}

/** 导出只写 report.json 与两份脱敏日志尾部，绝不遍历或打包整个数据目录。 */
async function exportRecord(value, targetPath) {
  if (process.platform !== 'win32') throw new Error('当前诊断包导出仅支持 Windows');
  const staging = path.join(os.tmpdir(), `lunargold-diagnostic-${crypto.randomBytes(6).toString('hex')}`);
  fs.mkdirSync(staging, { recursive: true });
  try {
    fs.writeFileSync(path.join(staging, 'report.json'), JSON.stringify(value, null, 2));
    fs.writeFileSync(
      path.join(staging, 'sidecar-tail.log'),
      readLogExcerpt(ctx.sidecarLog, { maxBytes: 512 * 1024, maxLines: 400, maxChars: 300000 }) || '（暂无侧车日志）'
    );
    fs.writeFileSync(
      path.join(staging, 'updater-tail.log'),
      readLogExcerpt(ctx.updaterLog, { maxBytes: 256 * 1024, maxLines: 250, maxChars: 150000 }) || '（暂无更新日志）'
    );
    await createZip(staging, targetPath);
    return targetPath;
  } finally {
    try {
      fs.rmSync(staging, { recursive: true, force: true });
    } catch {
      // 临时目录清理由系统兜底，不影响已生成的 ZIP。
    }
  }
}

function createZip(sourceDir, targetPath) {
  return new Promise((resolve, reject) => {
    execFile(
      'powershell.exe',
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        'Get-ChildItem -LiteralPath $env:LUNARGOLD_DIAG_SOURCE -File | Compress-Archive -DestinationPath $env:LUNARGOLD_DIAG_DEST -Force -ErrorAction Stop'
      ],
      {
        windowsHide: true,
        env: { ...process.env, LUNARGOLD_DIAG_SOURCE: sourceDir, LUNARGOLD_DIAG_DEST: targetPath }
      },
      (err, _stdout, stderr) => {
        if (err) reject(new Error(`Compress-Archive 失败：${String(stderr || err.message).trim()}`));
        else resolve();
      }
    );
  });
}

function readLogExcerpt(file, { maxBytes = 65536, maxLines = 80, maxChars = 50000 } = {}) {
  let fd;
  try {
    const size = fs.statSync(file).size;
    const length = Math.min(size, maxBytes);
    const buffer = Buffer.alloc(length);
    fd = fs.openSync(file, 'r');
    fs.readSync(fd, buffer, 0, length, Math.max(0, size - length));
    let text = buffer.toString('utf8');
    if (size > length) text = text.slice(text.indexOf('\n') + 1);
    return sanitizeText(text).split(/\r?\n/).slice(-maxLines).join('\n').slice(-maxChars);
  } catch {
    return '';
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
  }
}

/** 密钥、令牌、用户路径和常见对话字段在写入报告/ZIP 前统一脱敏。 */
function sanitizeText(input, maxChars = 300000) {
  let text = String(input == null ? '' : input)
    .replace(/\u001b\[[0-9;]*m/g, '')
    .replace(/\r\n/g, '\n');

  // LocalTokenService 的历史日志会直接打印无 key=value 标记的令牌，整段消息必须移除。
  text = text
    .split('\n')
    .map((line) => {
      if (!line.includes('LocalTokenService')) return line;
      return line.includes('LocalTokenService -')
        ? line.replace(/LocalTokenService\s*-.*$/, 'LocalTokenService - <REDACTED>')
        : '[LocalTokenService] <REDACTED>';
    })
    .join('\n');

  const sensitivePaths = [
    ctx && ctx.dataDir,
    os.homedir(),
    process.env.USERPROFILE,
    process.env.LOCALAPPDATA,
    process.env.APPDATA
  ]
    .filter(Boolean)
    .map((value) => path.resolve(value))
    .filter((value, index, all) => all.indexOf(value) === index)
    .sort((a, b) => b.length - a.length);
  for (const sensitive of sensitivePaths) {
    text = text.split(sensitive).join('<USER_PATH>');
    text = text.split(sensitive.replace(/\\/g, '/')).join('<USER_PATH>');
  }

  text = text
    .replace(/\b[A-Za-z]:[\\/][^\r\n<>"']+/g, '<PATH>')
    .replace(/\b(?:\/home|\/Users)\/[^\s<>"']+/g, '<USER_PATH>')
    .replace(/file:\/\/[^\s<>"']+/gi, '<PATH>')
    .replace(/(Bearer\s+)[A-Za-z0-9._~+\/-]+/gi, '$1<REDACTED>')
    .replace(/\b(?:sk|pk|key|token)-[A-Za-z0-9_-]{8,}\b/g, '<REDACTED>')
    .replace(/\b(?:ghp|github_pat|xox[baprs])_[A-Za-z0-9_-]+\b/g, '<REDACTED>')
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '<REDACTED>')
    .replace(/(\/\/[^\s/:@]+:)[^\s/@]+(@)/g, '$1<REDACTED>$2')
    .replace(
      /((?:["']?)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|aes[_-]?master[_-]?key|authorization|local[_-]?token)(?:["']?)\s*[:=]\s*)(["'][^"'\n]*["']|[^\s,;&]+)/gi,
      '$1<REDACTED>'
    )
    .replace(
      /((?:["']?)(?:prompt|conversation|chat_history|source_code|knowledge_base)(?:["']?)\s*[:=]\s*)(["'][^"'\n]*["']|[^\s,;&]+)/gi,
      '$1<REDACTED>'
    );

  return text.slice(0, maxChars);
}

function errorPage(value, options) {
  const sidecar = readLogExcerpt(ctx.sidecarLog);
  const updater = readLogExcerpt(ctx.updaterLog, { maxLines: 40, maxChars: 20000 });
  const retryLabel = options.retryLabel || '重试';
  const retryButton = options.onRetry
    ? `<a class="button primary" href="${ACTION_SCHEME}retry?id=${encodeURIComponent(value.id)}">${escapeHtml(retryLabel)}</a>`
    : '';
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<title>Lunar Gold - 运行诊断</title><style>
*{box-sizing:border-box}html,body{margin:0;min-height:100%;background:#111827;color:#e5e7eb;font-family:"Microsoft YaHei",system-ui,sans-serif}
body{padding:36px 20px}.card{max-width:820px;margin:0 auto;background:#1f2937;border:1px solid #374151;border-radius:14px;padding:28px;box-shadow:0 18px 50px rgba(0,0,0,.25)}
.header{display:flex;gap:16px;align-items:flex-start}.icon{flex:0 0 42px;width:42px;height:42px;border-radius:50%;display:grid;place-items:center;background:#4c2d31;color:#fca5a5;font-size:25px;font-weight:700}
h1{font-size:23px;margin:0 0 8px;color:#f9fafb}.summary{margin:0;color:#d1d5db;line-height:1.7;white-space:pre-wrap}.meta{display:flex;flex-wrap:wrap;gap:8px;margin:22px 0}.tag{padding:5px 9px;border-radius:6px;background:#111827;color:#9ca3af;font:12px Consolas,monospace}
.notice{padding:13px 15px;border-left:3px solid #60a5fa;background:#172033;color:#bfdbfe;line-height:1.65;font-size:14px}.privacy{color:#9ca3af;font-size:13px;line-height:1.7;margin:16px 0 0}
.actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:24px}.button{display:inline-block;padding:10px 17px;border-radius:7px;text-decoration:none;font-size:14px;border:1px solid #4b5563;color:#e5e7eb;background:#374151}.button.primary{background:#2563eb;border-color:#2563eb;color:white}.button.danger{color:#fca5a5}
details{margin-top:22px;border-top:1px solid #374151;padding-top:18px}summary{cursor:pointer;color:#93c5fd;font-size:14px}h2{font-size:14px;color:#d1d5db;margin:18px 0 8px}pre{white-space:pre-wrap;word-break:break-word;overflow-wrap:anywhere;background:#111827;border-radius:8px;padding:14px;color:#a7f3d0;font:12px/1.6 Consolas,monospace;max-height:300px;overflow:auto}
.id{margin-top:18px;color:#6b7280;font:11px Consolas,monospace;word-break:break-all}
</style></head><body><main class="card"><div class="header"><div class="icon">!</div><div><h1>${escapeHtml(value.title)}</h1><p class="summary">${escapeHtml(value.summary)}</p></div></div>
<div class="meta"><span class="tag">${escapeHtml(value.code)}</span><span class="tag">v${escapeHtml(value.appVersion)}</span><span class="tag">${escapeHtml(value.os.platform)} ${escapeHtml(value.os.arch)}</span><span class="tag">${escapeHtml(value.timestamp)}</span></div>
<div class="notice">${escapeHtml(value.recommendation)}</div><p class="privacy">诊断仅保存在本机，不会自动上传。导出的 ZIP 只含白名单报告和脱敏日志尾部，不包含工作区源码、对话、知识库、数据库、模型 Key、local-token 或 aes.key。</p>
<div class="actions">${retryButton}<a class="button" href="${ACTION_SCHEME}export?id=${encodeURIComponent(value.id)}">导出诊断包</a><a class="button danger" href="${ACTION_SCHEME}exit?id=${encodeURIComponent(value.id)}">退出</a></div>
<details><summary>查看详细日志（已脱敏）</summary><h2>错误详情</h2><pre>${escapeHtml(value.details || '没有更多错误详情。')}</pre><h2>sidecar.log 末尾</h2><pre>${escapeHtml(sidecar || '暂无侧车日志。')}</pre><h2>updater.log 末尾</h2><pre>${escapeHtml(updater || '暂无更新日志。')}</pre></details>
<div class="id">诊断编号：${escapeHtml(value.id)}</div></main></body></html>`;
}

function escapeHtml(input) {
  return String(input)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function pruneReports() {
  let files;
  try {
    files = fs
      .readdirSync(ctx.reportsDir)
      .filter((name) => name.endsWith('.json'))
      .sort()
      .reverse();
  } catch {
    return;
  }
  for (const name of files.slice(MAX_REPORTS)) {
    try {
      fs.rmSync(path.join(ctx.reportsDir, name), { force: true });
    } catch {
      // 保留多余旧报告不影响当前诊断。
    }
  }
}

module.exports = {
  init,
  isReady,
  beginSession,
  markCleanExit,
  classifyJavaError,
  record,
  report,
  show,
  isShowing,
  exportRecord,
  readLogExcerpt,
  sanitizeText
};
