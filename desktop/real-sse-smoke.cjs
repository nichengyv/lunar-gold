'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { scanLogForFastFail, stopSidecar, withLogTail } = require('./sidecar');

const OPT_IN = 'LUNARGOLD_REAL_DESKTOP_SMOKE';
const STUB_MODEL = 'desktop-real-sse-stub';
const STUB_TEXT = 'REAL_ELECTRON_JAVA_SSE_OK';

let runRoot;
let phase = 'opt-in';
let electronApp;
let stubServer;
let electronLog;
let harnessLog;
let sidecarLog;
let dataDir;
let jarPath;
let javaPids = new Set();
let lastModelRequest = null;
let modelRequestCount = 0;

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function log(message) {
  const line = `[${new Date().toISOString()}] [${phase}] ${message}`;
  console.log(line);
  if (harnessLog) fs.appendFileSync(harnessLog, `${line}\n`);
}

async function withTimeout(promise, ms, label) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} 超时（${ms}ms）`)), ms);
      })
    ]);
  } finally {
    clearTimeout(timer);
  }
}

function assertFile(file, description) {
  assert.ok(fs.existsSync(file) && fs.statSync(file).isFile(), `${description}不存在：${file}`);
  return path.resolve(file);
}

async function startStub() {
  const server = http.createServer((req, res) => {
    if (req.method !== 'POST' || req.url.split('?')[0] !== '/v1/chat/completions') {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'local smoke stub: endpoint not found' } }));
      return;
    }
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        modelRequestCount += 1;
        lastModelRequest = {
          model: body.model,
          stream: body.stream,
          authorized: req.headers.authorization === 'Bearer sk-local-deterministic-stub'
        };
        const base = {
          id: 'chatcmpl-desktop-real-smoke',
          object: 'chat.completion.chunk',
          created: 1,
          model: STUB_MODEL
        };
        const content = {
          ...base,
          choices: [{ index: 0, delta: { role: 'assistant', content: STUB_TEXT }, finish_reason: null }]
        };
        const finish = {
          ...base,
          choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
          usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 }
        };
        res.writeHead(200, {
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive'
        });
        res.write(`data: ${JSON.stringify(content)}\n\n`);
        res.write(`data: ${JSON.stringify(finish)}\n\n`);
        res.end('data: [DONE]\n\n');
      } catch (error) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: { message: error.message } }));
      }
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return { server, origin: `http://127.0.0.1:${server.address().port}` };
}

function processSnapshot() {
  if (process.platform !== 'win32') return [];
  const result = spawnSync(
    'powershell.exe',
    [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      'Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,CommandLine | ConvertTo-Json -Compress'
    ],
    { encoding: 'utf8', windowsHide: true, maxBuffer: 16 * 1024 * 1024 }
  );
  if (result.status !== 0 || !result.stdout.trim()) return [];
  try {
    const parsed = JSON.parse(result.stdout);
    return Array.isArray(parsed) ? parsed : [parsed];
  } catch {
    return [];
  }
}

function findOwnedJavaPids(electronPid) {
  const processes = processSnapshot();
  const descendants = new Set([electronPid]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const item of processes) {
      if (descendants.has(item.ParentProcessId) && !descendants.has(item.ProcessId)) {
        descendants.add(item.ProcessId);
        changed = true;
      }
    }
  }
  const expectedJar = jarPath.toLowerCase();
  return processes
    .filter(
      (item) =>
        descendants.has(item.ProcessId) &&
        /^java(?:\.exe)?$/i.test(item.Name || '') &&
        String(item.CommandLine || '').toLowerCase().includes(expectedJar)
    )
    .map((item) => item.ProcessId);
}

function isAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function killOwnedProcessTree(pid) {
  if (!isAlive(pid)) return;
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true });
  } else {
    process.kill(pid, 'SIGTERM');
  }
}

