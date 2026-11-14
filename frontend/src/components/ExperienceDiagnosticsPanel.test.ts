import ElementPlus, { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as agentApi from '../api/agent';
import type { AgentExperienceMetrics } from '../types/api';
import {
  getExperienceDiagnostics,
  isExperienceDiagnosticsEnabled,
  recordExperienceError,
  setExperienceDiagnosticsEnabled,
  setExperienceSessionId
} from '../utils/experienceDiagnostics';
import ExperienceDiagnosticsPanel from './ExperienceDiagnosticsPanel.vue';

vi.mock('web-vitals', () => ({ onLCP: vi.fn(), onINP: vi.fn(), onCLS: vi.fn() }));
vi.mock('../api/agent', () => ({ getExperienceMetrics: vi.fn() }));
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>();
  return { ...actual, ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } };
});

const metrics: AgentExperienceMetrics = {
  generatedAt: '2026-11-14T12:00:00Z',
  windowRuns: 20,
  firstToken: { p95Ms: 1900, sampleCount: 20 },
  tool: { failureRate: 0.25, totalCount: 8, failedCount: 2, scope: 'PROCESS' },
  approval: { averageWaitMs: 2000, sampleCount: 3 },
  tokens: { averageTotalTokens: 150, totalTokens: 300, sampleCount: 2 }
};

function mountPanel() {
  return mount(ExperienceDiagnosticsPanel, {
    global: { plugins: [createPinia(), ElementPlus], stubs: { Icon: true } }
  });
}

describe('ExperienceDiagnosticsPanel', () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    setExperienceSessionId(null);
    vi.mocked(agentApi.getExperienceMetrics).mockResolvedValue(metrics);
  });

  it('展示后端指标、样本窗口和本地脱敏异常', async () => {
    setExperienceSessionId(7);
    recordExperienceError('vue', 'token=secret-value D:\\private\\demo.ts');
    const wrapper = mountPanel();
    await flushPromises();

    expect(wrapper.text()).toContain('1,900 ms');
    expect(wrapper.text()).toContain('25.0%');
    expect(wrapper.text()).toContain('2,000 ms');
    expect(wrapper.text()).toContain('150');
    expect(wrapper.text()).toContain('20 个 Run');
    expect(wrapper.text()).toContain('进程累计');
    expect(wrapper.text()).toContain('Vue 渲染');
    expect(wrapper.text()).toContain('会话 #7');
    expect(wrapper.text()).toContain('token=<REDACTED>');
    expect(wrapper.text()).not.toContain('secret-value');
    expect(wrapper.text()).not.toContain('D:\\private');
  });

  it('后端失败不掩盖本地异常，并可独立重试', async () => {
    recordExperienceError('error', 'local-only-error');
    vi.mocked(agentApi.getExperienceMetrics).mockRejectedValueOnce(new Error('offline'));
    const wrapper = mountPanel();
    await flushPromises();

    expect(wrapper.text()).toContain('后端指标加载失败，本地诊断仍可使用');
    expect(wrapper.text()).toContain('local-only-error');
    await wrapper.find('.retry-link').trigger('click');
    await flushPromises();
    expect(wrapper.text()).not.toContain('后端指标加载失败，本地诊断仍可使用');
    expect(wrapper.text()).toContain('1,900 ms');
  });

  it('无样本显示占位符而不是伪造的 0', async () => {
    vi.mocked(agentApi.getExperienceMetrics).mockResolvedValue({
      generatedAt: '2026-11-14T12:00:00Z',
      windowRuns: 0,
      firstToken: { p95Ms: null, sampleCount: 0 },
      tool: { failureRate: null, totalCount: 0, failedCount: 0, scope: 'PROCESS' },
      approval: { averageWaitMs: null, sampleCount: 0 },
      tokens: { averageTotalTokens: null, totalTokens: 0, sampleCount: 0 }
    });
    const wrapper = mountPanel();
    await flushPromises();

    expect(wrapper.find('[data-testid="diagnostics-errors"]').text()).toContain('暂无本地异常记录');
    const values = wrapper.findAll('.metric-value').map((node) => node.text());
    expect(values).toContain('—');
    expect(wrapper.text()).not.toContain('0.0%');
    expect(wrapper.text()).not.toContain('0 ms');
  });

  it('关闭开关后停止新采集且保留已有记录，重新开启恢复采集', async () => {
    recordExperienceError('error', 'existing');
    const wrapper = mountPanel();
    await flushPromises();
    await wrapper.find('[role="switch"]').trigger('click');

    expect(isExperienceDiagnosticsEnabled()).toBe(false);
    recordExperienceError('error', 'disabled');
    expect(getExperienceDiagnostics().errors.map((item) => item.message)).toEqual(['existing']);
    expect(wrapper.text()).toContain('已停止新采集');

    await wrapper.find('[role="switch"]').trigger('click');
    expect(isExperienceDiagnosticsEnabled()).toBe(true);
    recordExperienceError('error', 'enabled-again');
    expect(getExperienceDiagnostics().errors).toHaveLength(2);
  });

  it('关闭时不主动请求后端，但允许手动刷新', async () => {
    setExperienceDiagnosticsEnabled(false);
    const wrapper = mountPanel();
    await flushPromises();
    expect(agentApi.getExperienceMetrics).not.toHaveBeenCalled();

    await wrapper.find('.diag-btn.compact').trigger('click');
    await flushPromises();
    expect(agentApi.getExperienceMetrics).toHaveBeenCalledTimes(1);
  });

  it('清除记录和手动导出均只影响本地白名单数据', async () => {
    const createObjectURL = vi.fn((_blob: Blob) => 'blob:diagnostics');
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    recordExperienceError('error', 'token=export-secret');
    const wrapper = mountPanel();
    await flushPromises();
    await wrapper.find('[data-testid="diagnostics-clear"]').trigger('click');
    expect(getExperienceDiagnostics().errors).toEqual([]);

    recordExperienceError('error', 'token=export-secret');
    await wrapper.find('[data-testid="diagnostics-export"]').trigger('click');
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    const blob = createObjectURL.mock.calls[0][0];
    const exported = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(reader.error);
      reader.readAsText(blob);
    });
    expect(exported).toContain('token=<REDACTED>');
    expect(exported).not.toContain('export-secret');
    expect(exported).toContain('"windowRuns": 20');
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:diagnostics');
    expect(ElMessage.success).toHaveBeenCalledWith('诊断 JSON 已导出');
  });
});
