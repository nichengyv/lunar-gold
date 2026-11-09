'use strict';

/**
 * 侧车（Spring Boot jar）生命周期：选空闲端口、拉起进程、等健康检查、整体终止。
 *
 * 这里刻意不依赖 Electron，方便用 node 直接跑脚本验证；
 * main.js 只负责窗口与错误提示。
 */

const { spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');

/** 只在本机回环上选一个空闲端口，避免和外部的 8080 冲突 */
function findFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

function pingHealth(port) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: '/actuator/health', timeout: 2000 },
      (res) => {
        res.resume();
        // DOWN 时 Spring Boot 返回 503，因此只认 200
        resolve(res.statusCode === 200);
      }
    );
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
    req.on('error', () => resolve(false));
  });
}

function waitForHealth(port, timeoutMs, child, logPath) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    let settled = false;
    const fail = (err) => {
      if (settled) return;
      settled = true;
      reject(err);
    };
    child.once('exit', (code, signal) => {
      fail(new Error(withLogTail(`侧车进程提前退出（code=${code}, signal=${signal}）`, logPath)));
    });

    const tick = async () => {
      if (settled) return;
      if (await pingHealth(port)) {
        settled = true;
        child.removeAllListeners('exit');
        resolve();
        return;
      }
      // 日志里出现典型启动失败时立刻失败，不必傻等超时
      const fastFail = scanLogForFastFail(logPath);
      if (fastFail) {
        fail(new Error(withLogTail(`侧车启动失败：${fastFail}`, logPath)));
        return;
      }
      if (Date.now() > deadline) {
        fail(new Error(withLogTail(`侧车在 ${Math.round(timeoutMs / 1000)} 秒内没有就绪`, logPath)));
        return;
      }
      setTimeout(tick, 500);
    };
    tick();
  });
}

/** 日志里一旦出现这些模式，再等下去也不会好起来（端口占用、配置错误、迁移失败） */
const FAST_FAIL_PATTERNS = [
  /APPLICATION FAILED TO START/i,
  /Web server failed to start/i,
  /already in use/i,
  /Port \d+ was already in use/i,
  /Unable to access jarfile/i,
  /DESKTOP_PG_RUNTIME 指向的文件不存在/
];

function scanLogForFastFail(logPath) {
  let text;
  try {
    text = fs.readFileSync(logPath, 'utf8');
  } catch {
    return null;
  }
  for (const pattern of FAST_FAIL_PATTERNS) {
    const match = text.match(pattern);
    if (match) return match[0];
  }
  return null;
}

/** 附日志末尾几行，让错误对话框不用打开文件也能看出原因 */
function withLogTail(message, logPath, maxLines = 6) {
  try {
    const lines = fs.readFileSync(logPath, 'utf8').trim().split(/\r?\n/);
    const tail = lines.slice(-maxLines).join('\n');
    return `${message}\n---- sidecar.log 末尾 ----\n${tail}`;
  } catch {
    return message;
  }
}

/**
 * 终止整棵进程树。注意：内嵌 PostgreSQL 的 postmaster 是 pg_ctl 分离拉起的，
 * 不在 java 的进程树里，必须按数据目录里的 postmaster.pid 单独终止，
 * 否则下次启动会被判定「数据库已被其他进程占用」。
 */
function stopSidecar(handle) {
  if (!handle) return;
  if (handle.child && handle.child.exitCode === null) {
    killTree(handle.child.pid);
  }
  const pid = readPostmasterPid(handle.dataDir);
  if (pid && isAlive(pid)) {
    killTree(pid);
  }
}

function killTree(pid) {
  try {
    if (process.platform === 'win32') {
      spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true });
    } else {
      process.kill(pid, 'SIGTERM');
    }
  } catch (err) {
    console.error('[sidecar] 终止失败 pid=%s: %s', pid, err.message);
  }
}

/** postmaster.pid 第一行是 pid；读不到视为没有需要终止的数据库 */
function readPostmasterPid(dataDir) {
  if (!dataDir) return null;
  try {
    const firstLine = fs.readFileSync(path.join(dataDir, 'db', 'postmaster.pid'), 'utf8').split(/\r?\n/, 1)[0];
    const pid = Number.parseInt(firstLine, 10);
    return Number.isInteger(pid) && pid > 0 ? pid : null;
  } catch {
    return null;
  }
}

function isAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/**
 * @param {object} options
 * @param {string} options.javaCmd   java 可执行文件
 * @param {string} options.jarPath   后端 jar
 * @param {NodeJS.ProcessEnv} options.env  传给侧车的环境变量
 * @param {string} options.logPath   侧车日志（追加写）
 * @param {number} [options.timeoutMs]
 * @returns {Promise<{child: import('node:child_process').ChildProcess, port: number, url: string}>}
 */
async function startSidecar({ javaCmd, jarPath, env, logPath, timeoutMs = 120000 }) {
  const port = await findFreePort();
  fs.mkdirSync(path.dirname(logPath), { recursive: true });
  const log = fs.createWriteStream(logPath, { flags: 'a' });
  log.write(`\n===== ${new Date().toISOString()} 启动侧车 port=${port} jar=${jarPath} =====\n`);

  const child = spawn(
    javaCmd,
    [
      '-jar',
      jarPath,
      '--spring.profiles.active=desktop',
      `--server.port=${port}`
    ],
    {
      env,
      windowsHide: true,
      // 独立进程组，便于 POSIX 下整组终止
      detached: process.platform !== 'win32',
      stdio: ['ignore', 'pipe', 'pipe']
    }
  );

  child.stdout.pipe(log);
  child.stderr.pipe(log);

  // 与后端 app.desktop.data-dir 的默认值保持一致，用于按 postmaster.pid 终止内嵌数据库
  const dataDir = (env && env.DESKTOP_DATA_DIR) || path.join(os.homedir(), '.lunargold', 'desktop');
  const handle = { child, port, url: `http://127.0.0.1:${port}/`, dataDir };
  try {
    await waitForHealth(port, timeoutMs, child, logPath);
  } catch (err) {
    stopSidecar(handle);
    throw err;
  }
  return handle;
}

module.exports = { startSidecar, stopSidecar, findFreePort, scanLogForFastFail, withLogTail };
