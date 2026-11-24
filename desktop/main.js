'use strict';

/**
 * Lunar Gold 桌面壳：单实例 → 拉起侧车 → 就绪后加载本地 UI → 退出时终止整棵进程树。
 * P3-06：主进程、渲染进程、Java 与内嵌 PostgreSQL 故障统一进入本地诊断卡片。
 */

const { app, BrowserWindow, dialog, shell, ipcMain } = require('electron');
// Windows 下 Electron 43 默认 sandbox 与本机安全策略冲突（child process spawn 被拦），
// 统一关主进程 sandbox。渲染进程 sandbox 由 BrowserWindow 配置控制。
app.commandLine.appendSwitch('no-sandbox');
app.commandLine.appendSwitch('disable-gpu');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { startSidecar, stopSidecar } = require('./sidecar');
const diagnostics = require('./diagnostics');
const updater = require('./updater');

const APP_TITLE = 'Lunar Gold';
const JAR_NAME = 'agent-gateway-1.0.0.jar';
const HEALTH_TIMEOUT_MS = 120000;

// 开发态开关：跳过 sidecar 拉起，加载 vite dev server（需要手动启动后端 + 前端）
const DEV_MODE = process.env.LUNARGOLD_DEV === '1';
const DEV_FRONTEND_URL = process.env.LUNARGOLD_DEV_FRONTEND || 'http://localhost:5173';

let mainWindow = null;
let sidecar = null;
// 主动退出期间不触发“崩溃”提示
let quitting = false;
let sidecarRestarting = false;
let starting = false;
let runtime = null; // { jarPath, dataDir, env, logPath }，供崩溃后重启复用

// ---- preload IPC：窗口状态查询 ----
ipcMain.handle('win:is-maximized', () => {
  if (!mainWindow || mainWindow.isDestroyed()) return false;
  return mainWindow.isMaximized();
});

if (!app.requestSingleInstanceLock()) {
  // 已有实例：直接退出，由已有实例把窗口拉到前台
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  });

  app.whenReady().then(bootstrap).catch((err) => {
    fail('启动失败', err);
  });

  app.on('window-all-closed', () => {
    // macOS 惯例：关窗口 App 仍在 Dock 里；Windows 开发态也留着方便调试渲染进程崩溃
    if (process.platform !== 'darwin' && !DEV_MODE) app.quit();
  });
  app.on('before-quit', () => {
    quitting = true;
    stopSidecar(sidecar);
    diagnostics.markCleanExit();
  });
  app.on('will-quit', () => stopSidecar(sidecar));
  process.on('exit', () => stopSidecar(sidecar));
  process.on('SIGINT', () => {
    quitting = true;
    stopSidecar(sidecar);
    app.quit();
  });
  process.on('uncaughtException', (err) => handleMainFailure('uncaughtException', err));
  process.on('unhandledRejection', (err) => handleMainFailure('unhandledRejection', err));
}

