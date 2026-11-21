/**
 * 零依赖的 OpenAI 兼容假模型，供 R7b（Run 级断点续跑）端到端脚本使用。
 *
 * 模式（POST /__mode，body {"mode":"crash"|"finish"}）：
 *   crash  第一次流式调用返回 write_file 工具调用，第二次及以后只保持连接、不返回任何 SSE 数据，
 *          用来制造「断点已落库、模型卡住」的真实崩溃窗口。
 *   finish 任何调用都直接流式返回正文「续跑完成」，finish_reason=stop。
 *
 * 每次请求都会把 method/url/body 追加写入 FAKE_MODEL_LOG（JSONL）。
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const PORT = Number(process.env.FAKE_MODEL_PORT || '18083');
const LOG_PATH = process.env.FAKE_MODEL_LOG
    || path.join(process.cwd(), 'build', 'resume-e2e', 'model-requests.jsonl');
const MODEL = process.env.FAKE_MODEL_NAME || 'deepseek-chat';
const TOOL_ARGUMENTS = JSON.stringify({ path: 'note.txt', content: 'crash-resume-e2e' });

fs.mkdirSync(path.dirname(LOG_PATH), { recursive: true });

let mode = 'crash';
let streamCallCount = 0;
const openResponses = new Set();

function logRequest(method, url, body) {
    const entry = { time: new Date().toISOString(), method, url, body };
    try {
        fs.appendFileSync(LOG_PATH, JSON.stringify(entry) + '\n', 'utf8');
    } catch (e) {
        console.error('[fake-openai] 写请求日志失败:', e.message);
    }
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function readBody(req) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        req.on('data', chunk => chunks.push(chunk));
        req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
        req.on('error', reject);
    });
}

function sendJson(res, status, payload) {
    const text = JSON.stringify(payload);
    res.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(text)
    });
    res.end(text);
}

function chunkPayload(delta, finishReason, usage) {
    const payload = {
        id: 'chatcmpl-fake-' + (++chunkPayload.seq),
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: MODEL,
        choices: [{ index: 0, delta, finish_reason: finishReason === undefined ? null : finishReason }]
    };
    if (usage) {
        payload.usage = usage;
    }
    return payload;
}
chunkPayload.seq = 0;

function writeSse(res, payload) {
    if (res.writableEnded || res.destroyed) {
        return;
    }
    res.write('data: ' + JSON.stringify(payload) + '\n\n');
}

function startSse(res) {
    res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        'Connection': 'keep-alive',
        'X-Accel-Buffering': 'no'
    });
    if (typeof res.flushHeaders === 'function') {
        res.flushHeaders();
    }
}

async function handleCompletion(body, req, res) {
    const streaming = body && body.stream === true;
    if (!streaming) {
        sendJson(res, 200, {
            id: 'chatcmpl-fake-sync',
            object: 'chat.completion',
            created: Math.floor(Date.now() / 1000),
            model: MODEL,
            choices: [{
                index: 0,
                message: { role: 'assistant', content: 'ok' },
                finish_reason: 'stop'
            }],
            usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 }
        });
        return;
    }

    streamCallCount += 1;
    const call = streamCallCount;
    startSse(res);
    req.on('close', () => openResponses.delete(res));

    if (mode === 'finish') {
        await sleep(15);
        writeSse(res, chunkPayload({ role: 'assistant' }));
        await sleep(20);
        writeSse(res, chunkPayload({ content: '续跑' }));
        await sleep(20);
        writeSse(res, chunkPayload({ content: '完成' }));
        writeSse(res, chunkPayload({}, 'stop',
            { prompt_tokens: 11, completion_tokens: 4, total_tokens: 15 }));
        res.end('data: [DONE]\n\n');
        return;
    }

    if (call === 1) {
        await sleep(20);
        writeSse(res, chunkPayload({ role: 'assistant' }));
        writeSse(res, chunkPayload({
            tool_calls: [{
                index: 0,
                id: 'call_fake_write_1',
                type: 'function',
                function: { name: 'write_file', arguments: TOOL_ARGUMENTS }
            }]
        }));
        writeSse(res, chunkPayload({}, 'tool_calls',
            { prompt_tokens: 20, completion_tokens: 10, total_tokens: 30 }));
        res.end('data: [DONE]\n\n');
        return;
    }

    // crash 模式的第二次调用：模型卡住。只保留连接，不写任何 SSE 数据。
    openResponses.add(res);
    console.log('[fake-openai] 流式调用 #' + call + ' 进入卡住状态（不返回数据）');
}

const server = http.createServer(async (req, res) => {
    let raw = '';
    try {
        raw = await readBody(req);
    } catch (e) {
        res.writeHead(400);
        res.end();
        return;
    }

    let body = null;
    if (raw) {
        try {
            body = JSON.parse(raw);
        } catch {
            body = raw;
        }
    }
    logRequest(req.method, req.url, body);

    const pathname = (req.url || '').split('?')[0];

    if (req.method === 'GET' && pathname === '/__ping') {
        sendJson(res, 200, { ok: true, mode });
        return;
    }

    if (req.method === 'POST' && pathname === '/__mode') {
        const next = body && typeof body.mode === 'string' ? body.mode : null;
        if (next !== 'crash' && next !== 'finish') {
            sendJson(res, 400, { error: 'mode 必须是 crash 或 finish' });
            return;
        }
        mode = next;
        sendJson(res, 200, { ok: true, mode });
        return;
    }

    if (req.method === 'POST' && pathname.endsWith('/chat/completions')) {
        try {
            await handleCompletion(body, req, res);
        } catch (e) {
            console.error('[fake-openai] 处理请求失败:', e);
            if (!res.headersSent) {
                sendJson(res, 500, { error: String(e && e.message || e) });
            } else {
                res.destroy();
            }
        }
        return;
    }

    sendJson(res, 404, { error: 'not found: ' + req.method + ' ' + pathname });
});

server.timeout = 0;
server.requestTimeout = 0;
server.headersTimeout = 0;

function shutdown(reason) {
    console.log('[fake-openai] 收到 ' + reason + '，关闭服务');
    for (const res of openResponses) {
        try {
            res.destroy();
        } catch {
            // ignore
        }
    }
    openResponses.clear();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 1500).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

server.listen(PORT, '127.0.0.1', () => {
    console.log('[fake-openai] 监听 http://127.0.0.1:' + PORT + '（请求日志 ' + LOG_PATH + '）');
});
