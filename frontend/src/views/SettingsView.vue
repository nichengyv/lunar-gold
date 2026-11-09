<template>
  <div class="page settings-page">
    <div class="settings-layout">
      <!-- 左侧 tab 导航 -->
      <nav class="settings-nav">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          class="nav-item"
          :class="{ active: activeTab === tab.id }"
          @click="activeTab = tab.id"
        >
          <Icon :name="tab.icon" :size="15" />
          <span>{{ tab.label }}</span>
        </button>
        <div class="nav-spacer"></div>
        <div class="nav-version">Lunar Gold {{ version }}</div>
      </nav>

      <!-- 右侧内容区 -->
      <div class="settings-content">

        <!-- ============ tab: 对话默认 ============ -->
        <template v-if="activeTab === 'chat'">
          <div class="section">
            <h2 class="section-title">对话默认</h2>
            <p class="section-desc">新会话启动时使用的默认参数，不影响已有会话</p>

            <div class="card">
              <h3><Icon name="cpu" :size="15" /> 默认模型</h3>
              <el-select
                v-model="local.defaultModel"
                class="full-width"
                :loading="modelsLoading"
                @change="saveChatDefaults"
              >
                <el-option
                  v-for="m in models"
                  :key="m.id"
                  :label="m.name"
                  :value="m.id"
                  :disabled="!m.available"
                >
                  <span>{{ m.name }}</span>
                  <span class="option-sub">{{ m.provider }}{{ m.available ? '' : '（未配置 API Key）' }}</span>
                </el-option>
              </el-select>
            </div>

            <div class="card">
              <h3><Icon name="sparkles" :size="15" /> 默认推理档位</h3>
              <el-radio-group v-model="local.defaultReasoning" @change="saveChatDefaults">
                <el-radio value="FAST">快速 · 少轮次，回答快</el-radio>
                <el-radio value="STANDARD">标准 · 常规平衡</el-radio>
                <el-radio value="DEEP">深度 · 多步思考，质量高</el-radio>
              </el-radio-group>
            </div>

            <div class="card">
              <h3><Icon name="shield" :size="15" /> 默认工作权限</h3>
              <el-radio-group v-model="local.defaultPermission" @change="saveChatDefaults">
                <el-radio value="MANUAL">手动审批 · 写文件和执行命令都要确认</el-radio>
                <el-radio value="AUTO">自动审批 · 写文件和普通命令自动通过，仅破坏性命令需确认</el-radio>
                <el-radio value="FULL">完全权限 · 全部自动通过</el-radio>
              </el-radio-group>
              <p class="hint">项目级默认权限优先于此处——如果在某个工作区设置过默认权限，新项目还是会继承那个值</p>
            </div>

            <div class="card">
              <h3><Icon name="keyboard" :size="15" /> 快捷键</h3>
              <table class="shortcuts">
                <tr><td><kbd>Ctrl</kbd>/<kbd>Cmd</kbd> + <kbd>K</kbd></td><td>聚焦输入框</td></tr>
                <tr><td><kbd>Esc</kbd> × 2</td><td>停止生成（500ms 内连按两次）</td></tr>
                <tr><td><kbd>/</kbd></td><td>打开命令面板</td></tr>
                <tr><td><kbd>@</kbd></td><td>打开引用面板（文件/资料/会话）</td></tr>
                <tr><td><kbd>Enter</kbd></td><td>发送消息</td></tr>
                <tr><td><kbd>Shift</kbd> + <kbd>Enter</kbd></td><td>输入换行</td></tr>
                <tr><td><kbd>↑</kbd>/<kbd>↓</kbd></td><td>面板内选择上/下一个候选</td></tr>
              </table>
            </div>

            <div class="card">
              <h3><Icon name="info" :size="15" /> 关于</h3>
              <div class="about-row">
                <span class="about-label">版本</span>
                <span class="about-value">{{ version }}</span>
              </div>
              <div class="about-row">
                <span class="about-label">后端状态</span>
                <span class="about-value">
                  <span class="status-dot" :class="health?.status === 'UP' ? 'up' : 'down'"></span>
                  {{ health?.status === 'UP' ? '在线' : '离线' }}
                </span>
              </div>
              <div class="about-row" v-if="health">
                <span class="about-label">运行时长</span>
                <span class="about-value">{{ formatUptime(health.uptimeMs) }}</span>
              </div>
            </div>
          </div>
        </template>

        <!-- ============ tab: 模型服务（API Key 配置） ============ -->
        <template v-else-if="activeTab === 'models'">
          <div class="section keys-section">
            <h2 class="section-title">模型服务</h2>
            <p class="section-desc">配置 DeepSeek、SiliconFlow、Tavily 等提供商的 API Key，保存后新对话即可使用对应模型</p>
            <KeysView />
          </div>
        </template>

        <!-- ============ tab: Agent 指令 ============ -->
        <template v-else-if="activeTab === 'rules'">
          <div class="section">
            <h2 class="section-title">Agent 指令库</h2>
            <p class="section-desc">
              设置 Agent 的长期行为规则。三层 scope 拼进 system prompt，后者权重更高可覆盖前者：
              <span class="scope-chip global">全局</span>
              <span class="scope-arrow">→</span>
              <span class="scope-chip project">项目</span>
              <span class="scope-arrow">→</span>
              <span class="scope-chip session">会话</span>
            </p>
            <div class="card">
              <RulesPanel />
            </div>
          </div>
        </template>

        <!-- ============ tab: MCP / 扩展工具 ============ -->
        <template v-else-if="activeTab === 'mcp'">
          <div class="section">
            <h2 class="section-title">扩展工具</h2>
            <p class="section-desc">
              Lunar Gold 内置了文件读写、命令执行、代码编辑等工具链。扩展工具可以接入更多外部能力，
              让 Agent 能做普通聊天机器人做不到的事。
            </p>

            <div class="card">
              <div class="mcp-header">
                <h3><Icon name="plug" :size="15" /> 启用扩展工具</h3>
                <el-switch
                  v-model="local.mcpEnabled"
                  @change="saveMcpEnabled"
                  :disabled="mcpLoading"
                />
              </div>
              <p class="hint">
                关掉后 Agent 只能使用内置工具（读写文件、跑命令、编辑代码、搜索）。
                打开后会根据可用的扩展工具增加更多能力——通常保持开启即可。
              </p>
              <div v-if="mcpLoading" class="loading">加载中…</div>
            </div>

            <div class="card">
              <h3><Icon name="info" :size="15" /> 关于扩展工具</h3>
              <p class="hint">
                扩展工具由系统管理员配置。如果你觉得 Agent 缺少某些能力（比如查数据库、调内部 API、读写 Notion），
                联系管理员添加对应扩展即可——不需要你改任何配置。
              </p>
            </div>
          </div>
        </template>

        <!-- ============ tab: 外观 ============ -->
        <template v-else-if="activeTab === 'appearance'">
          <div class="section">
            <h2 class="section-title">外观</h2>
            <p class="section-desc">自定义界面主题和字体大小</p>

            <div class="card">
              <h3><Icon name="palette" :size="15" /> 主题</h3>
              <div class="theme-options">
                <button
                  class="theme-preview"
                  :class="{ active: !theme.isDark }"
                  @click="theme.set(false)"
                >
                  <div class="preview-light"></div>
                  <span>浅色</span>
                </button>
                <button
                  class="theme-preview"
                  :class="{ active: theme.isDark }"
                  @click="theme.set(true)"
                >
                  <div class="preview-dark"></div>
                  <span>深色</span>
                </button>
              </div>
            </div>

            <div class="card">
              <h3><Icon name="type" :size="15" /> 字体大小</h3>
              <el-radio-group v-model="theme.fontSize" @change="theme.setFont(theme.fontSize)">
                <el-radio value="small">小</el-radio>
                <el-radio value="medium">中</el-radio>
                <el-radio value="large">大</el-radio>
              </el-radio-group>
              <p class="hint">字体大小影响全局：导航、消息、输入框、设置页等所有文本区域</p>
            </div>
          </div>
        </template>

        <!-- ============ tab: 账号安全（内嵌，不跳路由） ============ -->
        <template v-else-if="activeTab === 'account'">
          <div class="section">
            <h2 class="section-title">账号安全</h2>
            <p class="section-desc">管理个人信息和登录密码</p>

            <div class="card">
              <h3><Icon name="user" :size="15" /> 个人信息</h3>
              <div class="form-row">
                <label>用户名</label>
                <input v-model="account.username" type="text" readonly>
              </div>
              <div class="form-row">
                <label>邮箱</label>
                <input v-model="account.email" type="email">
              </div>
              <button class="btn-small btn-small-primary" @click="onAccountUpdate">保存修改</button>
            </div>

            <div class="card">
              <h3><Icon name="lock" :size="15" /> 修改密码</h3>
              <div class="form-row">
                <label>原密码</label>
                <input v-model="account.oldPwd" type="password">
              </div>
              <div class="form-row">
                <label>新密码</label>
                <input v-model="account.newPwd" type="password" placeholder="至少8位，含大小写+数字+特殊字符">
              </div>
              <div class="form-row">
                <label>确认新密码</label>
                <input v-model="account.confirmPwd" type="password">
              </div>
              <button class="btn-small btn-small-primary" :disabled="account.changing" @click="onPasswordChange">
                更新密码
              </button>
              <span class="status-text" :class="{ error: account.statusIsError }">{{ account.status }}</span>
            </div>
          </div>
        </template>

      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useChatStore } from '../stores/chat';
