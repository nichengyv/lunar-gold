import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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

  it('进程重启中断且保留断点时给出「继续执行」', async () => {
    const chat = useChatStore();
    const resume = vi.spyOn(chat, 'resumeInterrupted').mockResolvedValue();
    const msg = message({
      failed: true,
      errorText: '上一轮被应用重启中断，已保留断点',
      resumableRunId: 42
    });
    const wrapper = mountMessage(msg);

    const btn = wrapper.findAll('.retry-btn').find((b) => b.text().includes('继续执行'));
    expect(btn).toBeTruthy();
    await btn!.trigger('click');
    expect(resume).toHaveBeenCalledWith(msg);
  });
});

describe('ChatMessage 本轮用量', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('在重新生成右侧显示本轮 token', () => {
    const wrapper = mountMessage(
      message({
        content: '完成',
        durationMs: 12_600,
        usage: { promptTokens: 1200, completionTokens: 800, totalTokens: 2000 }
      })
    );

    const usage = wrapper.find('.msg-usage');
    expect(usage.text()).toBe('2.0k token');
    expect(usage.attributes('title')).toBe('输入 1200 / 输出 800 token');
  });

  it('没有 usage 时不占位，耗时只留在执行摘要', () => {
    const withDuration = mountMessage(message({ content: '完成', durationMs: 3000 }));
    expect(withDuration.find('.msg-usage').exists()).toBe(false);

    const bare = mountMessage(message({ content: '完成' }));
    expect(bare.find('.msg-usage').exists()).toBe(false);
  });
});

describe('ChatMessage P5-09 工具运行耗时', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-15T10:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('运行中按秒刷新已耗时，结束后切回已完成', async () => {
    const running = message({
      streaming: true,
      tools: [
        {
          name: 'read_file',
          args: '{"path":"a.ts"}',
          status: 'running',
          startedAt: Date.now() - 4000
        } as never
      ]
    });
    const wrapper = mountMessage(running);

    expect(wrapper.find('.progress-text').text()).toContain('已用 4 秒');
    await wrapper.find('.progress-toggle').trigger('click');
    expect(wrapper.find('.step-state').text()).toBe('进行中 · 4 秒');

    vi.advanceTimersByTime(3000);
    await wrapper.vm.$nextTick();
    expect(wrapper.find('.progress-text').text()).toContain('已用 7 秒');
    expect(wrapper.find('.step-state').text()).toBe('进行中 · 7 秒');

    await wrapper.setProps({
      msg: message({
        streaming: true,
        tools: [
          { name: 'read_file', args: '{"path":"a.ts"}', status: 'success', costMs: 2500 } as never
        ]
      })
    });
    await wrapper.vm.$nextTick();
    expect(wrapper.find('.step-state').text()).toBe('已完成');
    expect(wrapper.find('.progress-text').text()).not.toContain('已用');
  });
});
