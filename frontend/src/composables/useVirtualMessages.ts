/**
 * P5-11-3：长会话窗口化渲染。
 *
 * 只负责“渲染哪一段 + 上下留多少占位”，消息语义、复制、搜索定位仍由 ChatView 决定：
 * - 已渲染过的消息用 ResizeObserver 实测高度，未渲染的用最近平均高度估算；
 * - 滚动时只替换窗口内容，窗口外不产生 DOM；
 * - 关闭时返回全量列表，调用方行为与改造前一致。
 */
import { computed, nextTick, ref, type Ref } from 'vue';

export interface VirtualItem {
  id: number;
}

export interface UseVirtualMessagesOptions<T extends VirtualItem = VirtualItem> {
  scroller: Ref<HTMLElement | undefined>;
  items: () => readonly T[];
  enabled: () => boolean;
  /** 可视区上下额外渲染的条数 */
  overscan?: number;
  /** 可视区上下额外渲染的像素高度，缓解快速滚动时的空白 */
  bufferPx?: number;
  /** 未实测消息的估算高度 */
  estimateHeight?: number;
}

export interface VirtualRange {
  start: number;
  end: number;
  topPad: number;
  bottomPad: number;
}

/** 累计偏移：第 i 条的顶部位置 */
export function buildOffsets(
  ids: readonly number[],
  heights: Map<number, number>,
  estimate: number
): { offsets: number[]; total: number } {
  const offsets = new Array<number>(ids.length);
  let top = 0;
  for (let i = 0; i < ids.length; i++) {
    offsets[i] = top;
    top += heights.get(ids[i]) ?? estimate;
  }
  return { offsets, total: top };
}

/** 根据滚动位置计算需要渲染的区间与上下占位高度 */
export function findVisibleRange(
  ids: readonly number[],
  offsets: readonly number[],
  heights: Map<number, number>,
  estimate: number,
  scrollTop: number,
  viewport: number,
  bufferPx: number,
  overscan: number
): VirtualRange {
  const n = ids.length;
  if (n === 0) return { start: 0, end: 0, topPad: 0, bottomPad: 0 };
  const heightAt = (i: number) => heights.get(ids[i]) ?? estimate;
  const top = Math.max(0, scrollTop - bufferPx);
  const bottom = scrollTop + Math.max(0, viewport) + bufferPx;

  let start = 0;
  while (start < n - 1 && offsets[start] + heightAt(start) < top) start++;
  let end = start;
  while (end < n && offsets[end] < bottom) end++;

  const realStart = Math.max(0, start - overscan);
  const realEnd = Math.min(n, end + overscan);
  let lastEnd = 0;
  if (realEnd > realStart) {
    const last = realEnd - 1;
    lastEnd = offsets[last] + heightAt(last);
  }
  return {
    start: realStart,
    end: realEnd,
    topPad: offsets[realStart],
    bottomPad: Math.max(0, offsets[n - 1] + heightAt(n - 1) - lastEnd)
  };
}

export function useVirtualMessages<T extends VirtualItem>(options: UseVirtualMessagesOptions<T>) {
  const overscan = options.overscan ?? 6;
  const bufferPx = options.bufferPx ?? 800;
  const estimate = options.estimateHeight ?? 120;

  const scrollTop = ref(0);
  const viewport = ref(0);
  /** 实测高度变化计数，用于让布局计算重新执行 */
  const layoutVersion = ref(0);
  const heights = new Map<number, number>();
  const observers = new Map<number, ResizeObserver>();

  const enabled = computed(() => options.enabled());

  const layout = computed(() => {
    layoutVersion.value;
    if (!enabled.value) return { ids: [] as number[], offsets: [] as number[], total: 0 };
    const ids = options.items().map((item) => item.id);
    return { ids, ...buildOffsets(ids, heights, estimate) };
  });

  const range = computed<VirtualRange>(() => {
    if (!enabled.value) return { start: 0, end: 0, topPad: 0, bottomPad: 0 };
    return findVisibleRange(
      layout.value.ids,
      layout.value.offsets,
      heights,
      estimate,
      scrollTop.value,
      viewport.value,
      bufferPx,
      overscan
    );
  });

  const visibleItems = computed<readonly T[]>(() => {
    const all = options.items();
    if (!enabled.value) return all;
    return all.slice(range.value.start, range.value.end);
  });

  function measureHeight(id: number, el: HTMLElement) {
    const height = el.offsetHeight || el.getBoundingClientRect().height;
    if (height > 0 && Math.abs((heights.get(id) ?? -1) - height) > 0.5) {
      heights.set(id, height);
      layoutVersion.value += 1;
    }
  }

  /** 模板 ref 回调：渲染出来时开始实测，卸载时断开观察 */
  function registerItem(id: number, el: Element | null) {
    const prev = observers.get(id);
    if (prev) {
      prev.disconnect();
      observers.delete(id);
    }
    if (!enabled.value || !el) return;
    const node = el as HTMLElement;
    measureHeight(id, node);
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() => measureHeight(id, node));
    observer.observe(node);
    observers.set(id, observer);
  }

  function onScroll() {
    const el = options.scroller.value;
    if (!el) return;
    scrollTop.value = el.scrollTop;
    viewport.value = el.clientHeight;
  }

  function syncViewport() {
    const el = options.scroller.value;
    if (!el) return;
    viewport.value = el.clientHeight;
    scrollTop.value = el.scrollTop;
  }

  function offsetOfIndex(index: number): number {
    return layout.value.offsets[index] ?? 0;
  }

  /** 把某一位置的估算偏移直接写到滚动容器，渲染后由调用方按实测高度校正 */
  function scrollToIndex(index: number) {
    const el = options.scroller.value;
    if (!el || index < 0) return;
    const top = Math.max(0, offsetOfIndex(index) - 16);
    el.scrollTop = top;
    scrollTop.value = top;
  }

  function reset() {
    heights.clear();
    observers.forEach((observer) => observer.disconnect());
    observers.clear();
    scrollTop.value = 0;
    layoutVersion.value += 1;
    void nextTick(syncViewport);
  }

  /** 组件卸载时断开所有观察者 */
  function dispose() {
    observers.forEach((observer) => observer.disconnect());
    observers.clear();
  }

  return {
    enabled,
    visibleItems,
    range,
    layoutVersion,
    heights,
    estimate,
    registerItem,
    onScroll,
    syncViewport,
    scrollToIndex,
    offsetOfIndex,
    reset,
    dispose
  };
}
