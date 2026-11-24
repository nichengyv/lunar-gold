<template>
  <div class="mcp-panel">
    <div class="mcp-panel-header">
      <div>
        <div class="mcp-panel-title">
          MCP Servers
          <el-popover placement="bottom-start" trigger="click" width="300">
            <template #reference>
              <button class="mcp-help-link">从哪获取配置？</button>
            </template>
            <div class="mcp-help">
              <p>从 server 介绍页复制配置 JSON，粘贴到「添加」里即可。</p>
              <p>
                <a href="https://github.com/modelcontextprotocol/servers" target="_blank" rel="noopener">官方参考实现</a> ·
                <a href="https://mcp.so" target="_blank" rel="noopener">mcp.so</a> ·
                <a href="https://smithery.ai" target="_blank" rel="noopener">Smithery</a> ·
                <a href="https://glama.ai/mcp/servers" target="_blank" rel="noopener">Glama</a>
              </p>
            </div>
          </el-popover>
        </div>
        <div class="mcp-panel-desc">
          接入本机 stdio MCP server，命令以当前用户权限在本机执行。
        </div>
      </div>
      <div class="mcp-header-actions">
        <input
          ref="fileInput"
          type="file"
          accept=".json,application/json"
          class="mcp-file-input"
          @change="importFile"
        />
        <el-button size="small" @click="triggerImport">导入文件</el-button>
        <el-button size="small" :disabled="servers.length === 0" @click="exportConfig">导出配置</el-button>
        <el-button type="primary" size="small" @click="openCreate">
          <Icon name="plus" :size="13" /> 添加
        </el-button>
      </div>
    </div>

    <div v-if="loading" class="mcp-empty">加载中…</div>
    <div v-else-if="servers.length === 0" class="mcp-empty">
      还没有配置 MCP server。粘贴配置 JSON 即可接入，配置可从 mcp.so 等目录获取。
    </div>

    <div v-for="server in servers" :key="server.id" class="mcp-server">
      <div class="mcp-server-main">
        <div class="mcp-server-title">
          <span class="mcp-name">{{ server.name }}</span>
          <span class="mcp-state" :class="stateClass(server)">{{ stateLabel(server) }}</span>
          <button
            v-if="server.connected"
            class="mcp-tools mcp-tools-btn"
            :class="{ open: expandedId === server.id }"
            @click="toggleTools(server)"
          >
            {{ server.toolCount }} 个工具 {{ expandedId === server.id ? '▴' : '▾' }}
          </button>
        </div>
        <div class="mcp-command">{{ server.command }} {{ server.args.join(' ') }}</div>
        <div v-if="server.envKeys.length" class="mcp-env">
          环境变量：{{ server.envKeys.join(', ') }}
        </div>
        <div v-if="server.lastError" class="mcp-error">
          <Icon name="alert" :size="12" /> {{ server.lastError }}
        </div>
        <div v-if="expandedId === server.id" class="mcp-tool-list">
          <div v-if="toolsLoading" class="mcp-tool-empty">加载中…</div>
          <div v-else-if="toolCache[server.id]?.length" class="mcp-tool-rows">
            <div v-for="t in toolCache[server.id]" :key="t.name" class="mcp-tool-row">
              <span class="mcp-tool-name">{{ t.name }}</span>
              <span class="mcp-tool-desc">{{ t.description || '无描述' }}</span>
            </div>
          </div>
          <div v-else class="mcp-tool-empty">没有可用工具，或连接尚未就绪。</div>
        </div>
      </div>

      <div class="mcp-server-actions">
        <el-switch
          :model-value="server.enabled"
          :disabled="busyId === server.id"
          @change="(value: boolean) => toggle(server, value)"
        />
        <button
          class="mcp-icon-btn"
          title="测试连接"
          :disabled="busyId === server.id"
          @click="reload(server)"
        >
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
      :title="editingId == null ? '手动配置' : '编辑 MCP Server'"
      width="560px"
      :close-on-click-modal="false"
    >
      <div class="mcp-form">
        <div v-if="editingId == null" class="mcp-mode-switch">
          <el-button size="small" :type="addMode === 'json' ? 'primary' : undefined" @click="addMode = 'json'">
            粘贴 JSON
          </el-button>
          <el-button size="small" :type="addMode === 'form' ? 'primary' : undefined" @click="addMode = 'form'">
            表单填写
          </el-button>
          <span class="mcp-mode-hint">也可以切到表单逐项填写</span>
        </div>

        <p v-if="isJsonMode" class="mcp-dialog-desc">
          请从 MCP Server 的介绍页复制配置 JSON（优先使用 npx 或 uvx 配置），粘贴到下方输入框。
        </p>

        <template v-if="isJsonMode">
          <el-input
            v-model="jsonText"
            type="textarea"
            :rows="14"
            class="mcp-json-input"
            :input-style="jsonInputStyle"
            :placeholder="jsonPlaceholder"
          />
          <div v-if="jsonError" class="mcp-json-error">
            <Icon name="alert" :size="12" /> {{ jsonError }}
          </div>
          <div class="mcp-warning">
            <Icon name="alert" :size="13" />
            配置前请确认来源，甄别风险。
          </div>
        </template>

        <template v-else>
          <label class="mcp-field">
            <span>名称</span>
            <el-input v-model="form.name" placeholder="例如 github、postgres" maxlength="40" />
            <small>用于工具名前缀，添加后不要随意修改。</small>
          </label>
          <label class="mcp-field">
            <span>命令</span>
            <el-input v-model="form.command" placeholder="例如 npx、uvx、node" />
            <small>Windows 下 npx / npm / pnpm / yarn 会自动补 .cmd，其他命令请写完整名称。</small>
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
        </template>
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
const addMode = ref<'json' | 'form'>('json');
const jsonText = ref('');
const jsonError = ref('');
const fileInput = ref<HTMLInputElement | null>(null);
const expandedId = ref<number | null>(null);
const toolsLoading = ref(false);
const toolCache = reactive<Record<number, mcpApi.McpToolInfo[]>>({});

