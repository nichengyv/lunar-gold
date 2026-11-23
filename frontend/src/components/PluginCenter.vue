<template>
  <div class="plugin-center">
    <el-tabs v-model="activeTab" class="pc-tabs">
      <!-- 插件市场 -->
      <el-tab-pane name="market">
        <template #label>
          <span class="pc-tab-label"><Icon name="sparkles" :size="13" /> 插件市场</span>
        </template>
        <div v-if="loading" class="pc-empty">加载插件目录…</div>
        <div v-else-if="error" class="pc-empty">
          加载失败：{{ error }}
          <button class="pc-link-btn" @click="load">重试</button>
        </div>
        <div v-else-if="catalog.plugins.length === 0" class="pc-empty">暂无可用插件</div>
        <div v-else class="pc-grid">
          <div v-for="p in catalog.plugins" :key="p.id" class="pc-card">
            <div class="pc-card-head">
              <span class="pc-avatar" :style="{ background: colorOf(p.category) }">{{ initial(p.name) }}</span>
              <div class="pc-card-title">
                <div class="pc-name">{{ p.name }}</div>
                <div class="pc-sub">{{ p.category }}<span v-if="p.toolCount != null"> · {{ p.toolCount }} 工具</span></div>
              </div>
            </div>
            <p class="pc-desc">{{ p.description }}</p>
            <div class="pc-tags">
              <span v-if="p.runtime?.length" class="pc-tag">需要 {{ p.runtime.join(', ') }}</span>
              <span v-if="p.credentials && p.credentials.length > 0" class="pc-tag">需填 {{ p.credentials.length }} 项</span>
              <span v-else class="pc-tag pc-tag-free">免配置</span>
            </div>
            <div class="pc-actions">
              <button
                class="pc-install"
                :class="{ installed: installedIds.has(p.id) }"
                :disabled="installedIds.has(p.id)"
                @click="openInstall(p)"
              >
                {{ installedIds.has(p.id) ? '已安装' : '安装' }}
              </button>
              <a v-if="p.homepageUrl" :href="p.homepageUrl" target="_blank" rel="noopener" class="pc-detail">详情 ↗</a>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 已安装 -->
      <el-tab-pane name="installed">
        <template #label>
          <span class="pc-tab-label"><Icon name="cpu" :size="13" /> 已安装</span>
        </template>
        <McpServersPanel :key="panelKey" />
      </el-tab-pane>
    </el-tabs>

    <!-- 安装弹窗：按 credentials schema 渲染表单 -->
    <el-dialog
      v-model="installVisible"
      :title="installing ? `安装 ${installing.name}` : '安装插件'"
      width="500px"
      :close-on-click-modal="false"
    >
      <div v-if="installing">
        <p class="pc-install-desc">{{ installing.description }}</p>
        <el-form v-if="hasCredentials" label-position="top" @submit.prevent="confirmInstall">
          <el-form-item
            v-for="f in installing.credentials"
            :key="f.name"
            :label="f.label"
            :required="f.required"
          >
            <el-input
              v-model="formValues[f.name]"
              :type="f.secret ? 'password' : 'text'"
              :placeholder="f.placeholder"
              :show-password="f.secret"
              @keyup.enter="confirmInstall"
            />
            <div v-if="f.description" class="pc-field-hint">{{ f.description }}</div>
          </el-form-item>
        </el-form>
        <div v-else class="pc-no-cred">该插件无需配置，确认后立即安装并连接。</div>
        <div v-if="installing.runtime?.length" class="pc-runtime-hint">
          <Icon name="alert" :size="12" /> 安装前会检测运行时：{{ installing.runtime.join(', ') }}
        </div>
      </div>
      <template #footer>
        <el-button @click="installVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmInstall">确认安装</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, computed } from 'vue';
import { ElMessage } from 'element-plus';
import Icon from './Icon.vue';
import McpServersPanel from './McpServersPanel.vue';
import * as pluginApi from '../api/plugin';
import { listMcpServers } from '../api/mcp';
import type { Catalog, PluginManifest } from '../types/plugin';

const activeTab = ref<'market' | 'installed'>('market');
const catalog = ref<Catalog>({ version: '', plugins: [] });
const loading = ref(false);
const error = ref('');
const installedIds = ref<Set<string>>(new Set());
const panelKey = ref(0);

