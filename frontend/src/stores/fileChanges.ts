/**
 * R6：文件变更审查域 store。
 *
 * <p>覆盖项目级 PENDING 列表与任务级交付视图（ChangeSet），以及保留/撤回动作。
 * 会话切换由调用方显式传入 `sessionId`；内部用请求令牌丢弃迟到响应，避免串会话。
 */
import { defineStore } from 'pinia';
import { ElMessage } from 'element-plus';
import * as fileChangesApi from '../api/fileChanges';
import type { ChangeSetView, PendingFileChange } from '../types/api';

export const useFileChangeStore = defineStore('chat-file-changes', {
  state: () => ({
    /** 文件变更审查流：项目级 pending 变更列表 */
    pendingChanges: [] as PendingFileChange[],
    pendingChangesLoading: false as boolean,
    /** P5-14：任务级交付视图（按 Session 聚合多个 Run 的变更/审批/验证） */
    changeSet: null as ChangeSetView | null,
    changeSetLoading: false as boolean,
    changeSetRunFilter: null as number | null,
    /** ChangeSet 请求令牌：只接受最后一次请求的响应 */
    changeSetToken: 0
  }),

  actions: {
    async fetchPendingChanges(projectId: number) {
      this.pendingChangesLoading = true;
      try {
        this.pendingChanges = await fileChangesApi.fetchPendingChanges(projectId);
      } catch {
        this.pendingChanges = [];
      } finally {
        this.pendingChangesLoading = false;
      }
    },

    /** 拉取当前会话的 ChangeSet；刷新后由后端事件重建同一视图 */
    async fetchChangeSet(sessionId: number | null) {
      const token = ++this.changeSetToken;
      if (sessionId == null) {
        this.changeSet = null;
        return;
      }
      this.changeSetLoading = true;
      try {
        const view = await fileChangesApi.fetchChangeSet(sessionId, this.changeSetRunFilter ?? undefined);
        if (token === this.changeSetToken) this.changeSet = view;
      } catch {
        if (token === this.changeSetToken) this.changeSet = null;
      } finally {
        if (token === this.changeSetToken) this.changeSetLoading = false;
      }
    },

    /** 按单个 Run 过滤交付视图；传 null 恢复全部 */
    async setChangeSetRunFilter(sessionId: number | null, runId: number | null) {
      this.changeSetRunFilter = runId;
      await this.fetchChangeSet(sessionId);
    },

    /** 切换项目：清空上一个项目的审查流与交付视图 */
    reset() {
      this.pendingChanges = [];
      this.changeSet = null;
      this.changeSetRunFilter = null;
      this.changeSetToken += 1;
    },

    /** 任务级整体撤回：逆序回滚，外部修改返回冲突 */
    async revertTask(sessionId: number | null): Promise<boolean> {
      if (sessionId == null) {
        ElMessage.warning('当前没有可撤回的任务');
        return false;
      }
      try {
        const resp = await fileChangesApi.revertSession(sessionId);
        this.reportRevert(resp, '已撤回');
        return resp.count > 0;
      } catch (e: any) {
        ElMessage.error(e?.message || '整体撤回失败');
        return false;
      }
    },

    /** 按 Run 撤回：只回滚该 Run 对应的 PENDING 变更 */
    async revertRunChanges(sessionId: number | null, runId: number): Promise<boolean> {
      if (sessionId == null) {
        ElMessage.warning('当前没有可撤回的任务');
        return false;
      }
      try {
        const resp = await fileChangesApi.revertRun(sessionId, runId);
        this.reportRevert(resp, `已撤回 #${runId}`);
        return resp.count > 0;
      } catch (e: any) {
        ElMessage.error(e?.message || '按 Run 撤回失败');
        return false;
      }
    },

    async revertChange(id: number): Promise<boolean> {
      try {
        const resp = await fileChangesApi.revertChange(id);
        if (resp.ok) {
          this.pendingChanges = this.pendingChanges.filter((c) => c.id !== id);
          ElMessage.success('已撤销该文件变更');
          return true;
        }
        ElMessage.error(resp.conflicts?.[0]?.reason || '撤销失败');
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '撤销请求失败');
        return false;
      }
    },

    async acceptChange(id: number): Promise<boolean> {
      try {
        const resp = await fileChangesApi.acceptChange(id);
        if (resp.ok) {
          this.pendingChanges = this.pendingChanges.filter((c) => c.id !== id);
          ElMessage.success('已保留该文件变更');
          return true;
        }
        ElMessage.error('保留失败');
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '保留请求失败');
        return false;
      }
    },

    async acceptAll(projectId: number): Promise<boolean> {
      try {
        const resp = await fileChangesApi.acceptAll(projectId);
        if (resp.ok) {
          const n = this.pendingChanges.length;
          this.pendingChanges = [];
          ElMessage.success(`已全部保留 ${n} 个文件变更`);
          return true;
        }
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '批量保留失败');
        return false;
      }
    },

    /**
     * P6-08：只保留传入的这些变更（卡片里列出的文件）。
     * 不走项目级 accept-all，避免把其他会话的 PENDING 一起接受；失败合并成一条提示。
     */
    async acceptChanges(ids: number[]): Promise<boolean> {
      if (!ids.length) return false;
      const results = await Promise.allSettled(ids.map((id) => fileChangesApi.acceptChange(id)));
      const accepted = new Set<number>();
      results.forEach((result, idx) => {
        if (result.status === 'fulfilled' && result.value?.ok) accepted.add(ids[idx]);
      });
      if (accepted.size === 0) {
        ElMessage.error('保留失败，请稍后重试');
        return false;
      }
      this.pendingChanges = this.pendingChanges.filter((c) => !accepted.has(c.id));
      const failed = ids.length - accepted.size;
      ElMessage.success(`已保留 ${accepted.size} 个文件变更${failed ? `（${failed} 个失败）` : ''}`);
      return true;
    },

    async revertAll(projectId: number): Promise<boolean> {
      try {
        const resp = await fileChangesApi.revertAll(projectId);
        if (resp.ok) {
          this.pendingChanges = [];
          ElMessage.success(`已撤销 ${resp.count} 个文件变更${resp.failed ? `（${resp.failed} 个失败）` : ''}`);
          return true;
        }
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '批量撤销失败');
        return false;
      }
    },

    /** 第六十八批 A3：撤回最近一轮的改动（按 sessionId + turnId 精确定位） */
    async revertTurn(sessionId: number | null, turnId: string | undefined): Promise<boolean> {
      if (sessionId == null || !turnId) {
        ElMessage.warning('暂无可撤回的本轮改动');
        return false;
      }
      try {
        const resp = await fileChangesApi.revertTurn(sessionId, turnId);
        if (resp.ok) {
          this.pendingChanges = this.pendingChanges.filter(
            (c) => c.sessionId !== sessionId || c.turnId !== turnId
          );
          ElMessage.success(
            `已撤回本轮 ${resp.count} 个文件变更${resp.failed ? `（${resp.failed} 个失败）` : ''}`
          );
          return true;
        }
        return false;
      } catch (e: any) {
        ElMessage.error(e?.message || '按轮次撤回失败');
        return false;
      }
    },

    reportRevert(
      resp: { count: number; failed: number; skippedAccepted: number; conflicts: { reason: string }[] },
      prefix: string
    ) {
      const parts: string[] = [];
      if (resp.count > 0) parts.push(`${prefix} ${resp.count} 个文件变更`);
      if (resp.skippedAccepted > 0) parts.push(`${resp.skippedAccepted} 个已接受变更保留审计`);
      if (resp.failed > 0) parts.push(`${resp.failed} 个失败`);
      const conflictCount = resp.conflicts?.length ?? 0;
      if (conflictCount > 0) parts.push(`${conflictCount} 个冲突未覆盖：${resp.conflicts[0].reason}`);
      if (resp.count > 0) {
        ElMessage.success(parts.join('，'));
      } else if (conflictCount > 0) {
        ElMessage.warning(parts.join('，'));
      } else {
        ElMessage.info(parts.join('，') || '没有可撤回的变更');
      }
    }
  }
});
