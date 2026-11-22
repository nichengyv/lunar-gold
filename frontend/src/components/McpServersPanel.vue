<template>
  <div class="mcp-panel">
    <div class="mcp-panel-header">
      <div>
        <div class="mcp-panel-title">MCP Servers</div>
        <div class="mcp-panel-desc">
          接入本机 stdio MCP server。命令会在本机启动进程，只添加你信任的 server。
        </div>
      </div>
      <el-button type="primary" size="small" @click="openCreate">
        <Icon name="plus" :size="13" /> 添加
      </el-button>
    </div>

    <div v-if="loading" class="mcp-empty">加载中…</div>
    <div v-else-if="servers.length === 0" class="mcp-empty">
      还没有配置 MCP server。添加后 Agent 会自动发现它提供的工具。
    </div>

    <div v-for="server in servers" :key="server.id" class="mcp-server">
      <div class="mcp-server-main">
        <div class="mcp-server-title">
          <span class="mcp-name">{{ server.name }}</span>
          <span class="mcp-state" :class="stateClass(server)">{{ stateLabel(server) }}</span>
          <span v-if="server.connected" class="mcp-tools">{{ server.toolCount }} 个工具</span>
        </div>
        <div class="mcp-command">{{ server.command }} {{ server.args.join(' ') }}</div>
        <div v-if="server.envKeys.length" class="mcp-env">
          环境变量：{{ server.envKeys.join(', ') }}
        </div>
        <div v-if="server.lastError" class="mcp-error">
          <Icon name="alert" :size="12" /> {{ server.lastError }}
        </div>
      </div>

      <div class="mcp-server-actions">
        <el-switch
          :model-value="server.enabled"
          :disabled="busyId === server.id"
          @change="(value: boolean) => toggle(server, value)"
        />
        <button class="mcp-icon-btn" title="重连" :disabled="busyId === server.id" @click="reload(server)">
          <Icon name="refresh" :size="13" />
        </button>
        <button class="mcp-icon-btn" title="编辑" @click="openEdit(server)">
          <Icon name="file-text" :size="13" />
        </button>
        <button class="mcp-icon-btn danger" title="删除" @click="remove(server)">
          <Icon name="trash" :size="13" />
        </button>
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? '添加 MCP Server' : '编辑 MCP Server'"
      width="560px"
      :close-on-click-modal="false"
    >
      <div class="mcp-form">
        <label class="mcp-field">
          <span>名称</span>
          <el-input v-model="form.name" placeholder="例如 github、postgres" maxlength="40" />
          <small>用于工具名前缀，添加后不要随意修改。</small>
        </label>
        <label class="mcp-field">
          <span>命令</span>
          <el-input v-model="form.command" placeholder="例如 npx、uvx、node" />
          <small>Windows 下 .cmd 需要写全名，例如 npx.cmd。</small>
        </label>
        <label class="mcp-field">
          <span>启动参数</span>
          <el-input
            v-model="form.argsText"
            type="textarea"
            :rows="3"
            placeholder="每行一个参数，例如&#10;-y&#10;@modelcontextprotocol/server-github"
          />
        </label>
        <label class="mcp-field">
          <span>环境变量</span>
          <el-input
            v-model="form.envText"
            type="textarea"
            :rows="3"
            :placeholder="envPlaceholder"
          />
          <small>每行一个 KEY=VALUE；值只写不读，保存后不再回显。</small>
        </label>
        <div v-if="editingId != null && existingEnvKeys.length" class="mcp-env-existing">
          已保存：{{ existingEnvKeys.join(', ') }}
          <el-button link type="danger" size="small" @click="clearEnv = true; form.envText = ''">
            清空
          </el-button>
        </div>
        <label class="mcp-field inline">
          <span>启用</span>
          <el-switch v-model="form.enabled" />
        </label>
        <div class="mcp-warning">
          <Icon name="alert" :size="13" />
          命令会以当前用户权限在本机执行。只添加可信来源，并要求其通过审批后才会被模型调用。
        </div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as mcpApi from '../api/mcp';
import type { McpServerConfig } from '../api/mcp';
import Icon from './Icon.vue';

const servers = ref<McpServerConfig[]>([]);
const loading = ref(false);
const saving = ref(false);
const busyId = ref<number | null>(null);
const dialogVisible = ref(false);
const editingId = ref<number | null>(null);
const existingEnvKeys = ref<string[]>([]);
const clearEnv = ref(false);

const form = reactive({
  name: '',
  command: '',
  argsText: '',
  envText: '',
  enabled: true
});

const envPlaceholder = computed(() =>
  editingId.value == null
    ? '例如\nGITHUB_TOKEN=xxx'
    : '留空 = 保持现有变量；填写则整体替换'
);

onMounted(load);

