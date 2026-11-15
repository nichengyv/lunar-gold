/** P5-10-4：是否应减少动效。手动开启优先，其次跟随系统偏好。 */
export function isReducedMotion(): boolean {
  if (typeof document !== 'undefined' && document.documentElement.getAttribute('data-motion') === 'reduce') {
    return true;
  }
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }
  return false;
}
