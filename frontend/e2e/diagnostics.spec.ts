import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures/api';

const experienceMetrics = {
  generatedAt: '2026-11-14T12:00:00Z',
  windowRuns: 20,
  firstToken: { p95Ms: 1900, sampleCount: 20 },
  tool: { failureRate: 0.25, totalCount: 8, failedCount: 2, scope: 'PROCESS' },
  approval: { averageWaitMs: 2000, sampleCount: 3 },
  tokens: { averageTotalTokens: 150, totalTokens: 300, sampleCount: 2 }
};

test('体验诊断展示白名单异常与后端指标，支持停用、导出和清除', async ({ page }) => {
  await mockApi(page, { experienceMetrics });
  await page.goto('/settings');
  await page.locator('.settings-nav').getByRole('button', { name: '体验诊断' }).click();

  const backend = page.getByTestId('agent-experience-metrics');
  await expect(backend).toContainText('1,900 ms');
  await expect(backend).toContainText('25.0%');
  await expect(backend).toContainText('2,000 ms');
  await expect(backend).toContainText('150');

  await page.evaluate(() => {
    window.dispatchEvent(new ErrorEvent('error', {
      message: 'window token=e2e-secret D:\\private\\demo.ts'
    }));
    window.dispatchEvent(new PromiseRejectionEvent('unhandledrejection', {
      promise: Promise.resolve(),
      reason: new Error('rejection api_key=e2e-secret')
    }));
  });

  const errors = page.getByTestId('diagnostics-errors');
  await expect(errors).toContainText('浏览器异常');
  await expect(errors).toContainText('Promise 拒绝');
  await expect(errors).toContainText('token=<REDACTED>');
  await expect(errors).toContainText('api_key=<REDACTED>');
  await expect(errors).not.toContainText('e2e-secret');
  await expect(errors).not.toContainText('D:\\private');

  const downloadPromise = page.waitForEvent('download');
  await page.getByTestId('diagnostics-export').click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/^experience-diagnostics-.*\.json$/);
  const exported = await readFile(await download.path() as string, 'utf8');
  const parsed = JSON.parse(exported);
  expect(parsed.records.errors).toHaveLength(2);
  expect(parsed.agentMetrics.firstToken.p95Ms).toBe(1900);
  expect(exported).not.toContain('e2e-secret');
  expect(exported).not.toContain('D:\\private');
  expect(exported).not.toContain('stack');

  await page.getByTestId('diagnostics-enabled').click();
  await expect(page.getByText('已停止新采集，已有记录仍可查看')).toBeVisible();
  await page.evaluate(() => {
    window.dispatchEvent(new ErrorEvent('error', { message: 'disabled-new-error' }));
    window.dispatchEvent(new PromiseRejectionEvent('unhandledrejection', {
      promise: Promise.resolve(),
      reason: 'disabled-new-rejection'
    }));
  });
  await expect(errors).not.toContainText('disabled-new-error');
  await expect(errors).not.toContainText('disabled-new-rejection');
  const stored = await page.evaluate(() => JSON.parse(
    localStorage.getItem('agent.experience-diagnostics.v1') || '{}'
  ));
  expect(stored.errors).toHaveLength(2);

  await page.getByTestId('diagnostics-clear').click();
  await expect(errors).toContainText('暂无本地异常记录');
  const cleared = await page.evaluate(() => JSON.parse(
    localStorage.getItem('agent.experience-diagnostics.v1') || '{}'
  ));
  expect(cleared.errors).toEqual([]);
});