/** 仅「新增」支持 JSON 粘贴；编辑走表单（凭证值不回显，无法还原 JSON） */
const isJsonMode = computed(() => editingId.value == null && addMode.value === 'json');

const jsonInputStyle = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
  fontSize: '12px',
  lineHeight: '1.6'
};

const jsonPlaceholder = [
  '// 示例：',
  '// {',
  '//   "mcpServers": {',
  '//     "example-server": {',
  '//       "command": "npx",',
  '//       "args": ["-y", "mcp-server-example"]',
  '//     }',
  '//   }',
  '// }',
].join('\n');

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
  addMode.value = 'json';
  jsonText.value = '';
  jsonError.value = '';
  Object.assign(form, { name: '', command: '', argsText: '', envText: '', enabled: true });
  dialogVisible.value = true;
}

function openEdit(server: McpServerConfig) {
  editingId.value = server.id;
  existingEnvKeys.value = server.envKeys;
  clearEnv.value = false;
  addMode.value = 'form';
  jsonError.value = '';
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
  if (isJsonMode.value) {
    await saveFromJson();
    return;
  }
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

/** JSON 粘贴导入：支持 Claude Desktop 的 mcpServers 包裹、单 server、以及 {名称: 配置} 映射 */
async function saveFromJson() {
  jsonError.value = '';
  let entries: ParsedServer[];
  try {
    entries = parseServersJson(jsonText.value);
  } catch (e) {
    jsonError.value = errorText(e);
    return;
  }
  saving.value = true;
  try {
    for (const entry of entries) {
      await mcpApi.createMcpServer({
        name: entry.name,
        command: entry.command,
        args: entry.args,
        env: entry.env ?? {},
        enabled: true
      });
    }
    dialogVisible.value = false;
    ElMessage.success(
      entries.length > 1 ? `已导入 ${entries.length} 个 MCP server，正在连接` : '已保存，正在连接 MCP server'
    );
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    saving.value = false;
    await load();
  }
}

interface ParsedServer {
  name: string;
  command: string;
  args: string[];
  env: Record<string, string> | null;
}

function parseServersJson(text: string): ParsedServer[] {
  const raw = text.trim();
  if (!raw) {
    throw new Error('请先粘贴 MCP server 配置 JSON');
  }
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    throw new Error('JSON 格式有误，请检查括号、引号与逗号');
  }
  if (!isPlainObject(data)) {
    throw new Error('JSON 顶层必须是对象');
  }

  let entries: Array<[string, Record<string, unknown>]>;
  if (isPlainObject(data.mcpServers)) {
    entries = Object.entries(data.mcpServers)
      .filter(([, value]) => isPlainObject(value))
      .map(([key, value]) => [key, value as Record<string, unknown>]);
  } else if (typeof data.command === 'string') {
    entries = [[typeof data.name === 'string' ? data.name : '', data]];
  } else {
    entries = Object.entries(data)
      .filter(([, value]) => isPlainObject(value) && typeof (value as Record<string, unknown>).command === 'string')
      .map(([key, value]) => [key, value as Record<string, unknown>]);
  }

  if (entries.length === 0) {
    throw new Error('没找到配置：需要 command 字段，或用 {"mcpServers": {...}} 包裹');
  }

  return entries.map(([key, value], index) => {
    const command = typeof value.command === 'string' ? value.command.trim() : '';
    if (!command) {
      throw new Error(`第 ${index + 1} 项缺少 command`);
    }
    const name = (typeof value.name === 'string' && value.name.trim()) || key.trim() || command;
    const args = Array.isArray(value.args) ? value.args.map(item => String(item)) : [];
    let env: Record<string, string> | null = null;
    if (isPlainObject(value.env)) {
      env = {};
      for (const [envKey, envValue] of Object.entries(value.env)) {
        env[envKey] = String(envValue);
      }
    }
    return { name, command, args, env };
  });
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
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
    delete toolCache[server.id];
    if (expandedId.value === server.id) {
      await loadTools(server.id);
    }
    if (updated.connected) {
      ElMessage.success(`连接正常，共 ${updated.toolCount} 个工具`);
    } else {
      ElMessage.error(`连接失败：${updated.lastError || '请查看后端日志'}`);
    }
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    busyId.value = null;
  }
}