async function bootstrap() {
  const dataDir = process.env.DESKTOP_DATA_DIR || path.join(app.getPath('home'), '.lunargold', 'desktop');
  fs.mkdirSync(dataDir, { recursive: true });

  const nodeHome = resolveNodeHome();
  runtime = {
    jarPath: null,
    dataDir,
    env: {
      ...process.env,
      AES_MASTER_KEY: process.env.AES_MASTER_KEY || readOrCreateAesKey(dataDir),
      ...(nodeHome ? { LUNAR_MCP_NODE_HOME: nodeHome } : {}),
      LUNAR_MCP_NPM_CACHE: path.join(dataDir, 'mcp-npm-cache')
    },
    logPath: path.join(dataDir, 'sidecar.log')
  };

  diagnostics.init({
    electron: { app, dialog },
    dataDir,
    getWindow: () => mainWindow
  });
  const previousSession = diagnostics.beginSession();
  runtime.jarPath = resolveJar();

  ensureWindow();
  await mainWindow.loadURL(loadingPage());

  updater.initUpdater({
    getWindow: () => mainWindow,
    dataDir: runtime.dataDir,
    logPath: runtime.logPath,
    // 备份数据前停侧车：置位 sidecarRestarting 避免 onSidecarExit 误弹崩溃卡片
    stopSidecar: () => {
      sidecarRestarting = true;
      stopSidecar(sidecar);
    },
    // 用户选择「退出时安装」或备份出错时重新拉起侧车
    restartSidecar: async () => {
      try {
        await launchSidecar();
        await mainWindow.loadURL(mainWindowUrl());
      } finally {
        sidecarRestarting = false;
      }
    }
  });

  if (previousSession) {
    diagnostics.report(
      {
        source: 'electron-main',
        code: 'ELECTRON_MAIN_PREVIOUS_EXIT',
        title: 'Lunar Gold 上次没有正常退出',
        summary: '检测到上次主进程异常结束。主进程自身崩溃时无法当场弹出卡片，因此保留现场并在本次启动补充提示。',
        details: previousSession.corrupt
          ? '上次会话标记损坏，未能读取更多进程信息。'
          : `上次启动时间：${previousSession.startedAt || '未知'}\n上次进程 PID：${previousSession.pid || '未知'}\n上次版本：${previousSession.appVersion || '未知'}`
      },
      { retryLabel: '继续启动', onRetry: startLocalServices }
    );
    return;
  }

  await startLocalServices();
}

async function startLocalServices() {
  if (starting) return;
  starting = true;
  try {
    ensureWindow();
    await mainWindow.loadURL(loadingPage());
    if (DEV_MODE) {
      // 开发态：sidecar 由外部手动启动（Spring Boot 固定端口 + 内嵌 PG），只加载前端
      console.log('[dev] 跳过 sidecar 拉起，直接加载 vite dev server');
    } else {
      await launchSidecar();
    }
    await mainWindow.loadURL(mainWindowUrl());
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
    // 升级后启动失败才提供回滚入口；普通启动失败进入诊断卡片
    const marker = updater.pendingUpgrade();
    if (marker) {
      await updater.rollbackAfterFailedUpgrade(marker);
      return;
    }
    fail('本地服务没有启动成功', err, runtime.logPath);
  } finally {
    starting = false;
  }
}

async function launchSidecar() {
  if (!runtime.jarPath) runtime.jarPath = resolveJar();
  sidecar = await startSidecar({
    javaCmd: resolveJava(),
    jarPath: runtime.jarPath,
    env: runtime.env,
    logPath: runtime.logPath,
    timeoutMs: HEALTH_TIMEOUT_MS
  });
  sidecar.child.once('exit', onSidecarExit);
}

/** 就绪后进程意外退出：保留日志并提供重启、导出与退出，不静默留下死 UI。 */
function onSidecarExit(code, signal) {
  if (quitting || sidecarRestarting) return;
  const logTail = diagnostics.readLogExcerpt(runtime.logPath);
  const details = `侧车退出码：${code}\n退出信号：${signal}\n${logTail}`;
  const errorCode = diagnostics.classifyJavaError(details);
  console.error(`[sidecar] 意外退出 code=${code} signal=${signal}`);
  stopSidecar(sidecar);
  diagnostics.report(
    {
      source: errorCode === 'PG_INIT_FAILED' ? 'postgresql' : 'java',
      code: errorCode === 'JAVA_START_FAILED' ? 'JAVA_EXITED' : errorCode,
      title: errorCode === 'PG_INIT_FAILED' ? '内嵌数据库意外停止' : '本地服务意外退出',
      summary: `Java 侧车进程已退出（code=${code}，signal=${signal}）。可以重启本地服务，或先导出诊断包。`,
      details
    },
    { retryLabel: '重启本地服务', onRetry: restartSidecar }
  );
}

