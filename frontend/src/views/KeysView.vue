<template>
  <div class="page">
    <div class="page-container">
      <div class="card">
        <h3><Icon name="key" :size="16" /> API Key 管理</h3>
        <div v-if="loadError" class="empty-state" style="color: var(--danger)">加载失败: {{ loadError }}</div>
        <div v-else-if="keys.length === 0" class="empty-state">
          <div class="empty-state-icon"><Icon name="lock" :size="32" /></div>
          <div>暂无 API Key</div>
        </div>
        <table v-else class="keys-table">
          <thead>
            <tr>
              <th>提供商</th>
              <th>别名</th>
              <th>创建时间</th>
              <th style="width: 80px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="k in keys" :key="k.id">
              <td><span class="key-badge">{{ k.provider }}</span></td>
              <td>{{ k.alias || '-' }}</td>
              <td>{{ formatDateTime(k.createTime) }}</td>
              <td>
                <button class="btn-small btn-small-danger delete-key-btn" style="padding: 4px 10px; font-size: 12px" @click="onDelete(k.id)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <h3><Icon name="plus" :size="15" /> 添加新 Key</h3>
        <div class="form-row">
          <label>提供商</label>
          <select v-model="provider">
            <option value="DEEPSEEK">DeepSeek（对话）</option>
            <option value="SILICONFLOW">SiliconFlow（向量）</option>
            <option value="TAVILY">Tavily（联网搜索）</option>
          </select>
        </div>
        <div class="form-row">
          <label>API Key</label>
          <input v-model.trim="apiKey" type="password" placeholder="sk-...">
        </div>
        <div class="form-row">
          <label>别名（可选）</label>
          <input v-model.trim="alias" type="text" placeholder="默认Key">
        </div>
        <button class="btn-small" style="margin-right: 8px" :disabled="testing" @click="onTest">测试连接</button>
        <button class="btn-small btn-small-primary" :disabled="saving" @click="onSave">保存 Key</button>
        <span class="status-text" :class="{ error: statusIsError }">{{ status }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as keysApi from '../api/keys';
import type { KeyInfo } from '../types/api';
import { formatDateTime } from '../utils/format';
import Icon from '../components/Icon.vue';

const keys = ref<KeyInfo[]>([]);
const loadError = ref('');
const provider = ref('DEEPSEEK');
const apiKey = ref('');
const alias = ref('');
const status = ref('');
const statusIsError = ref(false);
const saving = ref(false);
const testing = ref(false);

onMounted(loadKeys);

async function loadKeys() {
  try {
    keys.value = await keysApi.listKeys();
    loadError.value = '';
  } catch (e) {
    loadError.value = (e as Error).message;
  }
}

function setStatus(msg: string, isError = false) {
  status.value = msg;
  statusIsError.value = isError;
}

async function onSave() {
  if (!apiKey.value) {
    setStatus('Key 不能为空', true);
    return;
  }
  saving.value = true;
  try {
    await keysApi.saveKey({ provider: provider.value, apiKey: apiKey.value, alias: alias.value || provider.value });
    setStatus('保存成功');
    apiKey.value = '';
    alias.value = '';
    await loadKeys();
  } catch (e) {
    setStatus((e as Error).message, true);
  } finally {
    saving.value = false;
  }
}

async function onTest() {
  if (!apiKey.value) {
    setStatus('Key 不能为空', true);
    return;
  }
  setStatus('正在测试...');
  testing.value = true;
  try {
    await keysApi.testKey({ provider: provider.value, apiKey: apiKey.value });
    setStatus('连接成功，Key 可用');
  } catch (e) {
    setStatus((e as Error).message, true);
  } finally {
    testing.value = false;
  }
}

async function onDelete(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该 Key？', '提示', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await keysApi.deleteKey(id);
    await loadKeys();
  } catch (e) {
    ElMessage.error('删除失败: ' + (e as Error).message);
  }
}
</script>
