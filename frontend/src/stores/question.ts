/**
 * R6：决策询问域 store（P7-06）。
 *
 * <p>同一时刻只可能存在一个等待回答的询问，因此状态是单值而非列表。
 */
import { defineStore } from 'pinia';
import * as agentApi from '../api/agent';
import type { UserQuestion } from '../types/chat';

export const useQuestionStore = defineStore('chat-question', {
  state: () => ({
    /** agent 执行中等待用户回答的决策询问（含后端 runId） */
    pendingUserQuestion: null as (UserQuestion & { runId: number }) | null
  }),

  actions: {
    /** SSE user_question 事件到达：agent 暂停等用户回答决策询问 */
    ask(question: UserQuestion) {
      if (!question.runId) {
        // 没有 runId 的旧后端消息忽略
        console.warn('handleUserQuestion: 缺少 runId', question);
        return;
      }
      this.pendingUserQuestion = { ...question, runId: question.runId };
    },

    /** 提交用户回答；成功后清除 pending question */
    async answer(runId: number, questionId: string, selected: string[] = [], custom?: string) {
      await agentApi.answerUserQuestion(runId, questionId, selected, custom);
      // 清除本地 pending——后端恢复 Run 后会继续 SSE 流，不需要额外事件
      this.pendingUserQuestion = null;
    },

    /** 指定 run 的询问已失效（完成 / 失败 / 停止 / 恢复后不存在） */
    clearForRun(runId: number) {
      if (this.pendingUserQuestion?.runId === runId) this.pendingUserQuestion = null;
    },

    clear() {
      this.pendingUserQuestion = null;
    }
  }
});
