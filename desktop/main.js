'use strict';

/**
 * Lunar Gold 桌面壳（P3-01 骨架）。
 *
 * 职责：单实例 → 拉起侧车（与日志同目录）→ 就绪后加载本地 UI → 退出时终止整棵进程树。
 * 安装包、签名、自动更新属于 P3-04，这里只保证「同一个 jar 能像桌面应用一样启动」。
 */

const { app, BrowserWindow, dialog, shell } = require('electron');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { startSidecar, stopSidecar } = require('./sidecar');
const updater = require('./updater');

const APP_TITLE = 'Lunar Gold';
const JAR_NAME = 'agent-gateway-1.0.0.jar';
const HEALTH_TIMEOUT_MS = 120000;

let mainWindow = null;
let sidecar = null;
// 主动退出期间不触发“崩溃”对话框
let quitting = false;
let runtime = null; // { jarPath, env, logPath }，供崩溃后重启复用

if (!app.requestSingleInstanceLock()) {
  // 已有实例：直接退出，由已有实例把窗口拉到前台
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!mainWindow) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  });

  app.whenReady().then(bootstrap).catch((err) => {
    fail('启动失败', err);
  });

  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', () => {
    quitting = true;
    stopSidecar(sidecar);
  });
  app.on('will-quit', () => stopSidecar(sidecar));
  process.on('exit', () => stopSidecar(sidecar));
  process.on('SIGINT', () => {
    quitting = true;
    stopSidecar(sidecar);
    app.quit();
  });
}

async function bootstrap() {
  const dataDir = process.env.DESKTOP_DATA_DIR || path.join(app.getPath('home'), '.lunargold', 'desktop');
  fs.mkdirSync(dataDir, { recursive: true });

  runtime = {
    jarPath: resolveJar(),
    dataDir,
    env: {
      ...process.env,
      AES_MASTER_KEY: process.env.AES_MASTER_KEY || readOrCreateAesKey(dataDir)
    },
    logPath: path.join(dataDir, 'sidecar.log')
  };

  mainWindow = createWindow();
  await mainWindow.loadURL(loadingPage());

  updater.initUpdater({
    getWindow: () => mainWindow,
    dataDir: runtime.dataDir,
    logPath: runtime.logPath,
    // 备份数据前停侧车：置位 sidecarRestarting 避免 onSidecarExit 误弹崩溃对话框
    stopSidecar: () => {
      sidecarRestarting = true;
      stopSidecar(sidecar);
    },
    // 用户选择「退出时安装」或备份出错时重新拉起侧车
    restartSidecar: async () => {
      try {
        await launchSidecar();
        await mainWindow.loadURL(sidecar.url);
      } finally {
        sidecarRestarting = false;
      }
    }
  });

  try {
    await launchSidecar();
    await mainWindow.loadURL(sidecar.url);
    // 升级后的首次成功启动：确认进程稳定存活几秒再清标记，
    // 避免启动后秒崩（如新版本初始化异常）却误判为升级成功
    if (updater.pendingUpgrade()) {
      await new Promise((resolve) => setTimeout(resolve, 3000));
      if (sidecar && sidecar.child && sidecar.child.exitCode === null) {
        updater.markStartupSuccess();
      }
    } else {
      updater.markStartupSuccess();
    }
    updater.scheduleUpdateCheck();
  } catch (err) {
    // 升级后启动失败才提供回滚入口；普通启动失败保持原有提示
    const marker = updater.pendingUpgrade();
    if (marker) {
      await updater.rollbackAfterFailedUpgrade(marker);
      return;
    }
    fail('本地服务没有启动成功', err, runtime.logPath);
  }
}

async function launchSidecar() {
  sidecar = await startSidecar({
    javaCmd: resolveJava(),
    jarPath: runtime.jarPath,
    env: runtime.env,
    logPath: runtime.logPath,
    timeoutMs: HEALTH_TIMEOUT_MS
  });
  sidecar.child.once('exit', onSidecarExit);
}

/** 就绪后进程意外退出：提示重启或退出，不静默留着一个死 UI */
function onSidecarExit(code, signal) {
  if (quitting || sidecarRestarting) return;
  console.error(`[sidecar] 意外退出 code=${code} signal=${signal}`);
  const choice = dialog.showMessageBoxSync(mainWindow, {
    type: 'error',
    title: APP_TITLE,
    message: '本地服务意外退出',
    detail: `code=${code} signal=${signal}\n日志：${runtime.logPath}`,
    buttons: ['重启本地服务', '退出'],
    defaultId: 0,
    cancelId: 1,
    noLink: true
  });
  if (choice === 0) {
    restartSidecar();
  } else {
    app.quit();
  }
}

