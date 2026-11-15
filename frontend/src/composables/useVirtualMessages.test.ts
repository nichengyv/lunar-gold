import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import { buildOffsets, findVisibleRange, useVirtualMessages } from './useVirtualMessages';

const IDS = Array.from({ length: 10 }, (_, i) => i + 1);

describe('buildOffsets', () => {
  it('已实测用真实高度，未实测用估算高度', () => {
    const heights = new Map([[2, 200]]);
    const { offsets, total } = buildOffsets(IDS.slice(0, 3), heights, 100);
    expect(offsets).toEqual([0, 100, 300]);
    expect(total).toBe(400);
  });
});

describe('findVisibleRange', () => {
  const { offsets } = buildOffsets(IDS, new Map(), 100);
  const heights = new Map<number, number>();

  it('从顶部开始时保留 overscan 且上下占位守恒', () => {
    const range = findVisibleRange(IDS, offsets, heights, 100, 0, 250, 0, 2);
    expect(range.start).toBe(0);
    expect(range.end).toBe(5);
    expect(range.topPad).toBe(0);
    expect(range.bottomPad).toBe(500);
    expect(range.topPad + (range.end - range.start) * 100 + range.bottomPad).toBe(1000);
  });

  it('滚动到中间时窗口对齐目标位置且占位总和守恒', () => {
    const range = findVisibleRange(IDS, offsets, heights, 100, 400, 250, 0, 1);
    expect(range.start).toBe(2);
    expect(range.end).toBe(8);
    expect(range.topPad).toBe(200);
    expect(range.bottomPad).toBe(200);
    expect(range.topPad + (range.end - range.start) * 100 + range.bottomPad).toBe(1000);
  });

  it('滚到底部时窗口不越过数组尾部', () => {
    const range = findVisibleRange(IDS, offsets, heights, 100, 900, 250, 0, 1);
    expect(range.start).toBe(7);
    expect(range.end).toBe(10);
    expect(range.bottomPad).toBe(0);
  });

  it('空列表返回空窗口', () => {
    expect(findVisibleRange([], [], heights, 100, 0, 300, 0, 2)).toEqual({
      start: 0,
      end: 0,
      topPad: 0,
      bottomPad: 0
    });
  });
});

describe('useVirtualMessages', () => {
  it('关闭时返回全量消息，行为与改造前一致', () => {
    const items = Array.from({ length: 300 }, (_, i) => ({ id: i + 1 }));
    const scroller = ref<HTMLElement>();
    const virtual = useVirtualMessages({ scroller, items: () => items, enabled: () => false });
    expect(virtual.visibleItems.value).toHaveLength(300);
  });

  it('开启时只返回窗口内消息，并能按估算偏移定位', () => {
    const items = Array.from({ length: 300 }, (_, i) => ({ id: i + 1 }));
    const el = { scrollTop: 0, clientHeight: 300 } as HTMLElement;
    const scroller = ref<HTMLElement | undefined>(el);
    const virtual = useVirtualMessages({
      scroller,
      items: () => items,
      enabled: () => true,
      estimateHeight: 100,
      overscan: 0,
      bufferPx: 0
    });

    virtual.syncViewport();
    expect(virtual.visibleItems.value.length).toBeLessThan(10);

    virtual.scrollToIndex(10);
    expect(el.scrollTop).toBe(1000 - 16);
    expect(virtual.visibleItems.value[0]).toMatchObject({ id: 10 });
  });
});
