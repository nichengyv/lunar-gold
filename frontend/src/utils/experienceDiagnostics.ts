import type { App } from 'vue';
import { onCLS, onINP, onLCP } from 'web-vitals';
import type { AgentExperienceMetrics } from '../types/api';

const STORAGE_KEY = 'agent.experience-diagnostics.v1';
const ENABLED_KEY = 'agent.experience-diagnostics.enabled.v1';
const ACCOUNT_KEY = 'agent.experience-diagnostics.account.v1';

const LIMITS = {
  errors: 50,
  vitals: 180,
  runs: 100,
  recoveries: 100
} as const;

export type ExperienceErrorKind = 'vue' | 'error' | 'unhandledrejection';
export type WebVitalName = 'LCP' | 'INP' | 'CLS';
export type RecoveryOutcome = 'recovered' | 'failed';

export interface ExperienceErrorRecord {
  id: string;
  timestamp: string;
  kind: ExperienceErrorKind;
  message: string;
  route: string;
  sessionId: number | null;
}

export interface ExperienceWebVitalRecord {
  name: WebVitalName;
  value: number;
  timestamp: string;
  route: string;
  sessionId: number | null;
}

export interface ExperienceRunRecord {
  runId: number;
  sessionId: number | null;
  startedAt: string;
}

export interface ExperienceRecoveryRecord {
  runId: number;
  sessionId: number | null;
  startedAt: string;
  endedAt: string;
  durationMs: number;
  pollAttempts: number;
  outcome: RecoveryOutcome;
}

export interface ExperienceDiagnosticsData {
  version: 1;
  updatedAt: string | null;
  errors: ExperienceErrorRecord[];
  vitals: ExperienceWebVitalRecord[];
  runs: ExperienceRunRecord[];
  recoveries: ExperienceRecoveryRecord[];
}

export interface ExperienceConnectionMetrics {
  runCount: number;
  interruptedRunCount: number;
  interruptionRate: number | null;
  recoveryAttempts: number;
  recoveredCount: number;
  recoverySuccessRate: number | null;
  averageRecoveryMs: number | null;
}

export interface ExperienceWebVitalSummary {
  value: number | null;
  sampleCount: number;
  timestamp: string | null;
}

const emptyData = (): ExperienceDiagnosticsData => ({
  version: 1,
  updatedAt: null,
  errors: [],
  vitals: [],
  runs: [],
  recoveries: []
});

const listeners = new Set<() => void>();
const pendingRecoveries = new Map<number, { runId: number; sessionId: number | null; startedAt: number }>();
let currentSessionId: number | null = null;
let listening = false;
let webVitalsStarted = false;

function notify() {
  listeners.forEach((listener) => listener());
}

function safeGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeSet(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 隐私模式或容量写满时只停用持久化，不影响聊天与设置页。
  }
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function sessionNumber(value: unknown): number | null {
  const number = finiteNumber(value);
  return number != null && number > 0 ? number : null;
}

function timestamp(value: unknown): string | null {
  return typeof value === 'string' && value.length <= 64 && Number.isFinite(Date.parse(value)) ? value : null;
}

function cleanRoute(value?: unknown): string {
  const raw = typeof value === 'string'
    ? value
    : typeof window !== 'undefined'
      ? window.location.pathname
      : '';
  try {
    return new URL(raw, 'http://local.invalid').pathname.slice(0, 300) || '/';
  } catch {
    return '/';
  }
}

