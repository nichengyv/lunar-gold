import type { App } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  beginExperienceRecovery,
  clearExperienceDiagnostics,
  createExperienceDiagnosticsExport,
  discardExperienceRecovery,
  finishExperienceRecovery,
  getExperienceConnectionMetrics,
  getExperienceDiagnostics,
  getExperienceWebVitals,
  initializeExperienceDiagnostics,
  isExperienceDiagnosticsEnabled,
  markExperienceRun,
  recordExperienceError,
  recordExperienceWebVital,
  sanitizeExperienceMessage,
  setExperienceDiagnosticsAccount,
  setExperienceDiagnosticsEnabled,
  setExperienceSessionId
} from './experienceDiagnostics';

vi.mock('web-vitals', () => ({
  onLCP: vi.fn(),
  onINP: vi.fn(),
  onCLS: vi.fn()
}));

describe('experienceDiagnostics', () => {
  beforeEach(() => {
    localStorage.clear();
    setExperienceSessionId(null);
  });

  afterEach(() => {
    setExperienceDiagnosticsEnabled(false);
  });

  it('默认开启，关闭后所有本地采样停止且已有记录保留', () => {
    expect(isExperienceDiagnosticsEnabled()).toBe(true);
    recordExperienceError('error', 'before');
    setExperienceDiagnosticsEnabled(false);

    recordExperienceError('vue', 'after');
    recordExperienceWebVital('LCP', 1200);
    markExperienceRun(1, 1);
    beginExperienceRecovery(1, 1, 99);
    finishExperienceRecovery(99, 'failed', 2);

    const data = getExperienceDiagnostics();
    expect(data.errors.map((item) => item.message)).toEqual(['before']);
    expect(data.vitals).toEqual([]);
    expect(data.runs).toEqual([]);
    expect(data.recoveries).toEqual([]);

    setExperienceDiagnosticsEnabled(true);
    recordExperienceError('vue', 're-enabled');
    expect(getExperienceDiagnostics().errors).toHaveLength(2);
  });

  it('脱敏密钥、路径、查询串和对话字段，只保留单行摘要', () => {
    const message = sanitizeExperienceMessage(new Error(
      'token=secret-value Bearer abc.def.ghi D:\\work\\private\\a.ts /home/me/a.ts '
      + 'https://example.test/path?q=secret prompt="private conversation"\nnext line'
    ));
    expect(message).not.toContain('secret-value');
    expect(message).not.toContain('abc.def.ghi');
    expect(message).not.toContain('D:\\work');
    expect(message).not.toContain('/home/me');
    expect(message).not.toContain('q=secret');
    expect(message).not.toContain('private conversation');
    expect(message).toContain('<REDACTED>');
    expect(message).toContain('<PATH>');
    expect(message).not.toContain('\n');
    expect(sanitizeExperienceMessage('x'.repeat(2100))).toHaveLength(2000);
  });

  it('按白名单和容量裁剪记录，Run 按 runId 去重', () => {
    for (let index = 0; index < 55; index += 1) recordExperienceError('error', `error-${index}`);
    for (let index = 0; index < 185; index += 1) recordExperienceWebVital('LCP', index);
    for (let index = 1; index <= 110; index += 1) markExperienceRun(index, 1);
    markExperienceRun(110, 1);

    const data = getExperienceDiagnostics();
    expect(data.errors).toHaveLength(50);
    expect(data.errors[0].message).toBe('error-5');
    expect(data.vitals).toHaveLength(180);
    expect(data.vitals[0].value).toBe(5);
    expect(data.runs).toHaveLength(100);
    expect(data.runs[0].runId).toBe(11);
    expect(data.runs.filter((run) => run.runId === 110)).toHaveLength(1);
    expect(Object.keys(data.errors[0]).sort()).toEqual(
      ['id', 'timestamp', 'kind', 'message', 'route', 'sessionId'].sort()
    );
  });

  it('按真实恢复终态计算中断率、成功率和平均耗时', () => {
    markExperienceRun(1, 1);
    markExperienceRun(2, 1);
    markExperienceRun(3, 1);
    beginExperienceRecovery(1, 1, 1);
    finishExperienceRecovery(1, 'recovered', 1);
    beginExperienceRecovery(1, 1, 2);
    finishExperienceRecovery(2, 'failed', 3);
    beginExperienceRecovery(2, 1, 3);
    finishExperienceRecovery(3, 'recovered', 2);

    const metrics = getExperienceConnectionMetrics();
    expect(metrics.runCount).toBe(3);
    expect(metrics.interruptedRunCount).toBe(2);
    expect(metrics.interruptionRate).toBeCloseTo(2 / 3);
    expect(metrics.recoveryAttempts).toBe(3);
    expect(metrics.recoverySuccessRate).toBeCloseTo(2 / 3);
    expect(metrics.averageRecoveryMs).not.toBeNull();
    expect(getExperienceDiagnostics().recoveries.map((item) => item.pollAttempts)).toEqual([1, 3, 2]);
  });

  it('被取代的恢复不写失败记录，空样本返回 null', () => {
    expect(getExperienceConnectionMetrics()).toMatchObject({
      interruptionRate: null,
      recoverySuccessRate: null,
      averageRecoveryMs: null
    });
    beginExperienceRecovery(9, 1, 20);
    discardExperienceRecovery(20);
    finishExperienceRecovery(20, 'failed', 1);
    expect(getExperienceDiagnostics().recoveries).toEqual([]);
  });

  it('Web Vitals 展示最新值和样本数，并可按会话筛选', () => {
    setExperienceSessionId(1);
    recordExperienceWebVital('LCP', 1000);
    recordExperienceWebVital('CLS', 0.01);
    setExperienceSessionId(2);
    recordExperienceWebVital('LCP', 1600);

    expect(getExperienceWebVitals().LCP).toMatchObject({ value: 1600, sampleCount: 2 });
    expect(getExperienceWebVitals(1).LCP).toMatchObject({ value: 1000, sampleCount: 1 });
    expect(getExperienceWebVitals(1).CLS).toMatchObject({ value: 0.01, sampleCount: 1 });
    expect(getExperienceWebVitals(1).INP).toMatchObject({ value: null, sampleCount: 0 });
  });

  it('损坏 JSON 与存储写满均静默降级', () => {
    localStorage.setItem('agent.experience-diagnostics.v1', '{broken');
    expect(getExperienceDiagnostics()).toMatchObject({ errors: [], vitals: [] });
    expect(() => recordExperienceError('error', 'still works')).not.toThrow();

    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });
    expect(() => recordExperienceError('error', 'no persistence')).not.toThrow();
  });

  it('账号切换清理本地标识，同账号不会误清', () => {
    setExperienceDiagnosticsAccount('alice');
    markExperienceRun(42, 7);
    setExperienceDiagnosticsAccount('alice');
    expect(getExperienceDiagnostics().runs).toHaveLength(1);
    setExperienceDiagnosticsAccount('bob');
    expect(getExperienceDiagnostics().runs).toEqual([]);
  });

  it('导出仅含本地白名单记录和当前后端快照', () => {
    setExperienceDiagnosticsAccount('alice');
    setExperienceSessionId(7);
    recordExperienceError('unhandledrejection', 'token=abc123 secret message');
    const exported = createExperienceDiagnosticsExport(null);
    const json = JSON.stringify(exported);
    expect(json).not.toContain('abc123');
    expect(json).not.toContain('alice');
    expect(json).not.toContain('stack');
    expect(exported.records.errors[0].kind).toBe('unhandledrejection');
    expect(exported.agentMetrics).toBeNull();
  });

  it('初始化捕获 Vue 和 window 异常，关闭后移除 window 监听', () => {
    const app = { config: {} } as App;
    initializeExperienceDiagnostics(app);
    app.config.errorHandler?.(new Error('vue failure'), null, 'render');
    window.dispatchEvent(new ErrorEvent('error', { message: 'window failure' }));
    expect(getExperienceDiagnostics().errors.map((item) => item.kind)).toEqual(['vue', 'error']);

    setExperienceDiagnosticsEnabled(false);
    window.dispatchEvent(new ErrorEvent('error', { message: 'disabled failure' }));
    expect(getExperienceDiagnostics().errors).toHaveLength(2);
  });

  it('清除只删除诊断记录，不改变采集开关', () => {
    setExperienceDiagnosticsEnabled(false);
    clearExperienceDiagnostics();
    expect(isExperienceDiagnosticsEnabled()).toBe(false);
    setExperienceDiagnosticsEnabled(true);
    recordExperienceError('error', 'to clear');
    clearExperienceDiagnostics();
    expect(getExperienceDiagnostics().errors).toEqual([]);
    expect(isExperienceDiagnosticsEnabled()).toBe(true);
  });
});
