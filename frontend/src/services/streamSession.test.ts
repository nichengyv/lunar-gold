import { describe, expect, it } from 'vitest';
import { createStreamSession } from './streamSession';

describe('streamSession', () => {
  it('开新流并释放句柄', () => {
    const session = createStreamSession();
    const controller = session.begin();

    expect(session.isActive(controller)).toBe(true);
    session.release(controller);
    expect(session.isActive(controller)).toBe(false);
  });

  it('abort 中断当前流并清空登记', () => {
    const session = createStreamSession();
    const controller = session.begin();

    session.abort();

    expect(controller.signal.aborted).toBe(true);
    expect(session.isActive(controller)).toBe(false);
  });

  it('旧流晚到的 release 不会覆盖新流', () => {
    const session = createStreamSession();
    const first = session.begin();
    const second = session.begin();

    session.release(first);

    expect(session.isActive(second)).toBe(true);
  });

  it('恢复代际：新代际或主动作废后旧令牌失效', () => {
    const session = createStreamSession();
    const first = session.newRecoveryToken();

    expect(session.isRecoveryCurrent(first)).toBe(true);

    const second = session.newRecoveryToken();
    expect(session.isRecoveryCurrent(first)).toBe(false);
    expect(session.isRecoveryCurrent(second)).toBe(true);

    session.cancelRecovery();
    expect(session.isRecoveryCurrent(second)).toBe(false);
  });
});
