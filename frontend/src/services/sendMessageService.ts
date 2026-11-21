/**
 * R6c：发送与流式收尾编排。
 *
 * <p>从 `useChatStore.sendMessage` 抽出：只负责把一轮请求送出去、把 SSE 事件
 * 映射到消息气泡 / 流式状态 / 审批 / 决策询问，以及连接提前结束时的收尾。
 * 会话列表刷新、侧栏状态点、耗时统计与 isProcessing 等 store 内部簿记仍由
 * chat facade 完成，通过 `SendMessageDeps` 回调注入。
 */
import { markExperienceRun } from '../utils/experienceDiagnostics';
import { createFrameThrottle } from '../utils/frameThrottle';
import { stageForTool } from '../utils/taskStages';
import { useApprovalStore } from '../stores/approvals';
import { useQuestionStore } from '../stores/question';
import { useStreamStore } from '../stores/stream';
import { streamChat, streamResume } from './streamService';
import type { StreamHooks } from './streamService';
import type { FileChange, ToolInvocation, UiMessage } from '../types/api';
import type { PermissionLevel, ReasoningLevel, TaskStageKey } from '../types/chat';

export interface SendMessageParams {
  prompt: string;
  sessionId: number | null;
  /** 当前项目：后端据此决定检索哪个项目的资料 */
  projectId?: number;
  attachmentIds: number[];
  /** 会话头当前选择的模型 / 推理档位 / 工作权限，每轮都随请求下发 */
  model: string;
  reasoning: ReasoningLevel;
  permission: PermissionLevel;
}

export interface SendMessageDeps {
  /** 本轮助手气泡（已挂到消息列表） */
  assistant: UiMessage;
  /** 本轮流的中断信号：用户停止 / 切会话时 abort */
  signal: AbortSignal;
  /** 当前会话 id（新建会话后由 onSessionCreated 更新） */
  getSessionId(): number | null;
  /** 新建会话拿到 id：写会话缓存、合并附件、刷新侧栏 */
  onSessionCreated(sessionId: number): void;
  /** 侧栏运行状态点：待审批 / 回到运行中 */
  onSessionState(state: 'RUNNING' | 'WAITING_APPROVAL'): void;
  /** 记录阶段首次到达 */
  markStage(key: TaskStageKey): void;
  /** 连接提前结束但后端仍在跑：按 runId 续上 */
  recoverRun(runId: number, target: UiMessage): Promise<void>;
}

/** 把 SSE 事件写到气泡、流式状态、审批与询问上；失败/停止状态也在这里落定 */
export async function sendChatMessage(params: SendMessageParams, deps: SendMessageDeps): Promise<void> {
  await runStream(deps, (hooks, signal) =>
    streamChat(
      {
        prompt: params.prompt,
        sessionId: params.sessionId,
        projectId: params.projectId,
        // 个人助手定位：资料检索默认开启且不暴露开关，用户无需理解 RAG
        useRag: true,
        attachmentIds: params.attachmentIds,
        model: params.model,
        reasoning: params.reasoning,
        permission: params.permission,
        clientRequestId: crypto.randomUUID()
      },
      hooks,
      signal
    )
  );
}

/**
 * R7b：从断点续跑被进程重启中断的 Run。
 * 事件映射、审批、决策询问与收尾逻辑与普通发送完全一致，只是入口换成 resume 接口。
 */
export async function resumeChatRun(runId: number, deps: SendMessageDeps): Promise<void> {
  await runStream(deps, (hooks, signal) => streamResume(runId, hooks, signal));
}