async function restartSidecar() {
  if (sidecarRestarting) return;
  sidecarRestarting = true;
  try {
    ensureWindow();
    await mainWindow.loadURL(loadingPage());
    // 崩溃后内嵌 PostgreSQL 的 postmaster 是游离进程，先按 postmaster.pid 补杀，
    // 否则重启会被判定「数据库已被其他进程占用」
    stopSidecar(sidecar);
    await new Promise((resolve) => setTimeout(resolve, 2000));
    await launchSidecar();
    await mainWindow.loadURL(mainWindowUrl());
  } catch (err) {
    fail('本地服务重启失败', err, runtime.logPath);
  } finally {
    sidecarRestarting = false;
  }
}

async function recoverRenderer() {
  ensureWindow();
  if (sidecar && sidecar.child && sidecar.child.exitCode === null) {
    try {
      await mainWindow.loadURL(mainWindowUrl());
    } catch (err) {
      showRendererFailure('ELECTRON_RENDERER_LOAD_FAILED', '界面重新加载失败', err);
    }
  } else {
    await restartSidecar();
  }
}

function showRendererFailure(code, title, err) {
  if (quitting || sidecarRestarting || !mainWindow || mainWindow.isDestroyed()) return;
  if (diagnostics.isShowing(mainWindow.webContents)) return;
  const details = err && err.stack ? err.stack : err && err.message ? err.message : String(err || '无更多详情');
  diagnostics.report(
    {
      source: 'electron-renderer',
      code,
      title,
      summary: '应用界面出现异常，但本地服务可能仍在运行。可以重新加载界面，或导出诊断包后继续排查。',
      details
    },
    { retryLabel: '重新加载界面', onRetry: recoverRenderer }
  );
}

function ensureWindow() {
  if (mainWindow && !mainWindow.isDestroyed()) return;
  mainWindow = createWindow();
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
    backgroundColor: '#101012',
    // 开发态先用原生标题栏，打包再开 titleBarStyle:'hidden'
    // 避免开发调试时 preload / titleBarOverlay 问题阻塞
    ...(DEV_MODE ? {} : { titleBarStyle: 'hidden', titleBarOverlay: { color: '#101012', symbolColor: '#e6e6e8', height: 36 } }),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: DEV_MODE ? false : true,
      preload: path.join(__dirname, 'preload.js')
    }
  });

  win.once('ready-to-show', () => win.show());
  // titleBarOverlay 最大化状态广播：让前端更新按钮图标
  win.on('maximize', () => win.webContents.send('win:maximize-changed', true));
  win.on('unmaximize', () => win.webContents.send('win:maximize-changed', false));
  // 新窗口一律交给系统浏览器，避免在应用内打开外部页面
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http://127.0.0.1') || url.startsWith('http://localhost')) {
      return { action: 'allow' };
    }
    shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('render-process-gone', (_event, details) => {
    showRendererFailure(
      'ELECTRON_RENDERER_CRASH',
      '应用界面意外停止',
      `渲染进程退出原因：${details.reason}\n退出码：${details.exitCode}`
    );
  });
  win.webContents.on('unresponsive', () => {
    showRendererFailure('ELECTRON_RENDERER_UNRESPONSIVE', '应用界面没有响应', '渲染进程持续无响应。');
  });
  win.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedURL, isMainFrame) => {
    // -3 是主动切换页面产生的 ERR_ABORTED，不是故障
    if (!isMainFrame || errorCode === -3 || quitting || sidecarRestarting) return;
    showRendererFailure(
      'ELECTRON_RENDERER_LOAD_FAILED',
      '应用界面加载失败',
      `Chromium 错误码：${errorCode}\n说明：${errorDescription}\n地址：${validatedURL}`
    );
  });
  return win;
}

function mainWindowUrl() {
  if (DEV_MODE) return DEV_FRONTEND_URL;
  const target = new URL(sidecar.url);
  target.searchParams.set('appVersion', app.getVersion());
  return target.toString();
}

