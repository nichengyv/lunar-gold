'use strict';

/**
 * P3-05 Windows 自动更新与失败回滚。
 *
 * 原则：
 *   1. 只检查并提示，不强制立即更新；开发态（未打包）完全不检查。
 *   2. 下载后先校验（sha512 由 electron-updater 负责；签名按开关），校验不过不安装。
 *   3. 安装前把数据目录整目录备份到 <dataDir>/../backup，并写升级标记；
 *      新版本首启成功后清标记，首启失败则由用户选择回滚。
 *   4. 回滚 = 恢复数据备份 + 静默装回上一版本安装包（从发布源下载）。
 *
 * 环境变量（供回归/排障使用）：
 *   LUNARGOLD_DISABLE_UPDATE=1        关闭自动更新
 *   LUNARGOLD_UPDATE_FEED=<url>       覆盖更新源（generic 目录，本地回归用）
 *   LUNARGOLD_UPDATE_MODE=auto        无人值守：不弹窗，下载后静默安装并重启
 *   LUNARGOLD_UPDATE_INITIAL_DELAY_MS 首启后延迟多久检查，默认 8000
 *   LUNARGOLD_REQUIRE_SIGNATURE=1     安装前强制校验 Authenticode（签名接入后启用）
 */

const { app, dialog, shell } = require('electron');
const { autoUpdater } = require('electron-updater');
const { spawn, spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const https = require('node:https');

const GITHUB_OWNER = 'nichengyv';
const GITHUB_REPO = 'lunar-gold';
const PRODUCT = 'Lunar Gold';
const BACKUP_KEEP = 2;
const MIN_FREE_MB = 1024;
const UPGRADE_MARKER = 'upgrade-state.json';

let ctx = null; // { getWindow, dataDir, rootDir, logPath, stopSidecar }
let checking = false;
let installing = false;

function log(message) {
  const line = `[${new Date().toISOString()}] ${message}\n`;
  if (process.env.LUNARGOLD_UPDATE_VERBOSE === '1') process.stdout.write(line);
  try {
    if (ctx && ctx.rootDir) fs.appendFileSync(path.join(ctx.rootDir, 'updater.log'), line);
  } catch {
    // 日志写不进去不影响升级主链路
  }
}

function getWindow() {
  const win = ctx && ctx.getWindow ? ctx.getWindow() : null;
  return win && !win.isDestroyed() ? win : undefined;
}

function isSupported() {
  return app.isPackaged && process.platform === 'win32' && process.env.LUNARGOLD_DISABLE_UPDATE !== '1';
}

/**
 * @param {object} options
 * @param {() => import('electron').BrowserWindow|null} options.getWindow
 * @param {string} options.dataDir   桌面数据目录（数据库、令牌、AES key 都在这）
 * @param {() => void} options.stopSidecar  回滚前终止 Java/PG 进程树
 * @param {() => Promise<void>} [options.restartSidecar]  备份后若用户选择「退出时安装」，重新拉起侧车
 */
function initUpdater(options) {
  ctx = { ...options, rootDir: path.dirname(options.dataDir) };
  autoUpdater.autoDownload = false;
  autoUpdater.autoInstallOnAppQuit = false;
  autoUpdater.allowPrerelease = false;
  autoUpdater.allowDowngrade = false;
  try {
    autoUpdater.disableWebInstaller = true;
  } catch {
    // 老版本属性不存在时忽略
  }
  autoUpdater.logger = {
    info: (msg) => log(`[lib] ${msg}`),
    warn: (msg) => log(`[lib:warn] ${msg}`),
    error: (msg) => log(`[lib:error] ${msg}`),
    debug: () => {}
  };

  const feed = process.env.LUNARGOLD_UPDATE_FEED;
  if (feed) {
    const url = feed.endsWith('/') ? feed : `${feed}/`;
    log(`使用覆盖更新源 ${url}`);
    autoUpdater.setFeedURL({ provider: 'generic', url });
  }

  autoUpdater.on('download-progress', (progress) => {
    const win = getWindow();
    if (win && Number.isFinite(progress.percent)) {
      win.setProgressBar(Math.min(progress.percent / 100, 1));
    }
  });
  autoUpdater.on('error', (err) => log(`更新出错：${err && err.message}`));

  log(`更新器就绪：当前 v${app.getVersion()}，打包态=${app.isPackaged}`);
}

function resetProgress() {
  const win = getWindow();
  if (win) win.setProgressBar(-1);
}

function parseVersion(version) {
  return String(version || '0.0.0')
    .split('-')[0]
    .split('.')
    .map((part) => Number.parseInt(part, 10) || 0);
}

function isNewer(candidate, current) {
  const a = parseVersion(candidate);
  const b = parseVersion(current);
  for (let i = 0; i < Math.max(a.length, b.length); i += 1) {
    const left = a[i] || 0;
    const right = b[i] || 0;
    if (left !== right) return left > right;
  }
  return false;
}

function scheduleUpdateCheck() {
  if (!ctx || !isSupported()) {
    log('自动更新未启用（未打包或非 Windows，或已显式关闭）');
    return;
  }
  const delay = Number.parseInt(process.env.LUNARGOLD_UPDATE_INITIAL_DELAY_MS || '8000', 10);
  setTimeout(() => {
    checkForUpdates({ silent: true }).catch((err) => log(`检查更新异常：${err && err.message}`));
  }, Number.isFinite(delay) && delay >= 0 ? delay : 8000);
}

async function checkForUpdates({ silent = true } = {}) {
  if (!ctx || !isSupported()) return null;
  if (checking || installing) return null;
  checking = true;
  try {
    const result = await autoUpdater.checkForUpdates();
    const info = result && result.updateInfo;
    if (!info || !isNewer(info.version, app.getVersion())) {
      log(`没有可用更新（远端 v${info ? info.version : '未知'}，当前 v${app.getVersion()}）`);
      if (!silent) {
        dialog.showMessageBoxSync(getWindow(), {
          type: 'info',
          title: PRODUCT,
          message: '已是最新版本',
          detail: `当前版本 v${app.getVersion()}。`,
          buttons: ['知道了'],
          noLink: true
        });
      }
      return null;
    }

    log(`发现新版本 v${info.version}（当前 v${app.getVersion()}）`);
    if (process.env.LUNARGOLD_UPDATE_MODE === 'auto') {
      await downloadAndInstall(info, { auto: true });
      return info;
    }

    const choice = dialog.showMessageBoxSync(getWindow(), {
      type: 'info',
      title: PRODUCT,
      message: `发现新版本 v${info.version}`,
      detail: `当前版本 v${app.getVersion()}。安装前会自动备份本地数据，下载完成后可自行选择重启时机。`,
      buttons: ['下载更新', '稍后提醒'],
      defaultId: 0,
      cancelId: 1,
      noLink: true
    });
    if (choice !== 0) {
      log('用户选择稍后提醒');
      return info;
    }
    await downloadAndInstall(info);
    return info;
  } catch (err) {
    log(`检查更新失败：${err && err.message}`);
    if (!silent) {
      dialog.showMessageBoxSync(getWindow(), {
        type: 'warning',
        title: PRODUCT,
        message: '检查更新失败',
        detail: `${err && err.message ? err.message : String(err)}\n\n可稍后重试，或到发布页手动下载。`,
        buttons: ['知道了'],
        noLink: true
      });
    }
    return null;
  } finally {
    checking = false;
  }
}

async function downloadAndInstall(info, { auto = false } = {}) {
  if (installing) return;
  installing = true;
  let backupPath = null;
  let sidecarStopped = false;
  try {
    ensureBackupCapacity();
    log(`开始下载 v${info.version}`);
    await autoUpdater.downloadUpdate();
    resetProgress();

    const cached = findDownloadedInstaller();
    await verifyInstaller(cached);
    log(`下载校验通过：${cached}`);

    // 备份前停掉侧车（含内嵌 PG），保证 robocopy 拿到的是一致性快照，
    // 否则运行中的 WAL/数据文件可能被复制成半写入状态
    if (ctx.stopSidecar) {
      ctx.stopSidecar();
      sidecarStopped = true;
      log('已停止侧车，准备备份数据');
    }
    backupPath = backupDataDir();
    writeUpgradeMarker({ fromVersion: app.getVersion(), toVersion: info.version, backupPath });
    log(`数据已备份到 ${backupPath}，准备安装 v${info.version}`);

    if (process.env.LUNARGOLD_UPDATE_MODE === 'auto') {
      autoUpdater.quitAndInstall(true, true);
      return;
    }

    const choice = dialog.showMessageBoxSync(getWindow(), {
      type: 'info',
      title: PRODUCT,
      message: `v${info.version} 已准备就绪`,
      detail: '立即重启会关闭本地服务并完成安装。选择「退出时安装」则在你下次正常退出应用时完成。',
      buttons: ['立即重启安装', '退出时安装'],
      defaultId: 0,
      cancelId: 1,
      noLink: true
    });
    if (choice === 0) {
      autoUpdater.quitAndInstall(true, true);
    } else {
      // 用户选择稍后安装：把侧车拉回来，让用户能继续使用
      if (sidecarStopped && ctx.restartSidecar) {
        try { await ctx.restartSidecar(); } catch (e) { log(`重启侧车失败：${e && e.message}`); }
      }
      // 已经备份过，退出时静默安装不会丢数据
      autoUpdater.autoInstallOnAppQuit = true;
      log('用户选择退出时安装');
    }
  } catch (err) {
    // 出错也要把侧车拉回来，避免用户卡在空白窗口
    if (sidecarStopped && ctx.restartSidecar) {
      try { await ctx.restartSidecar(); } catch (e) { log(`重启侧车失败：${e && e.message}`); }
    }
    resetProgress();
    installing = false;
    clearUpgradeMarker();
    log(`下载或安装准备失败：${err && err.message}`);
    if (process.env.LUNARGOLD_UPDATE_MODE === 'auto') return;
    dialog.showMessageBoxSync(getWindow(), {
      type: 'error',
      title: PRODUCT,
      message: '更新没有完成',
      detail: `${err && err.message ? err.message : String(err)}\n\n当前版本 v${app.getVersion()} 未受影响。`,
      buttons: ['知道了'],
      noLink: true
    });
  }
}

/** 升级标记与当前版本一致说明本次启动发生在升级之后 */
function pendingUpgrade() {
  if (!ctx) return null;
  const marker = readUpgradeMarker();
  if (!marker) return null;
  if (marker.toVersion !== app.getVersion()) {
    // 回滚完成后旧版本会看到指向新版本的标记，直接清掉避免长期残留
    log(`清理与当前版本不匹配的升级标记（标记 v${marker.toVersion}，当前 v${app.getVersion()}）`);
    clearUpgradeMarker();
    return null;
  }
  return marker;
}

function markStartupSuccess() {
  if (!ctx) return;
  if (!readUpgradeMarker()) return;
  log('升级后首启成功，清理升级标记');
  clearUpgradeMarker();
  pruneBackups();
}

function readUpgradeMarker() {
  try {
    const raw = fs.readFileSync(path.join(ctx.rootDir, UPGRADE_MARKER), 'utf8');
    const marker = JSON.parse(raw);
    return marker && marker.toVersion ? marker : null;
  } catch {
    return null;
  }
}

function writeUpgradeMarker(marker) {
  const file = path.join(ctx.rootDir, UPGRADE_MARKER);
  fs.writeFileSync(file, JSON.stringify({ ...marker, startedAt: new Date().toISOString() }, null, 2));
}

function clearUpgradeMarker() {
  try {
    fs.rmSync(path.join(ctx.rootDir, UPGRADE_MARKER), { force: true });
  } catch {
    // 清不掉也不影响下次启动
  }
}

function dirSize(target) {
  let total = 0;
  const stack = [target];
  while (stack.length) {
    const current = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(current, entry.name);
      try {
        if (entry.isDirectory()) stack.push(full);
        else if (entry.isFile()) total += fs.statSync(full).size;
      } catch {
        // 备份期间文件可能被替换，忽略单个条目
      }
    }
  }
  return total;
}

