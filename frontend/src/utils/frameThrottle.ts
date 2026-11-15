/**
 * P5-11-3：把高频流式更新合并到每帧一次，并限制每秒提交次数。
 *
 * 典型用法：SSE 每个 chunk 只 push 最新全量文本，真正写响应式状态按帧合并；
 * 结束、失败或用户停止时必须 flush，保证最终内容不丢最后一段。
 */
export interface FrameThrottle<T> {
  /** 记录最新值，同一帧内多次调用只保留最后一次 */
  push(value: T): void;
  /** 立即提交待处理值（没有待处理值时不重复提交） */
  flush(): void;
  /** 丢弃待处理值并取消已排期的回调 */
  cancel(): void;
}

export interface ThrottleScheduler {
  request(cb: () => void): number;
  cancel(id: number): void;
  now(): number;
}

const defaultScheduler: ThrottleScheduler = {
  request: (cb) => {
    if (typeof requestAnimationFrame === 'function') {
      return requestAnimationFrame(() => cb());
    }
    return setTimeout(cb, 16) as unknown as number;
  },
  cancel: (id) => {
    if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(id);
    else clearTimeout(id);
  },
  now: () => (typeof performance !== 'undefined' ? performance.now() : Date.now())
};

export function createFrameThrottle<T>(
  apply: (value: T) => void,
  options: { maxPerSecond?: number; scheduler?: ThrottleScheduler } = {}
): FrameThrottle<T> {
  const scheduler = options.scheduler ?? defaultScheduler;
  const minInterval = 1000 / Math.max(1, options.maxPerSecond ?? 20);
  let pending: { value: T } | null = null;
  let scheduled: number | null = null;
  let lastAppliedAt = -Infinity;

  const run = () => {
    scheduled = null;
    if (!pending) return;
    const wait = minInterval - (scheduler.now() - lastAppliedAt);
    if (wait > 0) {
      // 未到下一次可提交时间：留到下一帧再判断，避免超出每秒上限
      scheduled = scheduler.request(run);
      return;
    }
    const { value } = pending;
    pending = null;
    lastAppliedAt = scheduler.now();
    apply(value);
  };

  return {
    push(value: T) {
      pending = { value };
      if (scheduled == null) scheduled = scheduler.request(run);
    },
    flush() {
      if (scheduled != null) {
        scheduler.cancel(scheduled);
        scheduled = null;
      }
      if (!pending) return;
      const { value } = pending;
      pending = null;
      lastAppliedAt = scheduler.now();
      apply(value);
    },
    cancel() {
      if (scheduled != null) {
        scheduler.cancel(scheduled);
        scheduled = null;
      }
      pending = null;
    }
  };
}