function loadingPage() {
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
  <title>${APP_TITLE}</title>
  <style>
    html,body{height:100%;margin:0;background:#101012;color:#e6e6e8;
      font-family:"Microsoft YaHei",system-ui,sans-serif}
    .box{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px}
    .dot{width:8px;height:8px;border-radius:50%;background:#58a6ff;animation:p 1.2s infinite ease-in-out}
    .dots{display:flex;gap:6px}
    .dots .dot:nth-child(2){animation-delay:.15s}
    .dots .dot:nth-child(3){animation-delay:.3s}
    @keyframes p{0%,80%,100%{opacity:.25;transform:translateY(0)}40%{opacity:1;transform:translateY(-4px)}}
    p{margin:0;font-size:13px;color:#9a9aa0}
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

/** 内置 Node 运行时目录：打包态 resources/node-runtime，开发态 build/node-runtime */
function resolveNodeHome() {
  const candidates = [];
  if (process.env.LUNAR_MCP_NODE_HOME) candidates.push(process.env.LUNAR_MCP_NODE_HOME);
  if (app.isPackaged) candidates.push(path.join(process.resourcesPath, 'node-runtime'));
  candidates.push(path.join(__dirname, '..', 'build', 'node-runtime'));
  return candidates.find((dir) => dir && (
    fs.existsSync(path.join(dir, 'node.exe')) || fs.existsSync(path.join(dir, 'bin', 'node'))
  )) || null;
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

function handleMainFailure(kind, err) {
  console.error(`[electron-main:${kind}]`, err);
  if (!diagnostics.isReady()) {
    dialog.showErrorBox('Lunar Gold 主进程异常', err && err.message ? err.message : String(err));
    app.quit();
    return;
  }
  ensureWindow();
  diagnostics.report(
    {
      source: 'electron-main',
      code: 'ELECTRON_MAIN_EXCEPTION',
      title: 'Lunar Gold 主进程出现异常',
      summary: `捕获到 ${kind}。建议保存诊断包后重启应用，避免在异常状态下继续操作。`,
      details: err && err.stack ? err.stack : String(err)
    },
    { retryLabel: '重启应用', onRetry: relaunchApp }
  );
}

function relaunchApp() {
  app.relaunch();
  app.quit();
}

function fail(title, err, logPath) {
  const detail = err && err.stack ? err.stack : err && err.message ? err.message : String(err);
  console.error(`[${title}] ${detail}`);
  if (!diagnostics.isReady()) {
    dialog.showErrorBox(title, logPath ? `${detail}\n\n日志：${logPath}` : detail);
    stopSidecar(sidecar);
    app.quit();
    return;
  }
  ensureWindow();
  stopSidecar(sidecar);
  // Java 根因常在数百行 Spring 堆栈的底部，不能只拿 withLogTail 的最后 6 行分类。
  const logTail = logPath
    ? diagnostics.readLogExcerpt(logPath, { maxBytes: 512 * 1024, maxLines: 200, maxChars: 200000 })
    : '';
  const fullDetail = logTail ? `${detail}\n---- sidecar.log 分类与诊断末尾 ----\n${logTail}` : detail;
  const code = logPath ? diagnostics.classifyJavaError(fullDetail) : 'ELECTRON_MAIN_START_FAILED';
  const friendlyTitle =
    code === 'PG_INIT_FAILED' ? '内嵌 PostgreSQL 初始化失败' : code === 'PORT_UNAVAILABLE' ? '本地服务端口不可用' : title;
  diagnostics.report(
    {
      source: code === 'PG_INIT_FAILED' ? 'postgresql' : logPath ? 'java' : 'electron-main',
      code,
      title: friendlyTitle,
      summary: `${title}。${err && err.message ? err.message.split('\n')[0] : String(err)}`,
      details: fullDetail
    },
    { retryLabel: '重试启动', onRetry: startLocalServices }
  );
}