import { useThemeStore } from '../stores/theme';
import { useAuthStore } from '../stores/auth';
import { useMetaStore } from '../stores/meta';
import * as authApi from '../api/auth';
import * as settingsApi from '../api/settings';
import * as mcpApi from '../api/mcp';
import { encryptPassword } from '../utils/crypto';
import RulesPanel from '../components/RulesPanel.vue';
import KeysView from './KeysView.vue';
import Icon from '../components/Icon.vue';
import type { ModelInfo, HealthInfo, ChatDefaults } from '../api/settings';
import type { McpStatusResponse } from '../api/mcp';

const router = useRouter();
const chat = useChatStore();
const theme = useThemeStore();
const auth = useAuthStore();
const meta = useMetaStore();

const version = '1.0.0';

const BASE_TABS = [
  { id: 'chat', label: '对话默认', icon: 'cpu' },
  { id: 'models', label: '模型服务', icon: 'key' },
  { id: 'rules', label: 'Agent 指令', icon: 'file-text' },
  { id: 'mcp', label: 'MCP 工具', icon: 'plug' },
  { id: 'appearance', label: '外观', icon: 'palette' },
  { id: 'account', label: '账号安全', icon: 'user' },
] as const;

/**
 * 能力决定 tab 可见性：
 * - MCP 只在桌面端注册，网页端不展示；
 * - 桌面端是本地单用户，没有账号/密码概念，隐藏账号安全。
 */