async function waitForDesktop(page, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const url = page.url();
    if (/^http:\/\/127\.0\.0\.1:\d+\//.test(url)) return new URL(url).origin;
    if (electronApp.process().exitCode !== null) {
      throw new Error(withLogTail('Electron 在桌面服务就绪前退出', sidecarLog, 30));
    }
    const fastFail = scanLogForFastFail(sidecarLog);
    if (fastFail) {
      throw new Error(withLogTail(`真实侧车启动失败：${fastFail}`, sidecarLog, 30));
    }
    let logText = '';
    try {
      logText = fs.readFileSync(sidecarLog, 'utf8');
    } catch {
      // Java 尚未创建日志时继续等待。
    }
    if (/initdb/i.test(logText) && /postmaster\.pid|File exists/i.test(logText)) {
      throw new Error(withLogTail('内嵌 PostgreSQL initdb/postmaster.pid 启动失败', sidecarLog, 40));
    }
    if (url.startsWith('data:')) {
      const body = await page.locator('body').innerText().catch(() => '');
      if (/本地服务没有启动成功|内嵌 PostgreSQL 初始化失败|启动失败/.test(body) && !/正在启动本地服务/.test(body)) {
        throw new Error(withLogTail('桌面主进程显示启动失败诊断卡片', sidecarLog, 40));
      }
    }
    await delay(500);
  }
  throw new Error(withLogTail('等待真实 Electron 页面加载 Java 后端超时', sidecarLog, 40));
}

async function pageJson(page, url, options = {}) {
  return page.evaluate(
    async ({ url, options }) => {
      const response = await fetch(url, options);
      const body = await response.json().catch(() => null);
      return { status: response.status, body };
    },
    { url, options }
  );
}

async function readChatSse(page, token, projectId) {
  return page.evaluate(
    async ({ token, projectId }) => {
      const response = await fetch('/api/agent/chat/stream', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prompt: 'Reply with the deterministic smoke token only.',
          projectId,
          model: 'desktop-real-sse-stub',
          reasoning: 'STANDARD',
          permission: 'AUTO',
          useRag: false,
          clientRequestId: `desktop-real-smoke-${Date.now()}`
        })
      });
      const raw = await response.text();
      const events = [];
      for (const frame of raw.split(/\r?\n\r?\n/)) {
        let type = 'message';
        const data = [];
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith('event:')) type = line.slice(6).trim();
          if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
        }
        if (data.length) events.push({ type, data: data.join('\n') });
      }
      return {
        status: response.status,
        contentType: response.headers.get('content-type') || '',
        events,
        raw: raw.slice(0, 20000)
      };
    },
    { token, projectId }
  );
}

async function cleanup() {
  const errors = [];
  if (electronApp) {
    try {
      for (const pid of findOwnedJavaPids(electronApp.process().pid)) javaPids.add(pid);
      await withTimeout(electronApp.close(), 15000, 'Electron 正常退出');
    } catch (error) {
      errors.push(`Electron 正常退出失败：${error.message}`);
      killOwnedProcessTree(electronApp.process().pid);
    }
  }
  for (const pid of javaPids) {
    if (isAlive(pid)) {
      try {
        stopSidecar({ child: { pid, exitCode: null }, dataDir });
      } catch (error) {
        errors.push(`Java 清理失败 pid=${pid}：${error.message}`);
      }
    }
  }
  try {
    // dataDir 是本次唯一目录；同时按其中的 postmaster.pid 清理可能脱离 Java 的 PG。
    stopSidecar({ dataDir });
  } catch (error) {
    errors.push(`PostgreSQL 清理失败：${error.message}`);
  }
  if (stubServer) {
    try {
      const closed = new Promise((resolve) => stubServer.close(resolve));
      stubServer.closeAllConnections();
      await withTimeout(closed, 5000, '本地模型强桩关闭');
    } catch (error) {
      errors.push(`模型强桩清理失败：${error.message}`);
    }
  }
  if (electronLog) electronLog.end();
  return errors;
}