async function runStream(
  deps: SendMessageDeps,
  open: (hooks: StreamHooks, signal: AbortSignal) => Promise<void>
): Promise<void> {
  const aiMsg = deps.assistant;
  const stream = useStreamStore();
  const approvals = useApprovalStore();
  const question = useQuestionStore();

  // P5-11-3：SSE 每个 chunk 只保留最新全量文本，实际写响应式状态按帧合并（最多 20 次/秒）。
  // 结束、失败、停止与 finally 都会 flush，保证正文最终与后端全量文本逐字一致。
  const contentThrottle = createFrameThrottle<string>((full) => {
    aiMsg.content = full;
  });
  const thinkingThrottle = createFrameThrottle<string>((thought) => {
    aiMsg.thinking = thought;
  });
  const flushStreamedText = () => {
    contentThrottle.flush();
    thinkingThrottle.flush();
  };

  try {
    await open(
      {
        onSessionId: (id) => deps.onSessionCreated(id),
        // 第六十八批 A3：本轮 id 用于「撤回本轮」文件改动
        onTurnId: (turnId) => {
          aiMsg.turnId = turnId;
        },
        // P5-10：记下运行 id，连接断了可以凭它续上这一轮
        onRunId: (runId) => {
          stream.setRunId(runId);
          markExperienceRun(runId, deps.getSessionId());
        },
        onThinking: (thought) => {
          thinkingThrottle.push(thought);
        },
        onSources: (sources) => {
          aiMsg.sources = sources;
        },
        // Agent 提交/更新任务计划：同一轮内覆盖最新的计划快照
        onPlan: (plan) => {
          aiMsg.plan = plan;
          // 同时记到 store 级别：即使消息数组被刷新替换，计划面板仍能实时显示
          stream.setPlan(plan);
        },
        // 危险工具执行前挂起：把审批项挂到当前会话，切换会话时互不干扰
        onApprovalRequired: (req) => {
          approvals.request(req, deps.getSessionId());
          // P5-12-2：侧栏状态点同步成「待审批」，和审批卡片一致
          deps.onSessionState('WAITING_APPROVAL');
        },
        // 审批被处理（用户点击 / 超时 / 停止会话）：标记为 resolved，卡片切到"已处理"态并给叉号
        onApprovalResolved: (approvalId, decision, source, batchId) => {
          // P5-10-2：通知追踪页刷新审批历史（resolve 内部已自增 approvalRevision）
          approvals.resolve(approvalId, decision, source, batchId);
          // P5-12-2：处理完就回到运行中，终态由本轮收尾统一落
          deps.onSessionState('RUNNING');
        },
        // P7-06：agent 暂停等用户回答决策询问
        onUserQuestion: (q) => {
          question.ask(q);
        },
        // 同一文件被多次改动时只保留最新一条，避免面板里重复堆叠
        onFileChange: (change: FileChange) => {
          if (!aiMsg.changes) aiMsg.changes = [];
          const idx = aiMsg.changes.findIndex((c) => c.path === change.path);
          if (idx >= 0) aiMsg.changes[idx] = change;
          else aiMsg.changes.push(change);
          deps.markStage('modify');
        },
        onToolCall: (tool, args) => {
          aiMsg.tools.push({ name: tool, args, status: 'running', startedAt: Date.now() } as ToolInvocation);
          const stage = stageForTool(tool, !!stream.liveStageMarks.modify);
          if (stage) deps.markStage(stage);
        },
        onToolResult: (tool, data) => {
          const target =
            [...aiMsg.tools].reverse().find((t) => t.name === tool && t.status === 'running') ||
            aiMsg.tools[aiMsg.tools.length - 1];
          if (target) {
            target.status = data.success ? 'success' : 'error';
            target.result = data.content;
            target.error = data.error;
            target.costMs = data.costMs;
          }
        },
        onContent: (full) => {
          contentThrottle.push(full);
        },
        onCompletion: (completion) => {
          aiMsg.completion = completion;
        },
        onDiagnostics: (diagnostics) => {
          aiMsg.diagnostics = diagnostics;
          deps.markStage('verify');
        },
        onVerify: (verify) => {
          aiMsg.verify = verify;
          deps.markStage('verify');
        },
        // P5-10-3：每轮实时预算进度；旧后端不会发 usage 时保持 null 降级展示
        onUsage: (usage) => {
          stream.setBudget(usage);
          // 气泡上展示本轮用量：usage 是 Run 累计值，取最新一次即可
          aiMsg.usage = {
            promptTokens: usage.promptTokens,
            completionTokens: usage.completionTokens,
            totalTokens: usage.totalTokens
          };
        },
        onDone: () => {
          // 先提交最后一段文本，再切到完成态：避免合帧丢掉结尾或最后一帧仍是流式渲染
          flushStreamedText();
          deps.markStage('summarize');
          aiMsg.streaming = false;
          stream.finish();
          // P7-06：Run 已结束，等待中的询问不可能再被回答（超时/取消后后端会拉回 RUNNING）
          question.clear();
          // 思考内容保留：完成后折叠成「思考过程」入口，切回会话也仍能看到
        },
        onError: (message) => {
          flushStreamedText();
          aiMsg.streaming = false;
          aiMsg.failed = true;
          stream.finish();
          // P7-06：Run 已失败，残留的询问卡片不再可用
          question.clear();
          // 只记失败原因，不覆盖已经流出来的半截回答
          aiMsg.errorText = message || '这一轮没有完成';
        }
      },
      deps.signal
    );
    // 流正常结束（无论是否收到 done）先把合帧中的最后一段提交出去
    flushStreamedText();
    // P5-10：流先结束了却没收到 done（后端收尾前连接被掐断）——按 runId 把这一轮补完
    if (aiMsg.streaming && !deps.signal.aborted) {
      if (stream.activeRunId != null) {
        await deps.recoverRun(stream.activeRunId, aiMsg);
      } else {
        aiMsg.streaming = false;
        aiMsg.failed = true;
        aiMsg.errorText = '连接提前结束了，请稍后再试';
        stream.finish();
      }
    }
  } catch (e) {
    flushStreamedText();
    aiMsg.streaming = false;
    if (deps.signal.aborted) {
      // 用户主动停止：保留已生成的部分，不标成失败
      aiMsg.stopped = true;
      stream.finish();
      if (!aiMsg.content) aiMsg.content = '（已停止生成）';
    } else if (stream.activeRunId != null) {
      // P5-10：连接断了但后端还在跑——按 runId 续上已生成内容和后续输出
      await deps.recoverRun(stream.activeRunId, aiMsg);
    } else {
      aiMsg.failed = true;
      stream.finish();
      const raw = (e as Error).message || '';
      // 浏览器网络层错误只会说 "Failed to fetch"，对普通用户没有意义
      aiMsg.errorText = /failed to fetch|networkerror|load failed/i.test(raw)
        ? '网络连接中断了'
        : raw || '这一轮没有完成';
    }
  }
}