const tabs = computed(() =>
  BASE_TABS.filter((tab) => {
    if (tab.id === 'mcp') return meta.has('MCP_READ');
    if (tab.id === 'account') return meta.mode !== 'DESKTOP';
    return true;
  })
);

type TabId = typeof BASE_TABS[number]['id'];
const activeTab = ref<TabId>('chat');

// meta 晚于首次渲染返回时 tab 会变少，避免停在被隐藏的 tab 上出现空白
watch(tabs, (list) => {
  if (!list.some((tab) => tab.id === activeTab.value)) {
    activeTab.value = 'chat';
  }
});

// 对话默认 —— 本地副本避免写一半错乱
const local = reactive<{
  defaultModel: string;
  defaultReasoning: ChatDefaults['reasoning'];
  defaultPermission: ChatDefaults['permission'];
  mcpEnabled: boolean;
}>({
  defaultModel: chat.defaultModel,
  defaultReasoning: chat.defaultReasoning,
  defaultPermission: chat.defaultPermission,
  mcpEnabled: true
});

watch(
  () => [chat.defaultModel, chat.defaultReasoning, chat.defaultPermission] as const,
  () => {
    local.defaultModel = chat.defaultModel;
    local.defaultReasoning = chat.defaultReasoning;
    local.defaultPermission = chat.defaultPermission;
  }
);

// 账号信息（内嵌 ProfileView 的逻辑，不跳路由）
const account = reactive({
  username: '',
  email: '',
  oldPwd: '',
  newPwd: '',
  confirmPwd: '',
  status: '',
  statusIsError: false,
  changing: false,
});

// 模型列表：后端 /api/models（available 取决于是否配了 provider 的 API Key）
// 后端没跑时返回空 → 前端兜底硬编码
const models = ref<ModelInfo[]>([]);
const modelsLoading = ref(false);

async function loadModels() {
  modelsLoading.value = true;
  try {
    models.value = await settingsApi.listModels();
  } catch {
    // 后端未就绪时兜底硬编码
    models.value = [
      { id: 'deepseek-chat', name: 'DeepSeek Chat', provider: 'DEEPSEEK', available: true },
      { id: 'deepseek-reasoner', name: 'DeepSeek Reasoner', provider: 'DEEPSEEK', available: true },
    ];
  } finally {
    modelsLoading.value = false;
  }
}