/** 只保留单行摘要；原始 Error、reason 和 stack 从不进入存储。 */
export function sanitizeExperienceMessage(input: unknown): string {
  const message = input instanceof Error ? input.message : String(input ?? '未知异常');
  return message
    .replace(/\u001b\[[0-9;]*m/g, '')
    .replace(/\b[A-Za-z]:[\\/][^\s<>"']+/g, '<PATH>')
    .replace(/(?:\/home|\/Users|\/tmp|\/var|\/opt|\/root|\/mnt)\/[^\s<>"']+/g, '<PATH>')
    .replace(/file:\/\/[^\s<>"']+/gi, '<PATH>')
    .replace(/(Bearer\s+)[A-Za-z0-9._~+\/-]+/gi, '$1<REDACTED>')
    .replace(/\b(?:sk|pk|key|token)-[A-Za-z0-9_-]{8,}\b/g, '<REDACTED>')
    .replace(/\b(?:ghp|github_pat|xox[baprs])_[A-Za-z0-9_-]+\b/g, '<REDACTED>')
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '<REDACTED>')
    .replace(
      /((?:["']?)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|aes[_-]?master[_-]?key|authorization|local[_-]?token)(?:["']?)\s*[:=]\s*)(["'][^"'\n]*["']|[^\s,;&]+)/gi,
      '$1<REDACTED>'
    )
    .replace(
      /((?:["']?)(?:prompt|conversation|chat_history|source_code|knowledge_base)(?:["']?)\s*[:=]\s*)(["'][^"'\n]*["']|[^\s,;&]+)/gi,
      '$1<REDACTED>'
    )
    .replace(/(https?:\/\/[^\s?]+)\?[^\s]+/gi, '$1?<REDACTED>')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 2000) || '未知异常';
}

function normalizeData(value: unknown): ExperienceDiagnosticsData {
  if (!value || typeof value !== 'object') return emptyData();
  const input = value as Partial<ExperienceDiagnosticsData>;
  const data = emptyData();
  data.updatedAt = timestamp(input.updatedAt);

  if (Array.isArray(input.errors)) {
    data.errors = input.errors.slice(-LIMITS.errors).flatMap((item) => {
      if (!item || typeof item !== 'object') return [];
      const time = timestamp(item.timestamp);
      if (!time || !['vue', 'error', 'unhandledrejection'].includes(item.kind)) return [];
      return [{
        id: typeof item.id === 'string' ? item.id.slice(0, 100) : `${time}-${Math.random().toString(36).slice(2, 10)}`,
        timestamp: time,
        kind: item.kind as ExperienceErrorKind,
        message: sanitizeExperienceMessage(item.message),
        route: cleanRoute(item.route),
        sessionId: sessionNumber(item.sessionId)
      }];
    });
  }

  if (Array.isArray(input.vitals)) {
    data.vitals = input.vitals.slice(-LIMITS.vitals).flatMap((item) => {
      if (!item || typeof item !== 'object') return [];
      const time = timestamp(item.timestamp);
      const number = finiteNumber(item.value);
      if (!time || number == null || !['LCP', 'INP', 'CLS'].includes(item.name)) return [];
      return [{
        name: item.name as WebVitalName,
        value: number,
        timestamp: time,
        route: cleanRoute(item.route),
        sessionId: sessionNumber(item.sessionId)
      }];
    });
  }

  if (Array.isArray(input.runs)) {
    data.runs = input.runs.slice(-LIMITS.runs).flatMap((item) => {
      if (!item || typeof item !== 'object') return [];
      const runId = finiteNumber(item.runId);
      const startedAt = timestamp(item.startedAt);
      return runId == null || !startedAt ? [] : [{
        runId,
        sessionId: sessionNumber(item.sessionId),
        startedAt
      }];
    });
  }

  if (Array.isArray(input.recoveries)) {
    data.recoveries = input.recoveries.slice(-LIMITS.recoveries).flatMap((item) => {
      if (!item || typeof item !== 'object') return [];
      const runId = finiteNumber(item.runId);
      const startedAt = timestamp(item.startedAt);
      const endedAt = timestamp(item.endedAt);
      const durationMs = finiteNumber(item.durationMs);
      const pollAttempts = finiteNumber(item.pollAttempts);
      if (runId == null || !startedAt || !endedAt || durationMs == null || pollAttempts == null
        || !['recovered', 'failed'].includes(item.outcome)) return [];
      return [{
        runId,
        sessionId: sessionNumber(item.sessionId),
        startedAt,
        endedAt,
        durationMs: Math.max(0, durationMs),
        pollAttempts: Math.max(0, Math.round(pollAttempts)),
        outcome: item.outcome as RecoveryOutcome
      }];
    });
  }
  return data;
}

export function getExperienceDiagnostics(): ExperienceDiagnosticsData {
  const raw = safeGet(STORAGE_KEY);
  if (!raw) return emptyData();
  try {
    return normalizeData(JSON.parse(raw));
  } catch {
    return emptyData();
  }
}

function saveExperienceDiagnostics(data: ExperienceDiagnosticsData) {
  data.updatedAt = new Date().toISOString();
  safeSet(STORAGE_KEY, JSON.stringify(data));
  notify();
}

function mutate(mutator: (data: ExperienceDiagnosticsData) => void) {
  if (!isExperienceDiagnosticsEnabled()) return;
  const data = getExperienceDiagnostics();
  mutator(data);
  saveExperienceDiagnostics(data);
}

export function isExperienceDiagnosticsEnabled(): boolean {
  return safeGet(ENABLED_KEY) !== 'false';
}

function onWindowError(event: ErrorEvent) {
  recordExperienceError('error', event.message || '窗口脚本异常');
}

function onUnhandledRejection(event: PromiseRejectionEvent) {
  recordExperienceError('unhandledrejection', event.reason);
}

function startWebVitals() {
  if (webVitalsStarted) return;
  webVitalsStarted = true;
  const report = (name: WebVitalName, value: number) => {
    if (isExperienceDiagnosticsEnabled()) recordExperienceWebVital(name, value);
  };
  try {
    onLCP((metric) => report('LCP', metric.value), { reportAllChanges: true });
    onINP((metric) => report('INP', metric.value), { reportAllChanges: true });
    onCLS((metric) => report('CLS', metric.value), { reportAllChanges: true });
  } catch {
    // 不支持 PerformanceObserver 的浏览器保持无样本态。
  }
}

function syncCollection() {
  if (typeof window === 'undefined') return;
  const enabled = isExperienceDiagnosticsEnabled();
  if (enabled && !listening) {
    window.addEventListener('error', onWindowError);
    window.addEventListener('unhandledrejection', onUnhandledRejection);
    listening = true;
  } else if (!enabled && listening) {
    window.removeEventListener('error', onWindowError);
    window.removeEventListener('unhandledrejection', onUnhandledRejection);
    listening = false;
  }
  // web-vitals 没有统一注销接口；禁用后回调仍在库内结束，但不会再进入本地存储。
  if (enabled) startWebVitals();
}

export function initializeExperienceDiagnostics(app: App) {
  app.config.errorHandler = (error) => recordExperienceError('vue', error);
  syncCollection();
}

export function setExperienceDiagnosticsEnabled(enabled: boolean) {
  safeSet(ENABLED_KEY, String(enabled));
  syncCollection();
  notify();
}

/** 同一账号保留记录；账号变化时先清掉 run/session 标识，再绑定新账号。 */
export function setExperienceDiagnosticsAccount(username: string) {
  const next = username.trim();
  if (!next) return;
  const previous = safeGet(ACCOUNT_KEY);
  if (previous && previous !== next) clearExperienceDiagnostics();
  safeSet(ACCOUNT_KEY, next.slice(0, 200));
}

export function setExperienceSessionId(sessionId: number | null) {
  currentSessionId = sessionNumber(sessionId);
}

function newId() {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}

export function recordExperienceError(kind: ExperienceErrorKind, error: unknown) {
  mutate((data) => {
    data.errors.push({
      id: newId(),
      timestamp: new Date().toISOString(),
      kind,
      message: sanitizeExperienceMessage(error),
      route: cleanRoute(),
      sessionId: currentSessionId
    });
    data.errors = data.errors.slice(-LIMITS.errors);
  });
}

export function recordExperienceWebVital(name: WebVitalName, value: number) {
  if (!Number.isFinite(value)) return;
  mutate((data) => {
    data.vitals.push({
      name,
      value,
      timestamp: new Date().toISOString(),
      route: cleanRoute(),
      sessionId: currentSessionId
    });
    data.vitals = data.vitals.slice(-LIMITS.vitals);
  });
}

export function markExperienceRun(runId: number, sessionId: number | null) {
  if (!Number.isFinite(runId)) return;
  setExperienceSessionId(sessionId);
  mutate((data) => {
    if (data.runs.some((run) => run.runId === runId)) return;
    data.runs.push({ runId, sessionId: sessionNumber(sessionId), startedAt: new Date().toISOString() });
    data.runs = data.runs.slice(-LIMITS.runs);
  });
}

export function beginExperienceRecovery(runId: number, sessionId: number | null, token: number) {
  pendingRecoveries.set(token, { runId, sessionId: sessionNumber(sessionId), startedAt: Date.now() });
  markExperienceRun(runId, sessionId);
}

export function finishExperienceRecovery(token: number, outcome: RecoveryOutcome, pollAttempts: number) {
  const pending = pendingRecoveries.get(token);
  if (!pending) return;
  pendingRecoveries.delete(token);
  mutate((data) => {
    const endedAt = Date.now();
    data.recoveries.push({
      runId: pending.runId,
      sessionId: pending.sessionId,
      startedAt: new Date(pending.startedAt).toISOString(),
      endedAt: new Date(endedAt).toISOString(),
      durationMs: Math.max(0, endedAt - pending.startedAt),
      pollAttempts: Math.max(0, Math.round(pollAttempts)),
      outcome
    });
    data.recoveries = data.recoveries.slice(-LIMITS.recoveries);
  });
}

export function discardExperienceRecovery(token: number) {
  pendingRecoveries.delete(token);
}

export function getExperienceConnectionMetrics(): ExperienceConnectionMetrics {
  const data = getExperienceDiagnostics();
  const localRunIds = new Set(data.runs.map((record) => record.runId));
  const interruptedRuns = new Set(
    data.recoveries.filter((record) => localRunIds.has(record.runId)).map((record) => record.runId)
  );
  const recoveredCount = data.recoveries.filter((record) => record.outcome === 'recovered').length;
  const recoveryAttempts = data.recoveries.length;
  return {
    runCount: data.runs.length,
    interruptedRunCount: interruptedRuns.size,
    interruptionRate: data.runs.length ? interruptedRuns.size / data.runs.length : null,
    recoveryAttempts,
    recoveredCount,
    recoverySuccessRate: recoveryAttempts ? recoveredCount / recoveryAttempts : null,
    averageRecoveryMs: recoveryAttempts
      ? data.recoveries.reduce((sum, record) => sum + record.durationMs, 0) / recoveryAttempts
      : null
  };
}

export function getExperienceWebVitals(sessionId?: number | null): Record<WebVitalName, ExperienceWebVitalSummary> {
  const records = getExperienceDiagnostics().vitals.filter((record) =>
    sessionId == null || record.sessionId === sessionId
  );
  const summary: Record<WebVitalName, ExperienceWebVitalSummary> = {
    LCP: { value: null, sampleCount: 0, timestamp: null },
    INP: { value: null, sampleCount: 0, timestamp: null },
    CLS: { value: null, sampleCount: 0, timestamp: null }
  };
  for (const name of ['LCP', 'INP', 'CLS'] as const) {
    const matches = records.filter((record) => record.name === name);
    const latest = matches[matches.length - 1];
    summary[name] = {
      value: latest?.value ?? null,
      sampleCount: matches.length,
      timestamp: latest?.timestamp ?? null
    };
  }
  return summary;
}

export function clearExperienceDiagnostics() {
  saveExperienceDiagnostics(emptyData());
}

export function createExperienceDiagnosticsExport(agentMetrics: AgentExperienceMetrics | null) {
  const data = getExperienceDiagnostics();
  return {
    schemaVersion: 1,
    exportedAt: new Date().toISOString(),
    enabled: isExperienceDiagnosticsEnabled(),
    records: {
      errors: data.errors,
      vitals: data.vitals,
      runs: data.runs,
      recoveries: data.recoveries
    },
    agentMetrics
  };
}

export function subscribeExperienceDiagnostics(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
