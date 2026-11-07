<template>
  <div class="page">
    <div class="page-container">
      <div class="card">
        <h3><Icon name="book" :size="16" /> 我的资料<span v-if="project.currentProject" class="project-hint">· {{ project.currentProject.name }}</span></h3>
        <div v-if="loadError" class="empty-state" style="color: var(--danger)">加载失败: {{ loadError }}</div>
        <div v-else-if="docs.length === 0" class="empty-state">
          <div class="empty-state-icon"><Icon name="folder" :size="28" /></div>
          <div>还没有资料</div>
          <div style="font-size: 13px; margin-top: 8px">添加 PDF、Word、TXT 等文件，之后对话时可以直接提问</div>
        </div>
        <div v-else class="doc-grid">
          <div v-for="d in docs" :key="d.id" class="doc-card">
            <div class="doc-icon"><Icon :name="getFileIcon(d.fileType)" :size="28" /></div>
            <div class="doc-info">
              <div class="doc-name" :title="d.fileName">{{ d.fileName }}</div>
              <div class="doc-meta">
                <span class="doc-type">{{ d.fileType.toUpperCase() }}</span>
                <span class="doc-size">{{ formatFileSize(d.fileSize) }}</span>
                <span class="doc-status" :class="statusClass(d.status)">{{ statusText(d.status) }}</span>
                <button
                  v-if="d.status === 'ERROR'"
                  class="doc-retry"
                  title="重新处理这份资料"
                  @click="onRetry(d.id)"
                ><Icon name="refresh" :size="11" /> 重试</button>
              </div>
              <div class="doc-meta">添加于 {{ formatDateTime(d.createTime) }}</div>
              <div v-if="d.description" class="doc-desc">{{ d.description }}</div>
            </div>
            <button class="doc-delete" title="删除" @click="onDelete(d.id)"><Icon name="x" :size="14" /></button>
          </div>
        </div>
      </div>

      <div class="card">
        <h3><Icon name="plus" :size="15" /> 添加资料</h3>
        <div class="form-row">
          <label>选择文件（支持 PDF、Word、Excel、TXT、Markdown 等）</label>
          <input type="file" :accept="acceptTypes">
        </div>
        <div class="form-row">
          <label>描述（可选）</label>
          <input v-model="description" type="text" placeholder="这份资料是关于什么的...">
        </div>
        <button class="btn-small btn-small-primary" :disabled="uploading" @click="onUpload">添加</button>
        <span class="status-text" :class="{ error: statusIsError }">{{ uploadStatus }}</span>
        <div style="margin-top: 12px; font-size: 12px; color: var(--text-secondary)">
          添加后会在后台自动处理，完成后即可在对话中直接提问。
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as knowledgeApi from '../api/knowledge';
import { useProjectStore } from '../stores/project';
import type { DocumentInfo } from '../types/api';
import { formatDateTime, formatFileSize, getFileIcon } from '../utils/format';
import Icon from '../components/Icon.vue';

// 用户只需要知道「处理中 / 可用 / 失败」；解析、分块、Embedding 属于内部阶段
const STATUS_MAP: Record<string, { text: string; cls: string }> = {
  PENDING: { text: '处理中', cls: 'status-processing' },
  PARSING: { text: '处理中', cls: 'status-processing' },
  CHUNKING: { text: '处理中', cls: 'status-processing' },
  EMBEDDING: { text: '处理中', cls: 'status-processing' },
  READY: { text: '可用', cls: 'status-ready' },
  ERROR: { text: '失败', cls: 'status-error' }
};

const ALLOWED_EXTS = ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'txt', 'md', 'csv', 'json', 'java', 'py', 'js', 'html', 'xml', 'sql', 'yml', 'yaml'];
const acceptTypes = '.' + ALLOWED_EXTS.join(',.');

const docs = ref<DocumentInfo[]>([]);
const loadError = ref('');
const description = ref('');
const uploading = ref(false);
const uploadStatus = ref('');
const statusIsError = ref(false);

const project = useProjectStore();

// 轮询定时器：文档上传后后台异步解析，需轮询状态直到 READY/ERROR
let pollTimer: ReturnType<typeof setInterval> | null = null;

onMounted(loadDocuments);
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer);
});

// 切换项目后重新加载该项目的资料，避免看到别的项目内容
watch(
  () => project.currentProjectId,
  (id) => {
    if (!id) return;
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
    docs.value = [];
    loadDocuments();
  }
);

function statusText(status: string): string {
  return STATUS_MAP[status]?.text || status;
}

function statusClass(status: string): string {
  return STATUS_MAP[status]?.cls || '';
}

async function loadDocuments() {
  try {
    docs.value = await knowledgeApi.listDocuments(project.currentProjectId ?? undefined);
    loadError.value = '';
  } catch (e) {
    loadError.value = (e as Error).message;
  }
}

function startPolling(docId: number) {
  if (pollTimer) clearInterval(pollTimer);
  let attempts = 0;
  pollTimer = setInterval(async () => {
    attempts++;
    if (attempts > 60) {
      clearInterval(pollTimer!);
      pollTimer = null;
      return;
    }
    try {
      const list = await knowledgeApi.listDocuments();
      const doc = list.find((d) => d.id === docId);
      if (doc && (doc.status === 'READY' || doc.status === 'ERROR')) {
        clearInterval(pollTimer!);
        pollTimer = null;
        loadDocuments();
      }
    } catch {
      /* 轮询失败静默重试 */
    }
  }, 3000);
}

async function onUpload() {
  const input = document.querySelector<HTMLInputElement>('input[type="file"]');
  const file = input?.files?.[0];
  if (!file) {
    setStatus('请选择文件', true);
    return;
  }
  const ext = file.name.split('.').pop()?.toLowerCase() || '';
  if (!ALLOWED_EXTS.includes(ext)) {
    setStatus('不支持的文件类型: ' + ext, true);
    return;
  }
  if (file.size > 50 * 1024 * 1024) {
    setStatus('文件超过 50MB 限制', true);
    return;
  }

  setStatus('上传中...');
  uploading.value = true;
  try {
    const formData = new FormData();
    formData.append('file', file);
    if (project.currentProjectId) formData.append('projectId', String(project.currentProjectId));
    if (description.value.trim()) formData.append('description', description.value.trim());
    const data = await knowledgeApi.uploadDocument(formData);
    setStatus(data.message || '上传成功');
    if (input) input.value = '';
    description.value = '';
    await loadDocuments();
    startPolling(data.id);
  } catch (e) {
    setStatus((e as Error).message, true);
  } finally {
    uploading.value = false;
  }
}

async function onRetry(id: number) {
  try {
    await ElMessageBox.confirm('重新处理该文档？将先清除上次失败残留的分片和向量，再全量重建。', '提示', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await knowledgeApi.retryDocument(id);
    setStatus('已重新提交处理，正在后台重建...');
    await loadDocuments();
    startPolling(id);
  } catch (e) {
    ElMessage.error('重试失败: ' + (e as Error).message);
  }
}

async function onDelete(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该文档？相关向量数据也将被清除。', '提示', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await knowledgeApi.deleteDocument(id);
    await loadDocuments();
  } catch (e) {
    ElMessage.error('删除失败: ' + (e as Error).message);
  }
}

function setStatus(msg: string, isError = false) {
  uploadStatus.value = msg;
  statusIsError.value = isError;
}
</script>