const installVisible = ref(false);
const installing = ref<PluginManifest | null>(null);
const submitting = ref(false);
const formValues = reactive<Record<string, string>>({});

const hasCredentials = computed(
  () => !!installing.value?.credentials && installing.value.credentials.length > 0
);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    catalog.value = await pluginApi.fetchCatalog();
    const servers = await listMcpServers();
    installedIds.value = new Set(
      servers.map(s => s.pluginId).filter((id): id is string => !!id)
    );
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

function openInstall(p: PluginManifest) {
  installing.value = p;
  Object.keys(formValues).forEach(k => delete formValues[k]);
  installVisible.value = true;
}

async function confirmInstall() {
  if (!installing.value || submitting.value) return;
  submitting.value = true;
  const target = installing.value;
  try {
    await pluginApi.installPlugin(target.id, { ...formValues });
    ElMessage.success(`已安装 ${target.name}，正在连接`);
    installVisible.value = false;
    activeTab.value = 'installed';
    panelKey.value++; // 强制 McpServersPanel 重挂载刷新列表
    await load();
  } catch {
    // 错误已由 axios 拦截器统一提示
  } finally {
    submitting.value = false;
  }
}

function initial(name: string): string {
  return name?.charAt(0).toUpperCase() || '?';
}

const AVATAR_COLORS = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#3b82f6', '#8b5cf6', '#ec4899', '#14b8a6'];
function colorOf(category?: string): string {
  if (!category) return AVATAR_COLORS[0];
  let hash = 0;
  for (let i = 0; i < category.length; i++) hash = (hash * 31 + category.charCodeAt(i)) >>> 0;
  return AVATAR_COLORS[hash % AVATAR_COLORS.length];
}

function errorText(err: unknown): string {
  const anyError = err as { response?: { data?: { message?: string } }; message?: string };
  return anyError?.response?.data?.message || anyError?.message || '加载失败';
}

onMounted(load);
</script>

<style scoped>
.plugin-center {
  display: flex;
  flex-direction: column;
}
.pc-tabs {
  --el-tabs-header-height: 38px;
}
.pc-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
}
.pc-empty {
  padding: 24px;
  text-align: center;
  border: 1px dashed var(--border);
  border-radius: 8px;
  font-size: 12px;
  color: var(--text-muted);
}
.pc-link-btn {
  margin-left: 6px;
  border: none;
  background: none;
  color: var(--el-color-primary, #409eff);
  cursor: pointer;
  text-decoration: underline;
}
.pc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}
.pc-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.pc-card:hover {
  border-color: var(--el-color-primary-light-5, #a0cfff);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}
.pc-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pc-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  color: #fff;
  font-weight: 700;
  font-size: 15px;
  flex-shrink: 0;
}
.pc-card-title {
  min-width: 0;
}
.pc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}
.pc-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-muted);
}
.pc-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pc-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.pc-tag {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 10px;
  border: 1px solid var(--border);
  color: var(--text-muted);
  background: var(--surface-2, transparent);
}
.pc-tag-free {
  color: #1a7f37;
  border-color: #1a7f3755;
  background: #1a7f3712;
}
.pc-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  gap: 8px;
}
.pc-install {
  padding: 5px 14px;
  border: 1px solid var(--el-color-primary, #409eff);
  border-radius: 6px;
  background: var(--el-color-primary, #409eff);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}
.pc-install:hover:not(:disabled) {
  opacity: 0.9;
}
.pc-install:disabled,
.pc-install.installed {
  background: transparent;
  color: var(--text-muted);
  border-color: var(--border);
  cursor: default;
}
.pc-detail {
  font-size: 11px;
  color: var(--el-color-primary, #409eff);
  text-decoration: none;
}
.pc-install-desc {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
.pc-field-hint {
  margin-top: 3px;
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.5;
}
.pc-no-cred {
  padding: 10px 12px;
  border: 1px dashed var(--border);
  border-radius: 6px;
  font-size: 12px;
  color: var(--text-muted);
  background: var(--surface-2, transparent);
}
.pc-runtime-hint {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 10px;
  font-size: 11px;
  color: var(--text-muted);
}
</style>