/** 旧版持久化的权限值（EXECUTE/WRITE）映射到新的「手动审批」档 */
function normalizePermission(p: string | undefined): ChatDefaults['permission'] {
  if (p === 'AUTO' || p === 'FULL' || p === 'READ_ONLY') return p;
  return 'MANUAL';
}

async function loadDefaults() {
  try {
    const d = await settingsApi.getDefaults();
    local.defaultModel = d.model;
    local.defaultReasoning = d.reasoning;
    local.defaultPermission = normalizePermission(d.permission);
    // 同步到 chatStore（新会话启动用 store 的 default 字段）
    chat.defaultModel = d.model;
    chat.defaultReasoning = d.reasoning;
    chat.defaultPermission = normalizePermission(d.permission);
  } catch { /* 静默，store 里的默认值已经够了 */ }
}

// MCP
const mcpStatus = ref<McpStatusResponse | null>(null);
const mcpLoading = ref(false);

// Health
const health = ref<HealthInfo | null>(null);

onMounted(async () => {
  if (meta.has('MCP_READ')) loadMcp();
  loadHealth();
  await loadAccount();
  loadDefaults();
  loadModels();
});

async function loadMcp() {
  mcpLoading.value = true;
  try {
    const status = await mcpApi.getMcpStatus();
    mcpStatus.value = status;
    local.mcpEnabled = status.enabled;
  } catch { /* 静默 */ }
  finally { mcpLoading.value = false; }
}

async function loadHealth() {
  try { health.value = await settingsApi.getHealth(); } catch { /* 静默 */ }
}

async function loadAccount() {
  try {
    const me = await authApi.getMe();
    account.username = me.username || '';
    account.email = me.email || '';
  } catch { /* 静默 */ }
}

async function saveChatDefaults() {
  chat.defaultModel = local.defaultModel;
  chat.defaultReasoning = local.defaultReasoning;
  chat.defaultPermission = local.defaultPermission;
  try {
    await settingsApi.updateDefaults({
      model: local.defaultModel,
      reasoning: local.defaultReasoning,
      permission: local.defaultPermission,
    });
    ElMessage.success('默认值已保存');
  } catch {
    // 后端未就绪也不阻塞——store 里的值在当前会话已经生效
    ElMessage.success('默认值已保存（后端未就绪，当前会话已生效）');
  }
}

async function saveMcpEnabled() {
  try {
    await mcpApi.setMcpEnabled(local.mcpEnabled);
    ElMessage.success(local.mcpEnabled ? 'MCP 已启用' : 'MCP 已禁用');
  } catch {
    ElMessage.warning('保存失败，后端接口暂未就绪');
  }
}

function onAccountUpdate() {
  ElMessage.success('保存成功（演示）');
}

async function onPasswordChange() {
  if (!account.oldPwd || !account.newPwd) {
    account.status = '密码不能为空';
    account.statusIsError = true;
    return;
  }
  if (account.newPwd !== account.confirmPwd) {
    account.status = '两次输入不一致';
    account.statusIsError = true;
    return;
  }
  account.changing = true;
  try {
    const oldEnc = await encryptPassword(account.oldPwd);
    const newEnc = await encryptPassword(account.newPwd);
    await authApi.changePassword({ oldPassword: oldEnc, newPassword: newEnc });
    account.status = '修改成功，请重新登录';
    account.statusIsError = false;
    account.oldPwd = '';
    account.newPwd = '';
    account.confirmPwd = '';
    setTimeout(async () => {
      await auth.clearLocal();
      router.push('/login');
    }, 1500);
  } catch (e) {
    account.status = (e as Error).message;
    account.statusIsError = true;
  } finally {
    account.changing = false;
  }
}

function formatUptime(ms: number): string {
  const sec = Math.floor(ms / 1000);
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ${sec % 60}s`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ${min % 60}m`;
  const day = Math.floor(hr / 24);
  return `${day}d ${hr % 24}h`;
}
</script>

<style scoped>
.settings-page { padding: 0; }

.settings-layout {
  display: flex;
  min-height: 100%;
  max-width: 1000px;
}

/* 左侧 tab 导航 */
.settings-nav {
  width: 200px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  padding: 16px 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  background: var(--surface);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  text-align: left;
  transition: all 0.15s;
}

.nav-item:hover {
  background: var(--surface-hover);
  color: var(--text);
}

