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

function waitForHealth(port, timeoutMs, child) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    let settled = false;
    child.once('exit', (code, signal) => {
      if (settled) return;
      settled = true;
      reject(new Error(`侧车进程提前退出（code=${code}, signal=${signal}），请查看 sidecar.log`));
    });

    const tick = async () => {
      if (settled) return;
      if (await pingHealth(port)) {
        settled = true;
        child.removeAllListeners('exit');
        resolve();
        return;
      }
      if (Date.now() > deadline) {
        settled = true;
        reject(new Error(`侧车在 ${Math.round(timeoutMs / 1000)} 秒内没有就绪，请查看 sidecar.log`));
        return;
      }
      setTimeout(tick, 500);
    };
    tick();
  });
}

/** 终止整棵进程树：Spring Boot 会再拉起 PostgreSQL，只杀父进程会留下孤儿 */
function stopSidecar(handle) {
  if (!handle || !handle.child || handle.child.exitCode !== null) return;
  const { pid } = handle.child;
  try {
    if (process.platform === 'win32') {
      spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true });
    } else {
      process.kill(-pid, 'SIGTERM');
      handle.child.kill('SIGTERM');
    }
  } catch (err) {
    console.error('[sidecar] 终止失败:', err.message);
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

  const handle = { child, port, url: `http://127.0.0.1:${port}/` };
  try {
    await waitForHealth(port, timeoutMs, child);
  } catch (err) {
    stopSidecar(handle);
    throw err;
  }
  return handle;
}

module.exports = { startSidecar, stopSidecar, findFreePort };
