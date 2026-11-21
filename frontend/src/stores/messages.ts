/**
 * R6c：会话消息域 store。
 *
 * <p>承载一个会话的可见消息数组、历史加载状态与已发送附件的 UI 缓存。
 * 消息数组的增删与 trace 还原放在这里；断线恢复、发送编排仍由 chat facade
 * 调度，避免 store 之间出现循环依赖。视图继续通过 `useChatStore` 的兼容
 * getter 读取 `messages / messagesLoading / loadError / sessionAttachments`。
 */
import { defineStore } from 'pinia';
import { reactive } from 'vue';
import type { HistoryMessage, MessageTrace, UiMessage } from '../types/api';

/** 已发送消息中关联的附件（用于会话内恢复 UI） */
export interface SessionAttachment {
  id: number;
  fileName: string;
}

let seq = 0;
const nextId = () => ++seq;

/** 解析后端存的轨迹 JSON；损坏或缺失都当作没有轨迹，不能让历史加载失败 */
export function parseTrace(raw?: string): MessageTrace | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as MessageTrace;
  } catch {
    return null;
  }
}

/** 后端历史消息 → 可展示消息；思考/工具/来源/改动从 trace 还原 */
export function toUiMessage(m: HistoryMessage): UiMessage {
  const trace = parseTrace(m.trace);
  return {
    id: nextId(),
    sourceId: m.id,
    role: m.role === 'USER' ? 'user' : 'assistant',
    content: m.content,
    thinking: trace?.thinking,
    tools: trace?.tools ?? [],
    sources: trace?.sources,
    changes: trace?.changes,
    plan: trace?.plan,
    turnId: trace?.turnId,
    durationMs: trace?.durationMs,
    completion: trace?.completion,
    diagnostics: trace?.diagnostics,
    verify: trace?.verify
  };
}

export const useMessagesStore = defineStore('chat-messages', {
  state: () => ({
    messages: [] as UiMessage[],
    /** P5-09：会话历史加载中，用于骨架屏 */
    messagesLoading: false,
    /** P5-09：会话历史加载失败原因，非空时展示统一错误态 */
    loadError: '',
    // sessionId -> 已随消息发送的附件（仅前端内存，用于切回会话时恢复 chips）
    sessionAttachments: {} as Record<number, SessionAttachment[]>
  }),

  actions: {
    /** 新会话 / 切项目：清空消息与上一轮的加载错误；附件缓存由调用方决定是否清 */
    reset() {
      this.messages = [];
      this.loadError = '';
    },

    /**
     * 还原一轮历史。
     * 后端只在本轮完整结束时才存助手回答：历史停在用户消息上，说明那一轮被刷新/断线/停止打断了。
     * 这里补一个助手气泡并返回，交由调用方尝试按 runId 恢复。
     */
    setHistory(history: HistoryMessage[]): UiMessage | null {
      this.messages = history.map(toUiMessage);
      const last = this.messages[this.messages.length - 1];
      if (!last || last.role !== 'user') return null;
      // 必须是响应式对象：后续恢复轮询会持续改 content / streaming
      const bubble = reactive<UiMessage>({
        id: nextId(),
        role: 'assistant',
        content: '',
        thinking: '',
        tools: []
      });
      this.messages.push(bubble);
      return bubble as UiMessage;
    },

    appendUser(content: string) {
      this.messages.push({ id: nextId(), role: 'user', content, tools: [] });
    },

    appendHint(content: string) {
      this.messages.push({ id: nextId(), role: 'hint', content, tools: [] });
    },

    /** 新建本轮助手气泡并挂到消息列表，返回响应式引用供流式写入 */
    beginAssistant(): UiMessage {
      const msg = reactive<UiMessage>({
        id: nextId(),
        role: 'assistant',
        content: '',
        thinking: '',
        tools: [],
        streaming: true
      });
      this.messages.push(msg);
      return msg as UiMessage;
    },

    /** 截掉 index 及其之后的消息（重新生成） */
    truncateFrom(index: number) {
      if (index < 0) return;
      this.messages = this.messages.slice(0, index);
    },

    mergeSessionAttachments(sessionId: number, attachments: SessionAttachment[]) {
      const existing = this.sessionAttachments[sessionId] || [];
      const map = new Map<number, SessionAttachment>();
      [...existing, ...attachments].forEach((a) => map.set(a.id, a));
      this.sessionAttachments[sessionId] = [...map.values()];
    },

    clearAttachments() {
      this.sessionAttachments = {};
    }
  }
});