async function loadTools(id: number) {
  toolsLoading.value = true;
  try {
    toolCache[id] = await mcpApi.getMcpServerTools(id);
  } catch (e) {
    ElMessage.error(errorText(e));
  } finally {
    toolsLoading.value = false;
  }
}

async function toggleTools(server: McpServerConfig) {
  if (expandedId.value === server.id) {
    expandedId.value = null;
    return;
  }
  expandedId.value = server.id;
  if (!toolCache[server.id]) {
    await loadTools(server.id);
  }
}

function triggerImport() {
  fileInput.value?.click();
}

async function importFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  try {
    const text = await file.text();
    openCreate();
    jsonText.value = text;
    ElMessage.info('已读取配置，检查无误后点保存');
  } catch {
    ElMessage.error('读取文件失败');
  }
}

function exportConfig() {
  const mcpServers: Record<string, unknown> = {};
  for (const server of servers.value) {
    const env: Record<string, string> = {};
    server.envKeys.forEach(key => {
      env[key] = '';
    });
    mcpServers[server.name] = {
      command: server.command,
      args: server.args,
      ...(server.envKeys.length ? { env } : {})
    };
  }
  const blob = new Blob([JSON.stringify({ mcpServers }, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'mcp-servers.json';
  link.click();
  URL.revokeObjectURL(url);
  ElMessage.success('已导出（环境变量值不会导出，导入后需重新填写）');
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
    delete toolCache[server.id];
    if (expandedId.value === server.id) {
      expandedId.value = null;
    }
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
.mcp-mode-switch {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.mcp-mode-hint {
  font-size: 11px;
  color: var(--text-muted);
}
.mcp-json-error {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  font-size: 11px;
  line-height: 1.5;
  color: #c2410c;
  word-break: break-word;
}
.mcp-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.mcp-file-input {
  display: none;
}
.mcp-tools-btn {
  padding: 0;
  border: none;
  background: none;
  font-family: inherit;
  cursor: pointer;
}
.mcp-tools-btn:hover,
.mcp-tools-btn.open {
  color: var(--el-color-primary, #409eff);
}
.mcp-tool-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--border);
  max-height: 220px;
  overflow: auto;
}
.mcp-tool-row {
  display: flex;
  gap: 8px;
  font-size: 11px;
  line-height: 1.5;
}
.mcp-tool-name {
  flex-shrink: 0;
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--text);
}
.mcp-tool-desc {
  color: var(--text-muted);
}
.mcp-tool-empty {
  font-size: 11px;
  color: var(--text-muted);
}
.mcp-help-link {
  margin-left: 8px;
  padding: 0;
  border: none;
  background: none;
  font-size: 11px;
  font-weight: 400;
  color: var(--el-color-primary, #409eff);
  cursor: pointer;
}
.mcp-help p {
  margin: 0 0 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
.mcp-help p:last-child {
  margin-bottom: 0;
}
.mcp-help a {
  color: var(--el-color-primary, #409eff);
  text-decoration: none;
}
.mcp-dialog-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
</style>
