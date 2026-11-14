import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { UiMessage } from '../types/api';
import { useChatStore } from '../stores/chat';
import ChatMessage from './ChatMessage.vue';

const routerPush = vi.hoisted(() => vi.fn());

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush })
}));

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() }
}));

function mountMessage(msg: UiMessage) {
  return mount(ChatMessage, {
    props: { msg },
    global: { stubs: { Icon: true, MarkdownContent: true } }
  });
}

function message(overrides: Partial<UiMessage>): UiMessage {
  return {
    id: 1,
    role: 'assistant',
    content: '',
    tools: [],
    streaming: false,
    ...overrides
  } as UiMessage;
}

describe('ChatMessage P5-10-3', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    routerPush.mockReset();
  });

  it('停止后显示「已停止生成」和「继续」', async () => {
    const chat = useChatStore();
    const spy = vi.spyOn(chat, 'continueTask').mockResolvedValue();
    const wrapper = mountMessage(message({ id: 5, content: '半截', stopped: true }));

    expect(wrapper.text()).toContain('已停止生成');
    const btn = wrapper.find('.continue-btn');
    expect(btn.text()).toBe('继续');
    await btn.trigger('click');
    expect(spy).toHaveBeenCalledWith(5);
  });

  it('配置类失败给出「需配置」和「去设置」', async () => {
    const wrapper = mountMessage(
      message({ failed: true, errorText: '401 Unauthorized: API Key 未配置' })
    );

    expect(wrapper.find('.failure-tag').text()).toBe('需配置');
    expect(wrapper.findAll('.retry-btn').map((b) => b.text())).toEqual(['去设置', '重试这一轮']);
    await wrapper.find('.retry-btn').trigger('click');
    expect(routerPush).toHaveBeenCalledWith({ path: '/settings', query: { tab: 'model' } });
  });

  it('审批拒绝归为「需审批」并允许重新发起', () => {
    const wrapper = mountMessage(
      message({
        failed: true,
        errorText: '审批被拒绝',
        completion: { status: 'INCOMPLETE', reason: 'APPROVAL_REJECTED' } as never
      })
    );

    expect(wrapper.find('.failure-tag').text()).toBe('需审批');
    expect(wrapper.find('.failed-hint').text()).toContain('重新发起会再次请求审批');
  });

  it('权限类失败归为「需人工」并可重新加载', async () => {
    const chat = useChatStore();
    const reload = vi.spyOn(chat, 'reloadCurrentSession').mockResolvedValue();
    const wrapper = mountMessage(message({ failed: true, errorText: '文件写入失败：权限不足' }));

    expect(wrapper.find('.failure-tag').text()).toBe('需人工');
    expect(wrapper.find('.retry-btn').text()).toBe('重新加载');
    await wrapper.find('.retry-btn').trigger('click');
    expect(reload).toHaveBeenCalled();
  });
});
