import { describe, expect, it } from 'vitest';
import { createFrameThrottle, type ThrottleScheduler } from './frameThrottle';

/** 手动调度器：让测试精确控制“帧”和时间的推进 */
class ManualScheduler implements ThrottleScheduler {
  time = 0;
  private nextId = 1;
  private tasks = new Map<number, () => void>();

  request(cb: () => void): number {
    const id = this.nextId++;
    this.tasks.set(id, cb);
    return id;
  }

  cancel(id: number): void {
    this.tasks.delete(id);
  }

  now(): number {
    return this.time;
  }

  get pending(): number {
    return this.tasks.size;
  }

  /** 执行最早排期的一个回调，用来模拟一帧 */
  runFrame(): void {
    const first = this.tasks.entries().next();
    if (first.done) return;
    const [id, cb] = first.value;
    this.tasks.delete(id);
    cb();
  }
}

describe('createFrameThrottle', () => {
  it('同一帧内多次 push 只提交最后一次', () => {
    const scheduler = new ManualScheduler();
    const applied: string[] = [];
    const throttle = createFrameThrottle<string>((value) => applied.push(value), { scheduler });

    throttle.push('a');
    throttle.push('b');
    throttle.push('c');
    expect(applied).toEqual([]);

    scheduler.runFrame();
    expect(applied).toEqual(['c']);
    expect(scheduler.pending).toBe(0);
  });

  it('两次提交至少间隔 50ms，最多 20 次/秒', () => {
    const scheduler = new ManualScheduler();
    const applied: string[] = [];
    const throttle = createFrameThrottle<string>((value) => applied.push(value), { scheduler });

    scheduler.time = 100;
    throttle.push('first');
    scheduler.runFrame();
    expect(applied).toEqual(['first']);

    // 距上次提交不足 50ms：继续排到下一帧，不立即提交
    scheduler.time = 120;
    throttle.push('second');
    scheduler.runFrame();
    expect(applied).toEqual(['first']);
    expect(scheduler.pending).toBe(1);

    // 到 150ms 才允许第二次提交
    scheduler.time = 150;
    scheduler.runFrame();
    expect(applied).toEqual(['first', 'second']);
  });

  it('flush 立即提交最新值并取消已排期回调', () => {
    const scheduler = new ManualScheduler();
    const applied: string[] = [];
    const throttle = createFrameThrottle<string>((value) => applied.push(value), { scheduler });

    scheduler.time = 10;
    throttle.push('a');
    throttle.push('b');
    throttle.flush();

    expect(applied).toEqual(['b']);
    expect(scheduler.pending).toBe(0);

    // 已提交后没有待处理值，flush 不重复提交
    throttle.flush();
    expect(applied).toEqual(['b']);
  });

  it('cancel 丢弃待提交值，之后不再回调', () => {
    const scheduler = new ManualScheduler();
    const applied: string[] = [];
    const throttle = createFrameThrottle<string>((value) => applied.push(value), { scheduler });

    throttle.push('a');
    throttle.cancel();
    scheduler.runFrame();

    expect(applied).toEqual([]);
    expect(scheduler.pending).toBe(0);
  });
});