async function load() {
  loading.value = true;
  try {
    servers.value = await mcpApi.listMcpServers();
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editingId.value = null;
  existingEnvKeys.value = [];
  clearEnv.value = false;
  Object.assign(form, { name: '', command: '', argsText: '', envText: '', enabled: true });
  dialogVisible.value = true;
}

function openEdit(server: McpServerConfig) {
  editingId.value = server.id;
  existingEnvKeys.value = server.envKeys;
  clearEnv.value = false;
  Object.assign(form, {
    name: server.name,
    command: server.command,
    argsText: server.args.join('\n'),
    envText: '',
    enabled: server.enabled
  });
  dialogVisible.value = true;
}

async function save() {
  const args = form.argsText.split('\n').map(line => line.trim()).filter(Boolean);
  let env: Record<string, string> | null;
  if (clearEnv.value) {
    env = {};
  } else if (form.envText.trim()) {
    try {
      env = parseEnv(form.envText);
    } catch (e) {
      ElMessage.warning(errorText(e));
      return;
    }
  } else {
    env = editingId.value == null ? {} : null;
  }

  const input: mcpApi.McpServerInput = {
    name: form.name.trim(),
    command: form.command.trim(),
    args,
    env,
    enabled: form.enabled
  };
  saving.value = true;
  try {
    if (editingId.value == null) {
      await mcpApi.createMcpServer(input);
    } else {
      await mcpApi.updateMcpServer(editingId.value, input);
    }
    dialogVisible.value = false;
    ElMessage.success('已保存，正在连接 MCP server');
    await load();
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    saving.value = false;
  }
}

async function toggle(server: McpServerConfig, enabled: boolean) {
  busyId.value = server.id;
  try {
    const updated = await mcpApi.setMcpServerEnabled(server.id, enabled);
    replace(updated);
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busyId.value = null;
  }
}

async function reload(server: McpServerConfig) {
  busyId.value = server.id;
  try {
    const updated = await mcpApi.reloadMcpServer(server.id);
    replace(updated);
    ElMessage.success(updated.connected ? '已重连' : '重连失败，请查看错误信息');
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busyId.value = null;
  }
}

async function remove(server: McpServerConfig) {
  try {
    await ElMessageBox.confirm(
      `删除后 ${server.name} 提供的工具会立即从 Agent 中移除，确定继续？`,
      '删除 MCP Server',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  busyId.value = server.id;
  try {
    await mcpApi.deleteMcpServer(server.id);
    servers.value = servers.value.filter(item => item.id !== server.id);
    ElMessage.success('已删除');
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busyId.value = null;
  }
}

function replace(updated: McpServerConfig) {
  servers.value = servers.value.map(item => item.id === updated.id ? updated : item);
}

function stateLabel(server: McpServerConfig) {
  if (!server.enabled) return '已停用';
  return server.connected ? '已连接' : '连接失败';
}

function stateClass(server: McpServerConfig) {
  if (!server.enabled) return 'off';
  return server.connected ? 'up' : 'down';
}

function parseEnv(text: string): Record<string, string> {
  const result: Record<string, string> = {};
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const index = line.indexOf('=');
    if (index <= 0) {
      throw new Error(`环境变量格式错误：${line}`);
    }
    result[line.slice(0, index).trim()] = line.slice(index + 1);
  }
  return result;
}

function errorText(error: unknown) {
  const anyError = error as { response?: { data?: { message?: string } }; message?: string };
  return anyError?.response?.data?.message || anyError?.message || '操作失败';
}
</script>

<style scoped>
.mcp-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.mcp-panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.mcp-panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}
.mcp-panel-desc {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
.mcp-empty {
  padding: 18px;
  border: 1px dashed var(--border);
  border-radius: 8px;
  font-size: 12px;
  color: var(--text-muted);
}
.mcp-server {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
}
.mcp-server-main {
  min-width: 0;
}
.mcp-server-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.mcp-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}
.mcp-state {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 11px;
  border: 1px solid var(--border);
  color: var(--text-muted);
}
.mcp-state.up {
  color: #1a7f37;
  border-color: #1a7f3755;
  background: #1a7f3712;
}
.mcp-state.down {
  color: #c2410c;
  border-color: #c2410c55;
  background: #c2410c12;
}
.mcp-tools {
  font-size: 11px;
  color: var(--text-muted);
}
.mcp-command {
  margin-top: 6px;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-muted);
  word-break: break-all;
}
.mcp-env {
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-muted);
}
.mcp-error {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin-top: 6px;
  font-size: 11px;
  line-height: 1.5;
  color: #c2410c;
  word-break: break-word;
}
.mcp-server-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.mcp-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}
.mcp-icon-btn:hover:not(:disabled) {
  background: var(--surface-hover);
  color: var(--text);
}
.mcp-icon-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.mcp-icon-btn.danger:hover {
  color: #dc2626;
  border-color: #dc262655;
}
.mcp-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.mcp-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--text);
}
.mcp-field.inline {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
}
.mcp-field small {
  font-size: 11px;
  color: var(--text-muted);
}
.mcp-env-existing {
  font-size: 11px;
  color: var(--text-muted);
}
.mcp-warning {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-muted);
  background: var(--surface-2);
}
</style>
