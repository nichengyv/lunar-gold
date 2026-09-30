/**
 * 会话管理逻辑
 */
import { api } from './api.js';
import * as ui from './ui.js';

/**
 * 加载并渲染会话列表
 * @param {number|null} activeSessionId
 * @param {string} keyword
 * @returns {Promise<Array>}
 */
export async function loadSessions(activeSessionId, keyword = '') {
    const result = await api.listSessions();
    if (result.code !== 200) return [];
    const sessions = result.data || [];
    ui.renderSessions(sessions, activeSessionId, keyword);
    return sessions;
}

/**
 * 删除会话
 * @param {number} sessionId
 */
export async function deleteSession(sessionId) {
    if (!confirm('确定删除该会话？')) return false;
    await api.deleteSession(sessionId);
    return true;
}

/**
 * 加载会话历史消息
 * @param {number} sessionId
 * @returns {Promise<Array>}
 */
export async function loadSessionHistory(sessionId) {
    const result = await api.getSessionMessages(sessionId);
    if (result.code === 200 && result.data) {
        return result.data;
    }
    return [];
}

/**
 * 清空所有会话
 * @returns {Promise<boolean>}
 */
export async function clearAllSessions() {
    if (!confirm('⚠️ 确定清空所有会话？此操作不可恢复！')) return false;
    const result = await api.listSessions();
    if (result.code === 200 && result.data) {
        for (const s of result.data) {
            await api.deleteSession(s.id);
        }
    }
    return true;
}