import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { reactive } from 'vue';
import type { PendingApproval, UiMessage } from '../types/api';
import * as agentApi from '../api/agent';
import { streamChat } from '../api/sse';
import { getExperienceDiagnostics } from '../utils/experienceDiagnostics';
import {
  buildStageTimeline,
  classifyFailure,
  CONTINUE_PROMPT,
  isBatchApprovable,
  stageForTool,
  useChatStore
} from './chat';

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() }
}));

vi.mock('../api/agent', () => ({
  listSessions: vi.fn(async () => []),
  listRunningRuns: vi.fn(async () => []),
  getSessionMessages: vi.fn(async () => []),
  getRunEvents: vi.fn(),
  stopGeneration: vi.fn(async () => undefined),
  respondApproval: vi.fn(async () => undefined),
  approveBatch: vi.fn(async () => ({ batchId: 'bt-1', resolvedIds: [] })),
  getSessionApprovals: vi.fn(async () => []),
  deleteSession: vi.fn(async () => undefined),
  renameSession: vi.fn(async () => undefined),
  updateSessionPermission: vi.fn(async () => undefined),
  uploadChatAttachment: vi.fn()
}));

vi.mock('../api/settings', () => ({
  getDefaults: vi.fn(async () => ({}))
}));

vi.mock('../api/fileChanges', () => ({
  fetchPendingChanges: vi.fn(async () => []),
  fetchChangeSet: vi.fn(async () => null)
}));

vi.mock('../api/sse', () => ({
  streamChat: vi.fn()
}));

const streamChatMock = vi.mocked(streamChat);

function assistantMessage(): UiMessage {
  const list = useChatStore().messages.filter((m) => m.role === 'assistant');
  return list[list.length - 1];
}

