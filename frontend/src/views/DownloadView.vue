<template>
  <div class="download-page">
    <div class="download-card">
      <div class="download-hero">
        <span class="download-logo"><Icon name="bot" :size="28" /></span>
        <h1>AgentKey 桌面端</h1>
        <p class="download-sub">
          本地项目读写、命令执行、Git、MCP 与审批审查全部在你自己的电脑上运行。
        </p>
      </div>

      <ul class="feature-list">
        <li v-for="item in features" :key="item">
          <Icon name="check" :size="15" class="feature-check" />
          <span>{{ item }}</span>
        </li>
      </ul>

      <div class="version-row">
        <span class="version-label">最新版本</span>
        <strong class="version-value">{{ meta.latestDesktopVersion || '即将发布' }}</strong>
      </div>

      <p class="download-hint">
        安装包与 SHA-256 校验值会在发布后于本页提供；网页端只提供对话与资料问答，不执行代码。
      </p>

      <div class="download-actions">
        <router-link v-if="auth.isLoggedIn" class="btn btn-primary" to="/chat">回到对话</router-link>
        <router-link v-else class="btn btn-primary" to="/login">登录网页端</router-link>
      </div>

      <p class="platform-hint">支持 Windows / macOS / Linux</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useAuthStore } from '../stores/auth';
import { useMetaStore } from '../stores/meta';
import Icon from '../components/Icon.vue';

const auth = useAuthStore();
const meta = useMetaStore();

const features = [
  '读写本机项目文件，改动可逐轮撤回',
  '执行命令、构建与测试，危险操作先审批',
  'Git 操作、MCP 工具与 LSP 诊断',
  '本地数据库与会话历史，断网也可用'
];
</script>

<style scoped>
.download-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 20px;
  background: var(--bg-secondary);
}

.download-card {
  width: 100%;
  max-width: 560px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  padding: 36px 32px 28px;
}

.download-hero {
  text-align: center;
  margin-bottom: 24px;
}

.download-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: var(--primary-light);
  color: var(--primary);
}

.download-hero h1 {
  margin: 14px 0 8px;
  font-size: 22px;
  font-weight: 600;
}

.download-sub {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--fs-sm);
  line-height: 1.6;
}

.feature-list {
  list-style: none;
  margin: 0 0 20px;
  padding: 18px;
  background: var(--bg-secondary);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.feature-list li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: var(--fs-sm);
  color: var(--text);
  line-height: 1.5;
}

.feature-check {
  color: var(--success);
  margin-top: 2px;
}

.version-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-top: 1px solid var(--border);
  font-size: var(--fs-sm);
}

.version-label {
  color: var(--text-secondary);
}

.version-value {
  font-weight: 600;
}

.download-hint {
  margin: 4px 0 20px;
  color: var(--text-secondary);
  font-size: var(--fs-xs);
  line-height: 1.6;
}

.download-actions .btn {
  display: block;
  text-align: center;
  text-decoration: none;
}

.platform-hint {
  margin: 14px 0 0;
  text-align: center;
  color: var(--text-secondary);
  font-size: var(--fs-xs);
}
</style>
