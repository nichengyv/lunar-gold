/**
 * R6：审批域 store。
 *
 * <p>只负责「待审批卡片 + 审批历史」这一块状态与请求；会话隔离靠 `sessionId` 字段，
 * 与聊天消息、流式生命周期解耦。原 `useChatStore` 保留同名 action 作为兼容入口。
 */
import { defineStore } from 'pinia';
import { ElMessage } from 'element-plus';
import * as agentApi from '../api/agent';
import type { PendingApproval } from '../types/api';
import type { ApprovalDecisionSource, ApprovalHistoryItem, ApprovalRequest } from '../types/chat';

export const useApprovalStore = defineStore('chat-approvals', {
  state: () => ({
    /** 第六十八批 A2：等待用户确认的危险工具调用（按会话隔离） */
    pendingApprovals: [] as PendingApproval[],
    /** P5-12-4：轨迹里点「待审批」步骤时递增，聊天区据此高亮并定位审批区 */
    approvalFocusToken: 0,
    /** P5-10-2：审批状态变化计数，追踪页据此刷新会话审批历史 */
    approvalRevision: 0,
    /** P5-10-2：当前会话已处理审批历史 */
    approvalHistory: [] as ApprovalHistoryItem[],
    approvalHistoryLoading: false,
    /** 历史请求归属的会话；切会话后丢弃迟到的响应 */
    historySessionId: null as number | null
  }),

  actions: {
    /** SSE approval_required：同一 approvalId 只保留最新一条 */
    request(req: ApprovalRequest, sessionId: number | null) {
      this.pendingApprovals = this.pendingApprovals.filter((a) => a.approvalId !== req.approvalId);
      this.pendingApprovals.push({ ...req, sessionId });
    },

    /** SSE approval_resolved / 本地乐观更新：把卡片切到已处理态 */
    resolve(
      approvalId: string,
      decision: 'APPROVE' | 'REJECT',
      source?: ApprovalDecisionSource,
      batchId?: string
    ) {
      const idx = this.pendingApprovals.findIndex((a) => a.approvalId === approvalId);
      if (idx >= 0) {
        this.pendingApprovals[idx] = {
          ...this.pendingApprovals[idx],
          resolved: decision,
          resolvedSource: source,
          batchId
        };
      }
      this.approvalRevision += 1;
    },

    /** 停止生成时后端会拒绝该会话所有审批，前端同步清空卡片 */
    clearSession(sessionId: number | null) {
      if (sessionId == null) return;
      this.pendingApprovals = this.pendingApprovals.filter((a) => a.sessionId !== sessionId);
      this.approvalRevision += 1;
    },

    /** 切会话 / 新会话：历史按会话隔离，等追踪页按需重拉 */
    clearHistory() {
      this.approvalHistory = [];
      this.historySessionId = null;
    },

    /** 审批超时 / 过期：仅移除卡片，后端到时间会自动拒绝 */
    dismissApproval(approvalId: string) {
      this.pendingApprovals = this.pendingApprovals.filter((a) => a.approvalId !== approvalId);
    },

    /** P5-12-4：从轨迹面板点待审批步骤，定位到审批区 */
    requestFocus() {
      this.approvalFocusToken += 1;
    },

    /** 第六十八批 A2：提交工具审批结果；不在这里删卡片——等后端事件切 resolved 态，或用户手动关叉 */
    async respondApproval(approvalId: string, decision: 'APPROVE' | 'REJECT', rememberSession = false) {
      try {
        await agentApi.respondApproval(approvalId, decision, rememberSession ? 'SESSION' : 'NONE');
      } catch (e) {
        // P5-10-2：提交失败时保留待审批，不能在本地伪造已处理
        ElMessage.error('审批提交失败: ' + (e as Error).message);
        return;
      }
      // 乐观更新：后端大概率会发 approval_resolved，但 SSE 事件有延迟；先标个 resolved 占位，避免用户看到"已点了但还没状态"
      const idx = this.pendingApprovals.findIndex((a) => a.approvalId === approvalId);
      if (idx >= 0 && !this.pendingApprovals[idx].resolved) {
        // 来源先按 USER 占位，服务端事件到达后以真实 source 为准
        this.pendingApprovals[idx] = { ...this.pendingApprovals[idx], resolved: decision, resolvedSource: 'USER' };
      }
      this.approvalRevision += 1;
    },

    /** P5-10-2：批量允许低风险写入；只按服务端返回的 resolvedIds 标记，不夸大成功 */
    async approveBatch(sessionId: number | null, approvalIds: string[]) {
      if (sessionId == null || !approvalIds.length) return;
      try {
        const { batchId, resolvedIds } = await agentApi.approveBatch(sessionId, approvalIds);
        const done = new Set(resolvedIds);
        this.pendingApprovals = this.pendingApprovals.map((a) =>
          done.has(a.approvalId) && !a.resolved
            ? { ...a, resolved: 'APPROVE' as const, resolvedSource: 'BATCH' as const, batchId }
            : a
        );
        this.approvalRevision += 1;
      } catch (e) {
        ElMessage.error('批量允许失败: ' + (e as Error).message);
      }
    },

    /** P5-10-2：加载会话内已处理审批历史；返回时校验会话没被切走，避免串数据 */
    async loadApprovalHistory(sessionId: number | null) {
      this.historySessionId = sessionId;
      if (sessionId == null) {
        this.approvalHistory = [];
        return;
      }
      this.approvalHistoryLoading = true;
      try {
        const items = await agentApi.getSessionApprovals(sessionId);
        if (this.historySessionId === sessionId) this.approvalHistory = items;
      } catch {
        if (this.historySessionId === sessionId) this.approvalHistory = [];
      } finally {
        if (this.historySessionId === sessionId) this.approvalHistoryLoading = false;
      }
    }
  }
});