describe('chat store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    streamChatMock.mockReset();
  });

  it('发送成功后完成本轮并释放 runId', async () => {
    localStorage.clear();
    let runIdWhileStreaming: number | null = null;
    let processingWhileStreaming = false;
    streamChatMock.mockImplementation(async (_payload, hooks) => {
      hooks.onSessionId?.(3);
      hooks.onRunId?.(9);
      runIdWhileStreaming = useChatStore().activeRunId;
      processingWhileStreaming = useChatStore().isProcessing;
      hooks.onContent?.('你好', '你');
      hooks.onContent?.('你好', '好');
      hooks.onDone?.('你好');
    });

    const store = useChatStore();
    await store.sendMessage('你好');

    expect(runIdWhileStreaming).toBe(9);
    expect(processingWhileStreaming).toBe(true);
    expect(store.currentSessionId).toBe(3);
    expect(store.messages.map((m) => m.role)).toEqual(['user', 'assistant']);
    expect(assistantMessage().content).toBe('你好');
    expect(assistantMessage().streaming).toBe(false);
    expect(store.isProcessing).toBe(false);
    expect(store.activeRunId).toBeNull();
    expect(store.streamStatus).toBe('idle');
    expect(getExperienceDiagnostics().runs).toMatchObject([{ runId: 9, sessionId: 3 }]);
  });

  it('停止生成保留半截内容且状态落为已停止', async () => {
    streamChatMock.mockImplementation((_payload, hooks, signal) => {
      hooks.onSessionId?.(3);
      hooks.onRunId?.(7);
      hooks.onContent?.('半截', '半截');
      return new Promise<void>((_resolve, reject) => {
        signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
      });
    });

    const store = useChatStore();
    const promise = store.sendMessage('你好');
    expect(store.activeRunId).toBe(7);
    store.stopGenerating();
    await promise;

    expect(assistantMessage().content).toBe('半截');
    expect(assistantMessage().stopped).toBe(true);
    expect(assistantMessage().failed).toBeFalsy();
    expect(store.isProcessing).toBe(false);
    expect(store.streamStatus).toBe('idle');
    expect(agentApi.stopGeneration).toHaveBeenCalledWith(3);
  });

  it('提交审批后回到运行中，审批项标记为已处理', async () => {
    const states: Array<string | undefined> = [];
    streamChatMock.mockImplementation(async (_payload, hooks) => {
      hooks.onSessionId?.(3);
      hooks.onRunId?.(9);
      hooks.onApprovalRequired?.({
        approvalId: 'ap-1',
        tool: 'write_file',
        risk: 'WRITE',
        label: '写入',
        preview: 'a.txt',
        expiresAt: Date.now() + 60_000
      } as never);
      states.push(useChatStore().runningRunMap[3]);
      hooks.onApprovalResolved?.('ap-1', 'APPROVE');
      states.push(useChatStore().runningRunMap[3]);
      hooks.onDone?.('');
    });

    const store = useChatStore();
    await store.sendMessage('改文件');

    // 待审批时从运行集移除，处理完回到 RUNNING
    expect(states).toEqual([undefined, 'RUNNING']);
    expect(store.pendingApprovals[0].approvalId).toBe('ap-1');
    expect(store.pendingApprovals[0].resolved).toBe('APPROVE');
  });

  it('P5-10-3：按事件推进阶段并跳过未到达的中间阶段', () => {
    const marks = { understand: 1000, read: 3000, modify: 6000, summarize: 9000 };
    const stages = buildStageTimeline(marks, 12000);

    expect(stages.map((s) => [s.key, s.status])).toEqual([
      ['understand', 'done'],
      ['read', 'done'],
      ['modify', 'done'],
      ['verify', 'skipped'],
      ['summarize', 'active']
    ]);
    expect(stages.map((s) => s.durationMs)).toEqual([2000, 3000, 3000, undefined, 3000]);
  });

  it('P5-10-3：run_command 在修改前算读取、修改后算验证', () => {
    expect(stageForTool('read_file', false)).toBe('read');
    expect(stageForTool('write_file', false)).toBe('modify');
    expect(stageForTool('run_command', false)).toBe('read');
    expect(stageForTool('run_command', true)).toBe('verify');
    expect(stageForTool('diagnostics', false)).toBe('verify');
    expect(stageForTool('unknown_tool', false)).toBeNull();
  });

  it('P5-10-3：失败分为可重试、需配置、需审批、需人工四类', () => {
    expect(classifyFailure('HTTP 502 Bad Gateway').class).toBe('retryable');
    expect(classifyFailure('Error: timeout').primaryAction).toBe('retry');
    expect(classifyFailure('401 Unauthorized: API Key 未配置').class).toBe('config');
    expect(classifyFailure('401 Unauthorized: API Key 未配置').primaryAction).toBe('settings');
    expect(classifyFailure('', { reason: 'APPROVAL_REJECTED', status: 'INCOMPLETE' } as never).class).toBe('approval');
    expect(classifyFailure('', { reason: 'MAX_TOOL_ROUNDS', status: 'INCOMPLETE' } as never).class).toBe('retryable');
    expect(classifyFailure('文件写入失败：权限不足').class).toBe('manual');
    expect(classifyFailure(undefined, { reason: 'TIME_BUDGET_EXCEEDED', status: 'INCOMPLETE' } as never).class).toBe('manual');
  });

  it('P5-10-3：运行中记录阶段与预算', async () => {
    streamChatMock.mockImplementation(async (_payload, hooks) => {
      hooks.onSessionId?.(3);
      hooks.onRunId?.(9);
      hooks.onToolCall?.('read_file', '{}');
      hooks.onFileChange?.({ path: 'a.ts', action: 'edit', additions: 1, deletions: 0 } as never);
      hooks.onDiagnostics?.({ available: true, analyzedFiles: 1, diagnostics: [] } as never);
      hooks.onUsage?.({
        promptTokens: 1200,
        completionTokens: 300,
        totalTokens: 1500,
        round: 2,
        maxRounds: 30,
        maxTokens: 200000,
        elapsedMs: 45000,
        maxElapsedMs: 600000
      });
      hooks.onDone?.('完成');
    });

    const store = useChatStore();
    await store.sendMessage('改一下');

    expect(store.liveStageMarks.read).toBeTypeOf('number');
    expect(store.liveStageMarks.modify).toBeTypeOf('number');
    expect(store.liveStageMarks.verify).toBeTypeOf('number');
    expect(store.liveStageMarks.summarize).toBeTypeOf('number');
    expect(store.liveBudget).toMatchObject({ round: 2, maxRounds: 30, totalTokens: 1500, maxTokens: 200000 });
  });

  it('P5-10-3：停止后用固定提示继续', async () => {
    streamChatMock.mockImplementation(async (_payload, hooks) => {
      hooks.onSessionId?.(3);
      hooks.onDone?.('继续完成');
    });

    const store = useChatStore();
    store.messages.push({
      id: 5,
      role: 'assistant',
      content: '半截',
      tools: [],
      streaming: false,
      stopped: true
    } as UiMessage);

    await store.continueTask(5);

    expect(streamChatMock).toHaveBeenCalledTimes(1);
    expect(streamChatMock.mock.calls[0][0]).toMatchObject({ prompt: CONTINUE_PROMPT });
    expect(store.messages.some((m) => m.role === 'user' && m.content === CONTINUE_PROMPT)).toBe(true);
  });

  it('P5-11-3：流式合帧后最终正文与后端全量文本一致', async () => {
    const chunks = ['第一段', '第一段\n\n第二段', '第一段\n\n第二段\n\n尾段'];
    streamChatMock.mockImplementation(async (_payload, hooks) => {
      hooks.onSessionId?.(3);
      hooks.onRunId?.(9);
      hooks.onThinking?.('思考');
      for (const text of chunks) hooks.onContent?.(text, text);
      hooks.onDone?.(chunks[chunks.length - 1]);
    });

    const store = useChatStore();
    await store.sendMessage('你好');
    await new Promise((resolve) => requestAnimationFrame(() => resolve(null)));

    expect(assistantMessage().content).toBe('第一段\n\n第二段\n\n尾段');
    expect(assistantMessage().thinking).toBe('思考');
    expect(assistantMessage().streaming).toBe(false);
  });

  it('按 runId 重放已落库事件恢复正文', async () => {
    localStorage.clear();
    vi.mocked(agentApi.getRunEvents).mockResolvedValue({
      runId: 42,
      state: 'SUCCEEDED',
      events: [
        { seq: 1, type: 'MODEL_CHUNK', payload: JSON.stringify({ text: '恢复' }) },
        { seq: 2, type: 'MODEL_CHUNK', payload: JSON.stringify({ text: '恢复内容', length: 4 }) }
      ]
    });
    const store = useChatStore();
    store.currentSessionId = 3;
    const target = reactive<UiMessage>({
      id: 1,
      role: 'assistant',
      content: '',
      tools: [],
      streaming: true
    });

    await store.recoverRun(42, target);

    expect(target.content).toBe('恢复内容');
    expect(target.streaming).toBe(false);
    expect(store.streamStatus).toBe('recovered');
    expect(store.recoveredTruncated).toBe(false);
    expect(getExperienceDiagnostics().recoveries).toMatchObject([
      { runId: 42, sessionId: 3, outcome: 'recovered', pollAttempts: 1 }
    ]);
  });

  it('网络重试耗尽记录恢复失败和实际轮询次数', async () => {
    localStorage.clear();
    vi.mocked(agentApi.getRunEvents).mockRejectedValue(new Error('offline'));
    const store = useChatStore();
    store.currentSessionId = 3;
    const target = reactive<UiMessage>({
      id: 1,
      role: 'assistant',
      content: '',
      tools: [],
      streaming: true
    });

    await store.recoverRun(42, target, 1);

    expect(store.streamStatus).toBe('interrupted');
    expect(getExperienceDiagnostics().recoveries).toMatchObject([
      { runId: 42, sessionId: 3, outcome: 'failed', pollAttempts: 1 }
    ]);
  });

  it('Run 失败终态不与传输恢复失败混算', async () => {
    localStorage.clear();
    vi.mocked(agentApi.getRunEvents).mockResolvedValue({ runId: 42, state: 'FAILED', events: [] });
    const store = useChatStore();
    store.currentSessionId = 3;
    const target = reactive<UiMessage>({
      id: 1,
      role: 'assistant',
      content: '',
      tools: [],
      streaming: true
    });

    await store.recoverRun(42, target);

    expect(store.streamStatus).toBe('interrupted');
    expect(target.failed).toBe(true);
    expect(getExperienceDiagnostics().recoveries[0].outcome).toBe('recovered');
  });

  it('P5-10-2：低风险白名单只覆盖写和编辑文件', () => {
    expect(isBatchApprovable({ tool: 'write_file', risk: 'WRITE' })).toBe(true);
    expect(isBatchApprovable({ tool: 'edit_file', risk: 'WRITE' })).toBe(true);
    expect(isBatchApprovable({ tool: 'mcp_filesystem_write_file', risk: 'WRITE' })).toBe(true);
    expect(isBatchApprovable({ tool: 'run_command', risk: 'EXECUTE' })).toBe(false);
    expect(isBatchApprovable({ tool: 'delete_file', risk: 'WRITE' })).toBe(false);
    expect(isBatchApprovable({ tool: 'mcp_filesystem_create_directory', risk: 'WRITE' })).toBe(false);
    expect(isBatchApprovable({ tool: 'mcp_filesystem_move_file', risk: 'WRITE' })).toBe(false);
    expect(isBatchApprovable({ tool: 'mcp_filesystem_delete_file', risk: 'DESTRUCTIVE' })).toBe(false);
  });

  it('P5-10-2：批量允许只标记服务端返回的 resolvedIds', async () => {
    const store = useChatStore();
    store.currentSessionId = 3;
    store.pendingApprovals = [
      { approvalId: 'ap-1', sessionId: 3, tool: 'write_file', risk: 'WRITE', expiresAt: 0 },
      { approvalId: 'ap-2', sessionId: 3, tool: 'run_command', risk: 'EXECUTE', expiresAt: 0 }
    ] as PendingApproval[];
    vi.mocked(agentApi.approveBatch).mockResolvedValue({ batchId: 'bt-1', resolvedIds: ['ap-1'] });

    await store.approveBatch(['ap-1', 'ap-2']);

    expect(store.pendingApprovals[0]).toMatchObject({
      resolved: 'APPROVE',
      resolvedSource: 'BATCH',
      batchId: 'bt-1'
    });
    expect(store.pendingApprovals[1].resolved).toBeUndefined();
    expect(agentApi.approveBatch).toHaveBeenCalledWith(3, ['ap-1', 'ap-2']);
  });

  it('P5-10-2：审批提交失败保留待审批，不伪造已处理', async () => {
    const store = useChatStore();
    store.pendingApprovals = [
      { approvalId: 'ap-3', sessionId: 3, tool: 'write_file', risk: 'WRITE', expiresAt: 0 }
    ] as PendingApproval[];
    vi.mocked(agentApi.respondApproval).mockRejectedValueOnce(new Error('network'));

    await store.respondApproval('ap-3', 'APPROVE');

    expect(store.pendingApprovals[0].resolved).toBeUndefined();
    expect(store.approvalRevision).toBe(0);
  });

  it('P5-10-2：审批提交成功先按本人来源乐观更新', async () => {
    const store = useChatStore();
    store.pendingApprovals = [
      { approvalId: 'ap-4', sessionId: 3, tool: 'write_file', risk: 'WRITE', expiresAt: 0 }
    ] as PendingApproval[];
    vi.mocked(agentApi.respondApproval).mockResolvedValueOnce(undefined);

    await store.respondApproval('ap-4', 'REJECT');

    expect(store.pendingApprovals[0]).toMatchObject({ resolved: 'REJECT', resolvedSource: 'USER' });
    expect(store.approvalRevision).toBe(1);
  });
});