function ensureBackupCapacity() {
  const minFreeMb = Number.parseInt(process.env.LUNARGOLD_UPDATE_MIN_FREE_MB || String(MIN_FREE_MB), 10);
  let freeMb;
  try {
    const stat = fs.statfsSync(ctx.rootDir);
    freeMb = Math.floor((stat.bavail * stat.bsize) / (1024 * 1024));
  } catch {
    log('无法读取磁盘剩余空间，跳过容量预检');
    return;
  }
  const needMb = Math.ceil(dirSize(ctx.dataDir) / (1024 * 1024)) + minFreeMb;
  if (freeMb < needMb) {
    throw new Error(`磁盘空间不足：更新与备份至少需要约 ${needMb} MB，当前可用 ${freeMb} MB`);
  }
}

function backupDataDir() {
  const backupsRoot = path.join(ctx.rootDir, 'backup');
  fs.mkdirSync(backupsRoot, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const dest = path.join(backupsRoot, `${app.getVersion()}-${stamp}`);

  const result = spawnSync(
    'robocopy',
    [ctx.dataDir, dest, '/E', '/R:1', '/W:1', '/NFL', '/NDL', '/NJH', '/NJS', '/XJ'],
    { windowsHide: true }
  );
  // robocopy 0~7 都算成功（8 以上才是失败）
  if (result.status === null || result.status >= 8) {
    throw new Error(`数据备份失败（robocopy 退出码 ${result.status}）`);
  }
  log(`备份完成 ${dest}`);
  pruneBackups(dest);
  return dest;
}

function pruneBackups(keepPath) {
  const backupsRoot = path.join(ctx.rootDir, 'backup');
  let entries;
  try {
    entries = fs
      .readdirSync(backupsRoot, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => path.join(backupsRoot, entry.name))
      .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
  } catch {
    return;
  }
  const keep = new Set([keepPath, ...entries.slice(0, BACKUP_KEEP)].filter(Boolean));
  for (const entry of entries) {
    if (keep.has(entry)) continue;
    try {
      fs.rmSync(entry, { recursive: true, force: true });
      log(`清理旧备份 ${entry}`);
    } catch {
      // 清理失败不影响升级
    }
  }
}

/** electron-updater 把安装包缓存在 <LOCALAPPDATA>\<name>-updater\pending；取最新一个用于签名校验 */
function findDownloadedInstaller() {
  const helper = autoUpdater.downloadedUpdateHelper;
  if (helper && helper.cachedFile && fs.existsSync(helper.cachedFile)) return helper.cachedFile;

  const localAppData = process.env.LOCALAPPDATA || '';
  const pendingDirs = [];
  // 缓存目录名由 app-update.yml 的 updaterCacheDirName 决定，不写死，避免改名后失配
  try {
    for (const name of fs.readdirSync(localAppData)) {
      if (name.toLowerCase().endsWith('-updater')) {
        pendingDirs.push(path.join(localAppData, name, 'pending'));
      }
    }
  } catch {
    // 目录读不到就走下面的兜底
  }

  const exes = [];
  for (const dir of pendingDirs) {
    try {
      for (const name of fs.readdirSync(dir)) {
        if (name.toLowerCase().endsWith('.exe')) exes.push(path.join(dir, name));
      }
    } catch {
      // pending 目录可能还没创建
    }
  }
  if (exes.length === 0) throw new Error('找不到已下载的安装包，无法继续安装');
  exes.sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
  return exes[0];
}

function authenticodeStatus(file) {
  const escaped = file.replace(/'/g, "''");
  const result = spawnSync(
    'powershell',
    ['-NoProfile', '-NonInteractive', '-Command', `(Get-AuthenticodeSignature -LiteralPath '${escaped}').Status`],
    { encoding: 'utf8', windowsHide: true }
  );
  return (result.stdout || '').trim();
}

async function verifyInstaller(file) {
  if (process.env.LUNARGOLD_REQUIRE_SIGNATURE !== '1') {
    log('未开启签名强制校验（SignPath 接入前）');
    return;
  }
  const status = authenticodeStatus(file);
  if (status !== 'Valid') {
    throw new Error(`安装包签名校验失败（状态 ${status || '未知'}），已放弃安装`);
  }
  log('安装包签名有效');
}

/** 首启失败时由 main.js 调用：恢复数据备份并装回上一版本 */
async function rollbackAfterFailedUpgrade(marker) {
  const fromVersion = marker.fromVersion;
  const auto = process.env.LUNARGOLD_UPDATE_MODE === 'auto';
  if (!auto) {
    let choice = -1;
    while (choice === -1) {
      choice = dialog.showMessageBoxSync(getWindow(), {
        type: 'error',
        title: PRODUCT,
        message: `升级到 v${marker.toVersion} 后本地服务没能启动`,
        detail:
          `可以回滚到 v${fromVersion}：先恢复升级前的数据备份，再静默安装上一版本。\n` +
          `日志：${ctx.logPath}`,
        buttons: ['回滚到上一版本', '打开日志', '退出'],
        defaultId: 0,
        cancelId: 2,
        noLink: true
      });
      if (choice === 1) {
        shell.openPath(ctx.logPath);
        choice = -1;
      }
    }
    if (choice !== 0) {
      clearUpgradeMarker();
      app.quit();
      return;
    }
  }

  try {
    log(`开始回滚到 v${fromVersion}`);
    if (ctx.stopSidecar) ctx.stopSidecar();
    restoreData(marker.backupPath);
    const installer = await downloadInstaller(fromVersion);
    await verifyRollbackInstaller(installer, fromVersion);
    log(`回滚安装包就绪：${installer}`);
    // 与 electron-updater 的升级安装同一条路径：独立进程启动安装器后立即退出应用，
    // 由安装器的 --updated /S 静默覆盖并 --force-run 重新拉起；/D 必须放最后，指回当前安装目录
    const appDir = path.dirname(process.execPath);
    const child = spawn(
      installer,
      ['--updated', '/S', '--force-run', `/D=${appDir}`],
      { detached: true, stdio: 'ignore', windowsHide: true }
    );
    child.on('error', (err) => log(`启动回滚安装器失败：${err && err.message}`));
    child.unref();
    log('已启动回滚安装器，应用即将退出');
    app.quit();
  } catch (err) {
    log(`回滚失败：${err && err.message}`);
    dialog.showMessageBoxSync(getWindow(), {
      type: 'error',
      title: PRODUCT,
      message: '回滚没有完成',
      detail:
        `${err && err.message ? err.message : String(err)}\n\n` +
        `数据备份仍在：${marker.backupPath}\n` +
        `请到发布页手动安装 v${fromVersion}，或联系支持。`,
      buttons: ['知道了'],
      noLink: true
    });
  }
}

function restoreData(backupPath) {
  if (!backupPath || !fs.existsSync(backupPath)) {
    throw new Error(`找不到升级前的数据备份：${backupPath || '(空)'}`);
  }
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const tempDir = `${ctx.dataDir}.restoring-${stamp}`;
  const failedDir = `${ctx.dataDir}.failed-${stamp}`;

  // 先把备份复制到临时目录，成功后再做目录切换。
  // 这样即使复制中途失败，原数据目录也纹丝不动，用户还能手动回退。
  const copyResult = spawnSync(
    'robocopy',
    [backupPath, tempDir, '/E', '/R:1', '/W:1', '/NFL', '/NDL', '/NJH', '/NJS', '/XJ'],
    { windowsHide: true }
  );
  if (copyResult.status === null || copyResult.status >= 8) {
    try { fs.rmSync(tempDir, { recursive: true, force: true }); } catch { /* 清理不出去也不影响 */ }
    throw new Error(`恢复数据备份失败（robocopy 退出码 ${copyResult.status}）`);
  }

  // 复制成功：把当前数据目录改名为失败现场保留，临时目录转正
  if (fs.existsSync(ctx.dataDir)) {
    fs.renameSync(ctx.dataDir, failedDir);
    log(`当前数据目录已保留到 ${failedDir}`);
  }
  fs.renameSync(tempDir, ctx.dataDir);

  // 备份里的 postmaster.pid 指向旧进程，留着会误杀无关进程
  fs.rmSync(path.join(ctx.dataDir, 'db', 'postmaster.pid'), { force: true });
  log(`数据已恢复到 ${ctx.dataDir}`);
}

function downloadInstaller(version) {
  const fileName = `LunarGold-Setup-${version}.exe`;
  const feed = process.env.LUNARGOLD_UPDATE_FEED;
  const url = feed
    ? `${feed.endsWith('/') ? feed : `${feed}/`}${fileName}`
    : `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/releases/download/v${version}/${fileName}`;
  const dest = path.join(os.tmpdir(), 'lunargold-rollback', fileName);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  log(`下载回滚安装包 ${url}`);
  return downloadFile(url, dest, 5).then(() => dest);
}

function downloadFile(url, dest, redirects) {
  const client = url.startsWith('http:') ? require('node:http') : https;
  return new Promise((resolve, reject) => {
    const request = client.get(url, { headers: { 'User-Agent': PRODUCT }, timeout: 60000 }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects > 0) {
        res.resume();
        const next = new URL(res.headers.location, url).toString();
        resolve(downloadFile(next, dest, redirects - 1));
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error(`下载失败 HTTP ${res.statusCode}`));
        return;
      }
      const file = fs.createWriteStream(dest);
      res.pipe(file);
      file.on('finish', () => file.close(() => resolve()));
      file.on('error', reject);
    });
    request.on('timeout', () => request.destroy(new Error('下载超时')));
    request.on('error', reject);
  });
}

