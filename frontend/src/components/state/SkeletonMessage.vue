<script setup lang="ts">
/** P5-09：会话切换时的消息骨架屏，避免内容直接替换造成的跳变 */
withDefaults(defineProps<{ rows?: number }>(), { rows: 3 });
</script>

<template>
  <div class="skeleton-message" aria-hidden="true">
    <div v-for="i in rows" :key="i" class="sk-row">
      <div class="sk-avatar"></div>
      <div class="sk-lines">
        <div class="sk-line" :style="{ width: i % 2 === 0 ? '88%' : '68%' }"></div>
        <div class="sk-line short"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.skeleton-message {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.sk-row { display: flex; gap: 10px; align-items: flex-start; }
.sk-avatar,
.sk-line {
  background: var(--skeleton-a);
  position: relative;
  overflow: hidden;
}
.sk-avatar { width: 26px; height: 26px; border-radius: 50%; flex: 0 0 auto; }
.sk-lines { flex: 1; display: flex; flex-direction: column; gap: 9px; padding-top: 4px; }
.sk-line { height: 11px; border-radius: 5px; width: 88%; }
.sk-line.short { width: 46%; }
.sk-avatar::after,
.sk-line::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, var(--skeleton-b), transparent);
  animation: sk-shimmer 1.3s infinite;
}
@keyframes sk-shimmer {
  from { transform: translateX(-100%); }
  to { transform: translateX(100%); }
}
@media (prefers-reduced-motion: reduce) {
  .sk-avatar::after,
  .sk-line::after { animation: none; }
}
</style>