.nav-item.active {
  background: color-mix(in srgb, var(--primary) 12%, transparent);
  color: var(--primary);
  font-weight: 600;
}

.nav-spacer { flex: 1; }

.nav-version {
  font-size: 11px;
  color: var(--text-tertiary);
  padding: 4px 12px;
}

/* 右侧内容区 */
.settings-content {
  flex: 1;
  padding: 24px 28px;
  overflow-y: auto;
}

.section {
  max-width: 720px;
}

/* 模型服务直接复用 KeysView：去掉它页面级的内边距/滚动，避免在设置页里出现双层容器 */
.keys-section {
  max-width: none;
}
.keys-section :deep(.page) {
  padding: 0;
  overflow: visible;
  min-height: auto;
  animation: none;
}

.section-title {
  font-size: 18px;
  font-weight: 700;
  margin: 0 0 4px;
}

.section-desc {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0 0 20px;
  line-height: 1.6;
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 18px 20px;
  margin-bottom: 14px;
}

.card h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  margin: 0 0 12px;
  color: var(--text-primary);
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.card-head h3 { margin: 0; }

.full-width { width: 100%; }

.hint {
  font-size: 11px;
  color: var(--text-tertiary);
  margin: 8px 0 0;
  line-height: 1.5;
}

/* 快捷键表格 */
.shortcuts {
  width: 100%;
  border-collapse: collapse;
}

.shortcuts td {
  padding: 6px 0;
  font-size: 12px;
  color: var(--text-secondary);
}

.shortcuts td:first-child {
  width: 180px;
  font-family: monospace;
}

kbd {
  display: inline-block;
  padding: 1px 6px;
  font-size: 11px;
  font-family: inherit;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 4px;
  color: var(--text-primary);
  margin: 0 1px;
}

/* scope 徽章 */
.scope-chip {
  display: inline-block;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 500;
  margin: 0 2px;
}
.scope-chip.global { background: rgba(99,102,241,0.12); color: #6366f1; }
.scope-chip.project { background: rgba(16,185,129,0.12); color: #10b981; }
.scope-chip.session { background: rgba(245,158,11,0.12); color: #f59e0b; }
.scope-arrow { color: var(--text-tertiary); margin: 0 2px; font-size: 12px; }

.option-sub {
  color: var(--text-tertiary);
  margin-left: 8px;
  font-size: 11px;
}

/* MCP */
.mcp-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.mcp-header h3 { margin: 0; }

.refresh-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
}
.refresh-btn:hover { background: var(--surface-hover); color: var(--text); }

.mcp-list { display: flex; flex-direction: column; gap: 10px; }

.mcp-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--surface-2);
}

.mcp-info { flex: 1; min-width: 0; }
.mcp-name { font-weight: 600; font-size: 13px; }
.mcp-status-text { font-size: 11px; color: var(--text-secondary); margin-top: 2px; }
.fail-count { color: #f59e0b; }
.mcp-error { font-size: 11px; color: #ef4444; margin-top: 4px; width: 100%; }

.mcp-dot {
  width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; flex-shrink: 0;
}
.mcp-dot.mcp-up { background: #22c55e; }
.mcp-dot.mcp-down { background: #ef4444; }
.mcp-dot.mcp-unknown { background: #eab308; }

.loading, .empty {
  text-align: center;
  padding: 24px;
  color: var(--text-tertiary);
}
.empty p { margin: 6px 0 0; font-size: 13px; color: var(--text-secondary); }
.empty .hint { margin-top: 4px; }

/* 外观：主题预览 */
.theme-options {
  display: flex;
  gap: 12px;
}

.theme-preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 12px;
  border: 2px solid transparent;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-secondary);
  transition: all 0.15s;
}

.theme-preview:hover { background: var(--surface-hover); }
.theme-preview.active { border-color: var(--primary); color: var(--primary); }

.preview-light {
  width: 48px; height: 32px; border-radius: 6px;
  background: linear-gradient(135deg, #fff, #e5e7eb);
  border: 1px solid #d1d5db;
}
.preview-dark {
  width: 48px; height: 32px; border-radius: 6px;
  background: linear-gradient(135deg, #1f2937, #111827);
  border: 1px solid #374151;
}

/* 关于 */
.about-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 13px;
}
.about-label { color: var(--text-secondary); }
.about-value { color: var(--text-primary); }

.status-dot {
  display: inline-block;
  width: 8px; height: 8px; border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}
.status-dot.up { background: #22c55e; }
.status-dot.down { background: #ef4444; }
</style>