/** 下载文本内容（用于取 latest.yml） */
function downloadText(url, redirects) {
  const client = url.startsWith('http:') ? require('node:http') : https;
  return new Promise((resolve, reject) => {
    const request = client.get(url, { headers: { 'User-Agent': PRODUCT }, timeout: 30000 }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects > 0) {
        res.resume();
        const next = new URL(res.headers.location, url).toString();
        resolve(downloadText(next, redirects - 1));
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error(`下载失败 HTTP ${res.statusCode}`));
        return;
      }
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => resolve(data));
    });
    request.on('timeout', () => request.destroy(new Error('下载超时')));
    request.on('error', reject);
  });
}

/**
 * 从 latest.yml 里解析指定安装包的 sha512。
 * electron-builder 生成的 latest.yml 顶层有 path/sha512，files 数组里也有，两处一致。
 */
function extractSha512(ymlText, fileName) {
  if (!ymlText) return null;
  const lines = ymlText.split(/\r?\n/);
  let currentFile = null;
  for (const line of lines) {
    const urlMatch = line.match(/^\s*url:\s*(.+)$/);
    if (urlMatch) {
      currentFile = urlMatch[1].trim().replace(/^["']|["']$/g, '');
      continue;
    }
    if (currentFile === fileName) {
      const shaMatch = line.match(/^\s*sha512:\s*(.+)$/);
      if (shaMatch) return shaMatch[1].trim().replace(/^["']|["']$/g, '');
    }
    // 顶层 path/sha512 兜底
    const pathMatch = line.match(/^path:\s*(.+)$/);
    if (pathMatch) currentFile = pathMatch[1].trim().replace(/^["']|["']$/g, '');
  }
  return null;
}

/** 计算文件的 sha512（base64，与 electron-builder latest.yml 格式一致） */
function sha512OfFile(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha512');
    const stream = fs.createReadStream(file);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('end', () => resolve(hash.digest('base64')));
    stream.on('error', reject);
  });
}

/**
 * 回滚安装包校验：sha512（强校验）+ 可选 Authenticode 签名。
 * 升级时 electron-updater 会校验 latest.yml 的 sha512，但回滚是手动下载，必须自己校验。
 */
async function verifyRollbackInstaller(file, version) {
  const fileName = `LunarGold-Setup-${version}.exe`;
  const feed = process.env.LUNARGOLD_UPDATE_FEED;
  const ymlUrl = feed
    ? `${feed.endsWith('/') ? feed : `${feed}/`}latest.yml`
    : `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/releases/download/v${version}/latest.yml`;

  let expected = null;
  try {
    const yml = await downloadText(ymlUrl, 5);
    expected = extractSha512(yml, fileName);
  } catch (err) {
    log(`获取 v${version} 的 latest.yml 失败，跳过 sha512 校验：${err && err.message}`);
  }

  if (expected) {
    const actual = await sha512OfFile(file);
    if (actual !== expected) {
      throw new Error(`回滚安装包 sha512 校验失败，安装包可能已损坏或被篡改`);
    }
    log('回滚安装包 sha512 校验通过');
  } else {
    log(`无法获取 v${version} 的 sha512，跳过哈希校验`);
  }

  // 签名校验（与升级链路一致，SignPath 接入后开启）
  if (process.env.LUNARGOLD_REQUIRE_SIGNATURE === '1') {
    const status = authenticodeStatus(file);
    if (status !== 'Valid') {
      throw new Error(`回滚安装包签名校验失败（状态 ${status || '未知'}），已放弃安装`);
    }
    log('回滚安装包签名有效');
  }
}

module.exports = {
  initUpdater,
  scheduleUpdateCheck,
  checkForUpdates,
  pendingUpgrade,
  markStartupSuccess,
  rollbackAfterFailedUpgrade,
  // 供单元脚本直接验证的纯函数
  isNewer,
  parseVersion
};
