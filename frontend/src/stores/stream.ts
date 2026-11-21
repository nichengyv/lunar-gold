/**
 * R6：流式运行态 store。
 *
 * <p>承载「本轮」的临时状态：计划快照、预算、阶段时间线、连接状态与恢复信息。
 * 这些状态的生命周期以一次 Run 为单位，与消息历史、会话元数据分开管理。
 */
import { defineStore } from 'pinia';
import type { PlanSnapshot, RunUsage, TaskStageKey } from '../types/chat';

export type StreamStatus = 'idle' | 'streaming' | 'reconnecting' | 'recovered' | 'interrupted';

export const useStreamStore = defineStore('chat-stream', {
  state: () => ({
    /** 运行中的实时计划快照：SSE plan 事件直接写这里，避免依赖具体消息对象是否还在数组里 */
    livePlan: null as PlanSnapshot | null,
    /** P5-10-3：本轮预算进度；断线恢复后可能只有 token 三项 */
    liveBudget: null as RunUsage | null,
    /** P5-10-3：本轮各阶段首次到达时间 */
    liveStageMarks: {} as Partial<Record<TaskStageKey, number>>,
    /** P5-10-3：本轮开始时间，用于本地耗时推进 */
    runStartedAt: 0,
    /** P5-10：流式连接状态，驱动输入框上方的状态条 */
    streamStatus: 'idle' as StreamStatus,
    /** P5-10：当前轮次的运行 id；断线或刷新后凭它重放已落库事件 */
    activeRunId: null as number | null,
    /** P5-10：恢复出来的正文是否因为超出检查点上限而被截断 */
    recoveredTruncated: false
  }),

  actions: {
    /** 新一轮开始：清空上一轮的计划、预算与恢复标记 */
    beginRun(startedAt: number) {
      this.livePlan = null;
      this.liveBudget = null;
      this.liveStageMarks = {};
      this.runStartedAt = startedAt;
      this.streamStatus = 'streaming';
      this.activeRunId = null;
      this.recoveredTruncated = false;
    },

    markStage(key: TaskStageKey) {
      if (!this.liveStageMarks[key]) this.liveStageMarks[key] = Date.now();
    },

    setPlan(plan: PlanSnapshot | null) {
      this.livePlan = plan;
    },

    setBudget(usage: RunUsage | null) {
      this.liveBudget = usage;
    },

    setRunId(runId: number | null) {
      this.activeRunId = runId;
    },

    setStatus(status: StreamStatus) {
      this.streamStatus = status;
    },

    setRecoveredTruncated(truncated: boolean) {
      this.recoveredTruncated = truncated;
    },

    /** 流正常结束 / 失败后回到空闲，并释放 runId */
    finish() {
      this.streamStatus = 'idle';
      this.activeRunId = null;
    },

    /** 切会话 / 新会话：中断状态展示，不清计划（由消息加载决定） */
    resetSessionState() {
      this.streamStatus = 'idle';
      this.activeRunId = null;
    }
  }
});
