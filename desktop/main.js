'use strict';

/**
 * AgentKey Pro 桌面壳（P3-01 骨架）。
 *
 * 职责：单实例 → 拉起侧车（与日志同目录）→ 就绪后加载本地 UI → 退出时终止整棵进程树。
 * 安装包、签名、自动更新属于 P3-04，这里只保证「同一个 jar 能像桌面应用一样启动」。
 */

const { app, BrowserWindow, dialog, shell } = require('electron');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { startSidecar, stopSidecar } = require('./sidecar');

const APP_TITLE = 'AgentKey Pro';
const JAR_NAME = 'agent-gateway-1.0.0.jar';
const HEALTH_TIMEOUT_MS = 120000;

let mainWindow = null;
let sidecar = null;

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
  app.on('before-quit', () => stopSidecar(sidecar));
  app.on('will-quit', () => stopSidecar(sidecar));
  process.on('exit', () => stopSidecar(sidecar));
  process.on('SIGINT', () => {
    stopSidecar(sidecar);
    app.quit();
  });
}

async function bootstrap() {
  const dataDir = process.env.DESKTOP_DATA_DIR || path.join(app.getPath('home'), '.agentkey', 'desktop');
  fs.mkdirSync(dataDir, { recursive: true });

  const jarPath = resolveJar();
  const env = {
    ...process.env,
    AES_MASTER_KEY: process.env.AES_MASTER_KEY || readOrCreateAesKey(dataDir)
  };

  mainWindow = createWindow();
  await mainWindow.loadURL(loadingPage());

  try {
    sidecar = await startSidecar({
      javaCmd: resolveJava(),
      jarPath,
      env,
      logPath: path.join(dataDir, 'sidecar.log'),
      timeoutMs: HEALTH_TIMEOUT_MS
    });
    await mainWindow.loadURL(sidecar.url);
  } catch (err) {
    fail('本地服务没有启动成功', err, path.join(dataDir, 'sidecar.log'));
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
  if (process.env.AGENTKEY_JAR) candidates.push(process.env.AGENTKEY_JAR);
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
