import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import AskUserCard from './AskUserCard.vue';
import { useChatStore } from '../stores/chat';
import type { UserQuestion } from '../types/chat';

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() }
}));

vi.mock('../api/agent', () => ({
  answerUserQuestion: vi.fn(async () => ({ runId: 7, questionId: 'scope', state: 'RUNNING' })),
  stopGeneration: vi.fn(async () => undefined)
}));

const withOptions: UserQuestion & { runId: number } = {
  runId: 7,
  questionId: 'scope',
  header: '改动范围',
  question: '这次改接口还是加包装？',
  multiSelect: false,
  options: [
    { label: '改接口定义 (Recommended)', description: '同步更新所有调用方' },
    { label: '加 wrapper', description: '不侵入现有接口' }
  ]
};

const freeText: UserQuestion & { runId: number } = {
  runId: 8,
  questionId: 'name',
  question: '新文件叫什么？',
  multiSelect: false
};

function mountCard(question: UserQuestion & { runId: number }) {
  return mount(AskUserCard, { props: { question } });
}

describe('AskUserCard', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('渲染标题、问题与选项', () => {
    const wrapper = mountCard(withOptions);
    expect(wrapper.text()).toContain('改动范围');
    expect(wrapper.text()).toContain('这次改接口还是加包装？');
    expect(wrapper.findAll('.userq-option')).toHaveLength(2);
    expect(wrapper.find('.userq-option').classes()).toContain('recommended');
  });

  it('单选：点第二个选项后提交对应答案', async () => {
    const chat = useChatStore();
    const spy = vi.spyOn(chat, 'answerUserQuestion').mockResolvedValue();
    const wrapper = mountCard(withOptions);

    await wrapper.findAll('.userq-option')[1].trigger('click');
    await wrapper.find('.userq-submit').trigger('click');

    expect(spy).toHaveBeenCalledWith(7, 'scope', ['加 wrapper'], undefined);
  });

  it('单选：再点同一项可取消选中，提交时回退到默认推荐项', async () => {
    const chat = useChatStore();
    const spy = vi.spyOn(chat, 'answerUserQuestion').mockResolvedValue();
    const wrapper = mountCard(withOptions);
    const first = wrapper.findAll('.userq-option')[0];

    await first.trigger('click');
    expect(first.classes()).toContain('selected');
    await first.trigger('click');
    expect(first.classes()).not.toContain('selected');
    expect(wrapper.find('.userq-submit').attributes('disabled')).toBeUndefined();

    await wrapper.find('.userq-submit').trigger('click');
    expect(spy).toHaveBeenCalledWith(7, 'scope', ['改接口定义 (Recommended)'], undefined);
  });

  it('多选：可同时选中多个选项', async () => {
    const chat = useChatStore();
    const spy = vi.spyOn(chat, 'answerUserQuestion').mockResolvedValue();
    const wrapper = mountCard({ ...withOptions, multiSelect: true });

    const opts = wrapper.findAll('.userq-option');
    await opts[0].trigger('click');
    await opts[1].trigger('click');
    await wrapper.find('.userq-submit').trigger('click');

    expect(spy).toHaveBeenCalledWith(7, 'scope', ['改接口定义 (Recommended)', '加 wrapper'], undefined);
  });

  it('无选项时用自由输入提交 custom', async () => {
    const chat = useChatStore();
    const spy = vi.spyOn(chat, 'answerUserQuestion').mockResolvedValue();
    const wrapper = mountCard(freeText);

    const textarea = wrapper.find('.userq-textarea');
    expect(textarea.exists()).toBe(true);
    await textarea.setValue('index.ts');
    await wrapper.find('.userq-submit').trigger('click');

    expect(spy).toHaveBeenCalledWith(8, 'name', [], 'index.ts');
  });

  it('提交后按钮禁用，防止重复回答', async () => {
    const chat = useChatStore();
    const spy = vi.spyOn(chat, 'answerUserQuestion').mockResolvedValue();
    const wrapper = mountCard(withOptions);

    await wrapper.findAll('.userq-option')[0].trigger('click');
    await wrapper.find('.userq-submit').trigger('click');
    await wrapper.find('.userq-submit').trigger('click');

    expect(spy).toHaveBeenCalledTimes(1);
    expect(wrapper.find('.userq-submit').attributes('disabled')).toBeDefined();
  });

  it('提交失败时恢复可提交状态', async () => {
    const chat = useChatStore();
    vi.spyOn(chat, 'answerUserQuestion').mockRejectedValue(new Error('network'));
    const wrapper = mountCard(withOptions);

    await wrapper.findAll('.userq-option')[0].trigger('click');
    await wrapper.find('.userq-submit').trigger('click');
    await Promise.resolve();

    expect(wrapper.find('.userq-submit').attributes('disabled')).toBeUndefined();
  });
});
