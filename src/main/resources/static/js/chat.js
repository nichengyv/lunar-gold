import { api } from './api.js';
import * as ui from './ui.js';
import { escapeHtml, copyToClipboard } from './utils.js';

export async function parseSSE(body, callbacks) {
    const reader = body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let eventType = null;
    let eventData = '';

    while (true) {
        const { done, value } = await reader.read();
        if (done) {
            buffer += decoder.decode();
            if (buffer.trim()) {
                const lines = buffer.split(/\r?\n/);
                for (const line of lines) {
                    const trimmed = line.trim();
                    if (trimmed.startsWith('event:')) eventType = trimmed.slice(6).trim();
                    else if (trimmed.startsWith('data:')) eventData += (eventData ? '\n' : '') + trimmed.slice(5).trimStart();
                    else if (trimmed === '' && eventType) {
                        callbacks.onEvent?.(eventType, eventData);
                        eventType = null; eventData = '';
                    }
                }
                if (eventType) {
                    callbacks.onEvent?.(eventType, eventData);
                }
            }
            break;
        }

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split(/\r?\n/);
        buffer = lines.pop() || '';

        for (const line of lines) {
            const trimmed = line.trim();
            if (trimmed.startsWith('event:')) {
                eventType = trimmed.slice(6).trim();
            } else if (trimmed.startsWith('data:')) {
                eventData += (eventData ? '\n' : '') + trimmed.slice(5).trimStart();
            } else if (trimmed === '' && eventType) {
                callbacks.onEvent?.(eventType, eventData);
                eventType = null; eventData = '';
            }
        }
    }
}

export async function sendStreamMessage(text, sessionId, useRag, attachmentIds, hooks) {
    console.log('[Chat] 开始流式请求, sessionId:', sessionId, 'attachments:', attachmentIds);
    const resp = await api.streamChat({
        prompt: text || '',
        sessionId,
        useRag,
        attachmentIds: attachmentIds || []
    });
    console.log('[Chat] 响应状态:', resp.status);
    if (!resp.ok) {
        const err = await resp.json().catch(() => ({ message: '请求失败' }));
        throw new Error(err.message || `请求失败: ${resp.status}`);
    }
    let fullText = '';
    await parseSSE(resp.body, {
        onEvent(type, data) {
            console.log('[Chat] 收到事件类型:', type);
            switch (type) {
                case 'session_id':
                    hooks.onSessionId?.(parseInt(data));
                    break;
                case 'thinking':
                    try { hooks.onThinking?.(JSON.parse(data).thought || ''); } catch(e) {}
                    break;
                case 'tool_call':
                    try { hooks.onToolCall?.(JSON.parse(data).tool, JSON.parse(data).args || '{}'); } catch(e) {}
                    break;
                case 'tool_result':
                    try { hooks.onToolResult?.(JSON.parse(data).tool, JSON.parse(data)); } catch(e) {}
                    break;
                case 'content':
                    fullText += data;
                    hooks.onContent?.(fullText, data);
                    break;
                case 'done':
                    hooks.onDone?.(fullText);
                    break;
                case 'error':
                    hooks.onError?.(new Error(data));
                    break;
                default:
                    console.warn('[Chat] 未知事件类型:', type, 'data:', data.substring(0, 100));
            }
        }
    });
    console.log('[Chat] 流式请求结束');
}

export async function handleMessageAction(btn, onRegenerate) {
    const action = btn.dataset.action;
    const msgEl = btn.closest('.message');
    if (action === 'copy') {
        const text = msgEl.querySelector('.md-content')?.textContent || '';
        await copyToClipboard(text);
        btn.textContent = '✅ 已复制';
        setTimeout(() => btn.textContent = '📋 复制', 2000);
    } else if (action === 'regenerate') {
        let prev = msgEl.previousElementSibling;
        while (prev && !prev.classList.contains('user')) prev = prev.previousElementSibling;
        if (prev) {
            const text = prev.textContent;
            msgEl.remove(); prev.remove();
            onRegenerate(text);
        }
    } else if (action === 'copy-code') {
        const code = btn.closest('pre').querySelector('code').textContent;
        await copyToClipboard(code);
        btn.textContent = '已复制';
        btn.classList.add('copied');
        setTimeout(() => { btn.textContent = '复制'; btn.classList.remove('copied'); }, 2000);
    }
}