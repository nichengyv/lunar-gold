/**
 * R6：单次聊天流的中断句柄与断线恢复代际。
 *
 * <p>从 `stores/chat` 抽出：原先 `currentAbort` / `recoveryToken` 是模块级可变状态，
 * 既无法单测，也让「停止 / 切会话 / 重新发送」的中断语义散落在 store 各处。
 * 这里集中管理，store 只按语义调用 `begin / abort / newRecoveryToken`。
 */
export interface StreamSession {
  /** 开一条新流并登记为当前流；上次未释放的句柄会被覆盖（旧流由调用方自行结束） */
  begin(): AbortController;
  /** 流结束时释放登记；只在仍是当前流时生效，避免旧流覆盖新流 */
  release(controller: AbortController): void;
  /** 用户点「停止」：中断当前流并清空登记 */
  abort(): void;
  /** 该句柄是否仍是当前流 */
  isActive(controller: AbortController): boolean;
  /** 开始一轮断线恢复，返回本次恢复的代际标记 */
  newRecoveryToken(): number;
  /** 该代际是否仍然有效；切会话 / 新轮次后旧轮询据此退出 */
  isRecoveryCurrent(token: number): boolean;
  /** 作废所有进行中的恢复轮询，返回新的代际标记 */
  cancelRecovery(): number;
}

export function createStreamSession(): StreamSession {
  let active: AbortController | null = null;
  let recoveryGeneration = 0;

  return {
    begin() {
      active = new AbortController();
      return active;
    },
    release(controller) {
      if (active === controller) active = null;
    },
    abort() {
      active?.abort();
      active = null;
    },
    isActive(controller) {
      return active === controller;
    },
    newRecoveryToken() {
      return ++recoveryGeneration;
    },
    isRecoveryCurrent(token) {
      return token === recoveryGeneration;
    },
    cancelRecovery() {
      return ++recoveryGeneration;
    }
  };
}

/** 全局唯一的流会话：页面内同一时刻只允许一条聊天流 */
export const streamSession = createStreamSession();