let sidecarRestarting = false;
async function restartSidecar() {
  sidecarRestarting = true;
  try {
    await mainWindow.loadURL(loadingPage());
    // 崩溃后内嵌 PostgreSQL 的 postmaster 是游离进程，先按 postmaster.pid 补杀，
    // 否则重启会被判定「数据库已被其他进程占用」
    stopSidecar(sidecar);
    await new Promise((resolve) => setTimeout(resolve, 2000));
    await launchSidecar();
    await mainWindow.loadURL(sidecar.url);
  } catch (err) {
    fail('本地服务重启失败', err, runtime.logPath);
  } finally {
    sidecarRestarting = false;
  }
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 960,
    minHeight: 640,
    title: APP_TITLE,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: '#111827',
    webPreferences: {
      // 页面只跑前端 bundle，不需要 Node 能力
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true
    }
  });

  win.once('ready-to-show', () => win.show());
  // 新窗口一律交给系统浏览器，避免在应用内打开外部页面
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http://127.0.0.1') || url.startsWith('http://localhost')) {
      return { action: 'allow' };
    }
    shell.openExternal(url);
    return { action: 'deny' };
  });
  return win;
}

function loadingPage() {
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
  <title>${APP_TITLE}</title>
  <style>
    html,body{height:100%;margin:0;background:#111827;color:#e5e7eb;
      font-family:"Microsoft YaHei",system-ui,sans-serif}
    .box{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px}
    .dot{width:8px;height:8px;border-radius:50%;background:#60a5fa;animation:p 1.2s infinite ease-in-out}
    .dots{display:flex;gap:6px}
    .dots .dot:nth-child(2){animation-delay:.15s}
    .dots .dot:nth-child(3){animation-delay:.3s}
    @keyframes p{0%,80%,100%{opacity:.25;transform:translateY(0)}40%{opacity:1;transform:translateY(-4px)}}
    p{margin:0;font-size:13px;color:#9ca3af}
  </style></head>
  <body><div class="box"><div class="dots"><div class="dot"></div><div class="dot"></div><div class="dot"></div></div>
  <p>正在启动本地服务…</p></div></body></html>`;
  return 'data:text/html;charset=UTF-8,' + encodeURIComponent(html);
}

function resolveJar() {
  const candidates = [];
  if (process.env.LUNARGOLD_JAR) candidates.push(process.env.LUNARGOLD_JAR);
  if (app.isPackaged) {
    candidates.push(path.join(process.resourcesPath, 'sidecar', JAR_NAME));
    candidates.push(path.join(process.resourcesPath, 'sidecar', 'agent-gateway.jar'));
  }
  candidates.push(path.join(__dirname, '..', 'target', JAR_NAME));

  const found = candidates.find((candidate) => candidate && fs.existsSync(candidate));
  if (!found) {
    throw new Error(
      `找不到后端 jar。已尝试：\n${candidates.join('\n')}\n\n` +
        '请先执行：mvn -q "-Pwith-frontend,with-pg-runtime" -DskipTests package'
    );
  }
  return found;
}

function resolveJava() {
  // 打包态优先用随安装包分发的裁剪 JRE（P3-03，由 extraResources 复制为 resources/jre）
  if (app.isPackaged) {
    const bundled = path.join(process.resourcesPath, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(bundled)) return bundled;
  }
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const exe = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(exe)) return exe;
  }
  return process.platform === 'win32' ? 'java.exe' : 'java';
}

/** AES 主密钥必须跨启动稳定，否则已保存的模型 Key 会解不开 */
function readOrCreateAesKey(dataDir) {
  const keyFile = path.join(dataDir, 'aes.key');
  if (fs.existsSync(keyFile)) {
    return fs.readFileSync(keyFile, 'utf8').trim();
  }
  const key = crypto.randomBytes(32).toString('base64');
  fs.writeFileSync(keyFile, key, { mode: 0o600 });
  return key;
}

function fail(title, err, logPath) {
  const detail = err && err.message ? err.message : String(err);
  console.error(`[${title}] ${detail}`);
  dialog.showErrorBox(title, logPath ? `${detail}\n\n日志：${logPath}` : detail);
  stopSidecar(sidecar);
  app.quit();
}