async function main() {
  if (process.env[OPT_IN] !== '1') {
    console.error(`真实桌面冒烟是显式 opt-in：请先设置 ${OPT_IN}=1，再运行 npm run smoke:real-sse。`);
    process.exitCode = 1;
    return;
  }

  const repoRoot = path.resolve(__dirname, '..');
  runRoot = path.join(
    repoRoot,
    'target',
    'real-desktop-sse',
    `${new Date().toISOString().replace(/[:.]/g, '-')}-${process.pid}-${crypto.randomUUID()}`
  );
  dataDir = path.join(runRoot, 'desktop-data');
  sidecarLog = path.join(dataDir, 'sidecar.log');
  harnessLog = path.join(runRoot, 'harness.log');
  const dirs = {
    home: path.join(runRoot, 'home'),
    appData: path.join(runRoot, 'appdata'),
    localAppData: path.join(runRoot, 'local-appdata'),
    electronData: path.join(runRoot, 'electron-user-data'),
    workspace: path.join(runRoot, 'workspace'),
    tmp: path.join(runRoot, 'tmp'),
    toolResults: path.join(runRoot, 'tool-results')
  };
  fs.mkdirSync(dataDir, { recursive: true });
  for (const dir of Object.values(dirs)) fs.mkdirSync(dir, { recursive: true });

  const result = { status: 'failed', phase, runRoot, sidecarLog };
  let failure = null;
  try {
    phase = 'preflight';
    log('开始真实 Electron + Java SSE 冒烟；不使用 page.route');
    jarPath = assertFile(
      process.env.LUNARGOLD_JAR || path.join(repoRoot, 'target', 'agent-gateway-1.0.0.jar'),
      '后端 jar'
    );
    const pgRuntime = assertFile(
      process.env.DESKTOP_PG_RUNTIME || path.join(repoRoot, 'build', 'pg-runtime', 'pg-runtime.txz'),
      '含 pgvector 的 PostgreSQL 运行时'
    );
    const electronPath = assertFile(require('electron'), 'Electron 可执行文件');
    const { _electron } = require('../frontend/node_modules/playwright');
    assert.ok(_electron, 'frontend 的 Playwright 未提供 Electron 支持');

    phase = 'stub-start';
    const stub = await startStub();
    stubServer = stub.server;
    log(`确定性强桩已监听 ${stub.origin}`);

    const env = {
      ...process.env,
      DESKTOP_DATA_DIR: dataDir,
      DESKTOP_PG_RUNTIME: pgRuntime,
      DESKTOP_DB_PORT: '0',
      DESKTOP_DEV_TOKEN: '',
      DESKTOP_LOG_TOKEN: 'false',
      DESKTOP_EXPOSE_TOKEN_ENDPOINT: 'true',
      LUNARGOLD_JAR: jarPath,
      LUNARGOLD_DISABLE_UPDATE: '1',
      LUNARGOLD_UPDATE_FEED: '',
      AES_MASTER_KEY: crypto.randomBytes(32).toString('base64'),
      JWT_SECRET: crypto.randomBytes(48).toString('base64'),
      AGENT_SPRING_AI_BASE_URL: stub.origin,
      AGENT_SPRING_AI_COMPLETIONS_PATH: '/v1/chat/completions',
      AGENT_MODEL_MAX_ATTEMPTS: '1',
      AGENT_MODEL_FALLBACKS: '',
      AGENT_MODEL_IDLE_TIMEOUT_SECONDS: '15',
      AGENT_BUDGET_TOOL_SPILL_DIR: dirs.toolResults,
      SPRING_AI_OPENAI_API_KEY: 'local-smoke-only',
      LLM_CHAT_MODEL: STUB_MODEL,
      LLM_CHAT_ALLOWED_MODELS: STUB_MODEL,
      DEEPSEEK_MODEL: STUB_MODEL,
      DEEPSEEK_ALLOWED_MODELS: STUB_MODEL,
      DEEPSEEK_VISION_ENABLED: 'false',
      CONTEXT_SUMMARY_ENABLED: 'false',
      EMBEDDING_API_URL: `${stub.origin}/v1/embeddings`,
      EMBEDDING_API_KEY: '',
      TAVILY_API_KEY: '',
      MCP_ENABLED: 'false',
      WORKSPACE_COMMAND_ENABLED: 'false',
      WORKSPACE_ROOT: dirs.workspace,
      WORKSPACE_PATH: dirs.workspace,
      WORKSPACE_DIR: dirs.workspace,
      HOME: dirs.home,
      USERPROFILE: dirs.home,
      APPDATA: dirs.appData,
      LOCALAPPDATA: dirs.localAppData,
      XDG_CONFIG_HOME: dirs.appData,
      XDG_CACHE_HOME: dirs.localAppData,
      XDG_DATA_HOME: dirs.localAppData,
      TEMP: dirs.tmp,
      TMP: dirs.tmp,
      JAVA_TOOL_OPTIONS: `-Duser.home="${dirs.home}" -Duser.dir="${runRoot}" -Djava.io.tmpdir="${dirs.tmp}"`,
      NO_PROXY: '127.0.0.1,localhost',
      no_proxy: '127.0.0.1,localhost',
      HTTP_PROXY: '',
      HTTPS_PROXY: '',
      ALL_PROXY: '',
      ELECTRON_ENABLE_LOGGING: '1'
    };
    // 不继承 IDE 对宿主 Electron/Chromium 的注入；冒烟只启动本仓库的 Electron。
    for (const key of Object.keys(env)) {
      if (/^(ELECTRON_|AHA_|ICUBE_|VSCODE_)/.test(key)) delete env[key];
    }
    env.ELECTRON_ENABLE_LOGGING = '1';

    phase = 'electron-launch';
    electronLog = fs.createWriteStream(path.join(runRoot, 'electron.log'), { flags: 'a' });
    electronApp = await _electron.launch({
      executablePath: electronPath,
      // 当前 Windows  runner 的 Chromium 沙箱会让 Electron 以 0x80000003 提前退出。
      args: ['--no-sandbox', path.join(__dirname), `--user-data-dir=${dirs.electronData}`],
      cwd: runRoot,
      env,
      timeout: 30000
    });
    electronApp.process().stdout?.pipe(electronLog);
    electronApp.process().stderr?.pipe(electronLog);
    electronApp.on('console', (message) => log(`[electron-main] ${message.text()}`));
    const page = await electronApp.firstWindow({ timeout: 30000 });
    const browserWindow = await electronApp.browserWindow(page);
    result.electronWindow = await browserWindow.evaluate((win) => ({ id: win.id, visible: win.isVisible() }));
    result.electronPid = electronApp.process().pid;
    log(`真实 Electron 窗口已创建，主进程 pid=${result.electronPid}`);

    phase = 'desktop-startup';
    result.origin = await waitForDesktop(page, 150000);
    javaPids = new Set(findOwnedJavaPids(result.electronPid));
    assert.ok(javaPids.size > 0, '未找到由本次 Electron 启动且命令行指向隔离 jar 的真实 Java 子进程');
    result.javaPids = [...javaPids];
    log(`真实 Java 后端已就绪：${result.origin}，pid=${result.javaPids.join(',')}`);

    phase = 'meta';
    const meta = await withTimeout(pageJson(page, '/api/meta'), 15000, 'GET /api/meta');
    assert.equal(meta.status, 200, `GET /api/meta 返回 ${meta.status}`);
    assert.equal(meta.body.mode, 'DESKTOP');
    assert.equal(meta.body.authMode, 'LOCAL_SINGLE_USER');
    result.meta = { mode: meta.body.mode, authMode: meta.body.authMode, version: meta.body.version };

    phase = 'desktop-token';
    const tokenResponse = await withTimeout(pageJson(page, '/api/desktop/token'), 15000, 'GET /api/desktop/token');
    assert.equal(tokenResponse.status, 200, `GET /api/desktop/token 返回 ${tokenResponse.status}`);
    const token = tokenResponse.body && tokenResponse.body.data && tokenResponse.body.data.token;
    assert.ok(typeof token === 'string' && token.length > 0, '桌面令牌响应缺少 data.token');
    result.desktopToken = { obtained: true, length: token.length };

    phase = 'api-key';
    const savedKey = await withTimeout(
      pageJson(page, '/api/keys', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider: 'DEEPSEEK', apiKey: 'sk-local-deterministic-stub', alias: 'local-smoke' })
      }),
      15000,
      'POST /api/keys'
    );
    assert.equal(savedKey.status, 200, `POST /api/keys 返回 ${savedKey.status}`);
    assert.equal(savedKey.body.code, 200, JSON.stringify(savedKey.body));

    phase = 'workspace';
    const projects = await withTimeout(
      pageJson(page, '/api/projects', { headers: { Authorization: `Bearer ${token}` } }),
      15000,
      'GET /api/projects'
    );
    const project = projects.body && projects.body.data && projects.body.data[0];
    assert.ok(project && project.id, '无法创建/读取本次隔离默认工作区');
    const bound = await withTimeout(
      pageJson(page, `/api/projects/${project.id}/workspace/set-path`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: dirs.workspace })
      }),
      15000,
      '绑定隔离工作区'
    );
    assert.equal(bound.status, 200, `绑定隔离工作区返回 ${bound.status}`);
    result.workspace = dirs.workspace;

    phase = 'chat-sse';
    const stream = await withTimeout(readChatSse(page, token, project.id), 60000, 'POST /api/agent/chat/stream');
    assert.equal(stream.status, 200, `POST /api/agent/chat/stream 返回 ${stream.status}：${stream.raw}`);
    assert.match(stream.contentType, /text\/event-stream/i);
    const eventTypes = stream.events.map((event) => event.type);
    for (const required of ['session_id', 'run_id', 'turn_id', 'content', 'done']) {
      assert.ok(eventTypes.includes(required), `SSE 缺少 ${required} 事件，实际：${eventTypes.join(',')}；${stream.raw}`);
    }
    assert.ok(!eventTypes.includes('error'), `SSE 返回 error 事件：${stream.raw}`);
    assert.ok(
      stream.events.some((event) => event.type === 'content' && event.data.includes(STUB_TEXT)),
      `SSE content 缺少确定性正文：${stream.raw}`
    );
    assert.equal(modelRequestCount, 1, '本地模型强桩调用次数应为 1');
    assert.deepEqual(lastModelRequest, { model: STUB_MODEL, stream: true, authorized: true });
    result.sse = { eventTypes, deterministicText: STUB_TEXT, modelRequests: modelRequestCount };
    result.status = 'passed';
    result.phase = 'passed';
    log(`冒烟通过：SSE 事件 ${eventTypes.join(' → ')}`);
  } catch (error) {
    failure = error;
    result.phase = phase;
    result.error = error.stack || error.message || String(error);
  } finally {
    const cleanupErrors = await cleanup();
    if (cleanupErrors.length) {
      result.cleanupErrors = cleanupErrors;
      if (!failure) {
        failure = new Error(cleanupErrors.join('\n'));
        result.status = 'failed';
        result.phase = 'cleanup';
      }
    }
    result.logs = {
      harness: harnessLog,
      electron: path.join(runRoot, 'electron.log'),
      sidecar: sidecarLog
    };
    fs.writeFileSync(path.join(runRoot, 'result.json'), JSON.stringify(result, null, 2));
  }

  if (failure) {
    console.error(`[REAL_SMOKE_FAILED] phase=${result.phase}\n${failure.message}`);
    console.error(`运行目录：${runRoot}\n侧车日志：${sidecarLog}\n结果：${path.join(runRoot, 'result.json')}`);
    process.exitCode = 1;
    return;
  }
  console.log(`[REAL_SMOKE_PASSED] 运行目录：${runRoot}\n结果：${path.join(runRoot, 'result.json')}`);
}

main().catch((error) => {
  console.error(`[REAL_SMOKE_FAILED] phase=${phase}: ${error.stack || error.message}`);
  if (runRoot) console.error(`运行目录：${runRoot}\n侧车日志：${sidecarLog}`);
  process.exitCode = 1;
});
