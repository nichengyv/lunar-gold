<script setup lang="ts">
/** P5-09：统一的错误态，含错误码、可读原因与重试入口 */
defineProps<{
  title?: string;
  message?: string;
  code?: string;
}>();
defineEmits<{ retry: [] }>();
</script>

<template>
  <div class="error-state" role="alert">
    <div class="es-title">{{ title || '加载失败' }}</div>
    <p class="es-message">{{ message || '请稍后重试。' }}</p>
    <div class="es-actions">
      <span v-if="code" class="es-code">{{ code }}</span>
      <button class="es-retry" type="button" @click="$emit('retry')">重试</button>
    </div>
  </div>
</template>

<style scoped>
.error-state {
  margin: 16px 20px;
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--danger) 45%, var(--border));
  background: var(--danger-bg);
  border-radius: var(--radius-md);
}
.es-title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--danger);
}
.es-message {
  margin-top: 4px;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}
.es-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.es-code {
  font-family: ui-monospace, Consolas, monospace;
  font-size: var(--fs-xs);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--danger) 12%, transparent);
  color: var(--danger);
}
.es-retry {
  margin-left: auto;
  padding: 4px 12px;
  font-size: var(--fs-sm);
  border-radius: var(--radius-sm);
  border: 1px solid var(--primary);
  background: var(--primary);
  color: var(--on-primary);
}
.es-retry:hover { background: var(--primary-hover); border-color: var(--primary-hover); }
</style>
