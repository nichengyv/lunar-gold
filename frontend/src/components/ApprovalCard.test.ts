import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import ApprovalCard from './ApprovalCard.vue';
import { useChatStore } from '../stores/chat';
import type { PendingApproval } from '../types/api';

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() }
}));

vi.mock('../api/agent', () => ({
  respondApproval: vi.fn(async () => undefined),
  stopGeneration: vi.fn(async () => undefined)
}));

const baseApproval: PendingApproval = {
  approvalId: 'ap-1',
  sessionId: 3,
  tool: 'write_file',
  risk: 'WRITE',
  preview: {
    kind: 'write',
    path: 'src/demo.ts',
    newFile: false,
    additions: 2,
    deletions: 1,
    diff: '@@ -1 +1 @@\n-old\n+new'
  },
  expiresAt: Date.now() + 60_000
} as PendingApproval;

function mountCard(approval: PendingApproval = baseApproval, shortcutActive?: boolean) {
  return mount(ApprovalCard, {
    props: { approval, shortcutActive },
    global: { stubs: { Icon: true } }
  });
}

describe('ApprovalCard', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('渲染风险、文件名与操作按钮', () => {
    const wrapper = mountCard();
    expect(wrapper.text()).toContain('需要你确认');
    expect(wrapper.text()).toContain('写入文件');
    expect(wrapper.find('.file-name').text()).toBe('demo.ts');
    expect(wrapper.findAll('.approval-btn').map((b) => b.text())).toEqual([
      '拒绝D',
      '本会话总是允许',
      '允许A'
    ]);
  });

  it('点击允许与拒绝提交对应决策', async () => {
    const chat = useChatStore();
    const spy = vi.spyOn(chat, 'respondApproval').mockResolvedValue();
    const wrapper = mountCard();
    const buttons = wrapper.findAll('.approval-btn');

    await buttons[0].trigger('click');
    expect(spy).toHaveBeenLastCalledWith('ap-1', 'REJECT', false);

    await buttons[2].trigger('click');
    expect(spy).toHaveBeenLastCalledWith('ap-1', 'APPROVE', false);
  });

  it('已处理状态不渲染操作按钮并显示结果', () => {
    const wrapper = mountCard({ ...baseApproval, resolved: 'APPROVE' });
    expect(wrapper.text()).toContain('已批准');
    expect(wrapper.findAll('.approval-btn')).toHaveLength(1);
    expect(wrapper.find('.approval-btn').attributes('disabled')).toBeDefined();
    expect(wrapper.find('.approval-timer').exists()).toBe(false);
  });

  it('倒计时到 0 自动关闭卡片', () => {
    vi.useFakeTimers();
    try {
      const chat = useChatStore();
      const spy = vi.spyOn(chat, 'dismissApproval');
      mountCard({ ...baseApproval, expiresAt: Date.now() + 1500 });
      vi.advanceTimersByTime(2500);
      expect(spy).toHaveBeenCalledWith('ap-1');
    } finally {
      vi.useRealTimers();
    }
  });

  it('快捷键 A / D 触发允许与拒绝', () => {
    const chat = useChatStore();
    const spy = vi.spyOn(chat, 'respondApproval').mockResolvedValue();
    mountCard();

    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }));
    expect(spy).toHaveBeenLastCalledWith('ap-1', 'APPROVE', false);

    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'd', bubbles: true }));
    expect(spy).toHaveBeenLastCalledWith('ap-1', 'REJECT', false);
  });

  it('非首张卡片不响应快捷键', () => {
    const chat = useChatStore();
    const spy = vi.spyOn(chat, 'respondApproval').mockResolvedValue();
    mountCard(baseApproval, false);
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }));
    expect(spy).not.toHaveBeenCalled();
  });

  it('P5-10-2：当前选中项显示选中态，点击卡片通知选中', async () => {
    const wrapper = mount(ApprovalCard, {
      props: { approval: baseApproval, shortcutActive: true, selected: true },
      global: { stubs: { Icon: true } }
    });
    const card = wrapper.find('.approval-card');
    expect(card.classes()).toContain('selected');

    await card.trigger('click');
    expect(wrapper.emitted('select')).toHaveLength(1);
  });

  it('P5-10-2：已处理卡片不显示选中态', () => {
    const wrapper = mount(ApprovalCard, {
      props: { approval: { ...baseApproval, resolved: 'APPROVE' }, selected: true },
      global: { stubs: { Icon: true } }
    });
    expect(wrapper.find('.approval-card').classes()).not.toContain('selected');
  });
});
