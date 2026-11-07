<template>
  <div class="fe-pane">
    <div class="fe-header">
      <div class="fe-file">
        <Icon name="file-text" :size="14" />
        <span class="fe-file-name" :title="path">{{ path }}</span>
        <span v-if="dirty" class="fe-dirty">未保存</span>
      </div>
      <div class="fe-actions">
        <button class="fe-btn" :disabled="saving || loading" title="重新加载" @click="reload">
          <Icon name="refresh" :size="13" />
        </button>
        <button class="fe-btn primary" :disabled="!dirty || saving" @click="save">
          <Icon name="save" :size="13" /> {{ saving ? '保存中…' : '保存' }}
        </button>
        <button class="fe-btn" title="关闭" @click="$emit('close')">
          <Icon name="x" :size="13" />
        </button>
      </div>
    </div>

    <div class="fe-body">
      <div v-show="loading" class="fe-empty">读取中…</div>
      <div v-show="!loading && error" class="fe-empty">
        <Icon name="alert" :size="20" />
        <p>无法打开这个文件</p>
        <p class="fe-empty-sub">{{ error }}</p>
      </div>
      <!-- 单一视图：CodeMirror 编辑 + 红绿 diff 行装饰 -->
      <div v-show="!loading && !error" ref="cmHost" class="fe-cm"></div>
    </div>

    <div class="fe-status">
      <span>{{ lineCount }} 行 · {{ content.length }} 字符</span>
      <template v-if="hasDiff">
        <span class="fe-diff-stat">
          <span class="fe-add">+{{ change?.additions || 0 }}</span>
          <span class="fe-del">-{{ change?.deletions || 0 }}</span>
          <span class="fe-diff-src">来自助手最近一次改动</span>
        </span>
      </template>
      <span v-if="savedAt">已保存 {{ savedAt }}</span>
      <span v-else>Ctrl / Cmd + S 保存 · Ctrl / Cmd + F 查找</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { basicSetup } from 'codemirror';
import { Compartment, EditorState, Prec, type Extension } from '@codemirror/state';
import { Decoration, EditorView, keymap } from '@codemirror/view';
import { indentWithTab } from '@codemirror/commands';
import { javascript } from '@codemirror/lang-javascript';
import { java } from '@codemirror/lang-java';
import { html } from '@codemirror/lang-html';
import { css } from '@codemirror/lang-css';
import { json } from '@codemirror/lang-json';
import { markdown } from '@codemirror/lang-markdown';
import { python } from '@codemirror/lang-python';
import { xml } from '@codemirror/lang-xml';
import { sql } from '@codemirror/lang-sql';
import { yaml } from '@codemirror/lang-yaml';
import { oneDark } from '@codemirror/theme-one-dark';
import { useProjectStore } from '../stores/project';
import { useThemeStore } from '../stores/theme';
import * as workspaceApi from '../api/workspace';
import type { FileChange } from '../types/api';
import Icon from './Icon.vue';

const props = defineProps<{
  path: string;
  change?: FileChange | null;
  focusLine?: number | null;
  focusTick?: number;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update:dirty', dirty: boolean): void;
}>();

const project = useProjectStore();
const theme = useThemeStore();

const content = ref('');
const dirty = ref(false);
const originalContent = ref('');
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const savedAt = ref('');
const cmHost = ref<HTMLElement | null>(null);
const view = ref<EditorView | null>(null);
let syncingDoc = false;

watch(dirty, (v) => emit('update:dirty', v));

const hasDiff = computed(() => !!props.change?.diff);

const lineCount = computed(() => (content.value ? content.value.split('\n').length : 1));

// 项目是异步加载的，等 currentProjectId 到位才读文件
watch(
  () => [props.path, project.currentProjectId] as const,
  ([path, pid]) => {
    if (path && pid) void load();
    else resetState();
  },
  { immediate: true }
);

// 变更列表点同一文件的另一个改动行 / 重复点同一行时，重新定位
watch(() => [props.focusLine, props.focusTick] as const, () => void relocate());

// 有 diff 变化时重绘行装饰
watch(
  () => props.change?.diff,
  () => {
    view.value?.dispatch({ effects: diffCompartment.reconfigure(diffExtension()) });
  }
);

// 主题切换时重配高亮
watch(
  () => theme.isDark,
  () => {
    view.value?.dispatch({ effects: themeCompartment.reconfigure(themeExtension()) });
  }
);

/** 基础配色 */
const baseTheme = EditorView.theme({
  '&': { height: '100%', fontSize: '12px', backgroundColor: 'var(--surface)', color: 'var(--text)' },
  '.cm-scroller': { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', lineHeight: '1.6' },
  '.cm-content': { padding: '8px 0' },
  '.cm-gutters': {
    backgroundColor: 'var(--surface-2)',
    color: 'var(--text-secondary)',
    borderRight: '1px solid var(--border)'
  },
  '.cm-activeLine': { backgroundColor: 'var(--surface-hover)' },
  '.cm-activeLineGutter': { backgroundColor: 'var(--surface-hover)' },
  '&.cm-focused': { outline: 'none' },
  '.cm-selectionBackground, ::selection': { backgroundColor: 'var(--primary-light) !important' },
  /** diff 行装饰：新增行浅绿背景 */
  '.cm-line.cm-line-add': { backgroundColor: 'rgba(103, 194, 58, 0.12)' },
  '.cm-line.cm-line-del': { backgroundColor: 'rgba(245, 108, 108, 0.10)', textDecoration: 'line-through', color: 'var(--text-secondary)' }
});

const themeCompartment = new Compartment();
const diffCompartment = new Compartment();

function themeExtension(): Extension {
  return theme.isDark ? [baseTheme, oneDark] : baseTheme;
}

/**
 * 解析 unified diff，找出当前文件里的新增行（+ 开头，非 +++ 文件头）和删除行。
 * 删除行在当前文件里已经不存在了，所以只标新增行。
 * 返回 CodeMirror DecorationSet，给新增行加 cm-line-add class。
 */
// 用更简洁正确的方式实现：
function diffExtension(): Extension {
  const diff = props.change?.diff;
  if (!diff) return [];

  const addedLineNos: number[] = [];
  let newLineNo = 0;
  let inHunk = false;

  for (const raw of diff.split('\n')) {
    if (raw.startsWith('@@')) {
      const m = raw.match(/\+(\d+)/);
      newLineNo = m ? parseInt(m[1], 10) : 1;
      inHunk = true;
      continue;
    }
    if (!inHunk) continue;
    if (raw.startsWith('+++') || raw.startsWith('---')) continue;
    if (raw.startsWith('+')) {
      addedLineNos.push(newLineNo);
      newLineNo++;
    } else if (raw.startsWith('-')) {
      continue;
    } else {
      newLineNo++;
    }
  }

  if (addedLineNos.length === 0) return [];

  // EditorView.decorations.compute 返回一个 DecorationSet
  return EditorView.decorations.compute(['doc'], (state) => {
    const builder: { from: number; to: number; value: Decoration }[] = [];
    for (const ln of addedLineNos) {
      if (ln < 1 || ln > state.doc.lines) continue;
      const line = state.doc.line(ln);
      builder.push({ from: line.from, to: line.from, value: Decoration.line({ class: 'cm-line-add' }) });
    }
    return Decoration.set(builder, true);
  });
}

/** 按扩展名挑语法高亮 */
function languageFor(path: string): Extension {
  const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
  switch (ext) {
    case 'ts':
    case 'tsx':
    case 'jsx':
    case 'js':
    case 'mjs':
    case 'cjs':
      return javascript({ typescript: ext === 'ts' || ext === 'tsx', jsx: ext === 'tsx' || ext === 'jsx' });
    case 'java':
      return java();
    case 'html':
    case 'vue':
      return html();
    case 'css':
    case 'scss':
    case 'less':
      return css();
    case 'json':
      return json();
    case 'md':
    case 'markdown':
      return markdown();
    case 'py':
      return python();
    case 'xml':
      return xml();
    case 'sql':
      return sql();
    case 'yml':
    case 'yaml':
      return yaml();
    default:
      return [];
  }
}

onMounted(() => {
  if (!cmHost.value) return;
  view.value = new EditorView({
    state: EditorState.create({
      doc: content.value,
      extensions: [
        basicSetup,
        Prec.highest(
          keymap.of([
            indentWithTab,
            {
              key: 'Mod-s',
              run: () => {
                if (dirty.value && !saving.value) void save();
                return true;
              }
            }
          ])
        ),
        languageFor(props.path),
        themeCompartment.of(themeExtension()),
        diffCompartment.of(diffExtension()),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !syncingDoc) {
            dirty.value = true;
          }
        })
      ]
    }),
    parent: cmHost.value
  });
});

onBeforeUnmount(() => {
  view.value?.destroy();
  view.value = null;
});

/** 从后端读文件内容 */
async function load() {
  if (!project.currentProjectId || !props.path) return;
  loading.value = true;
  error.value = '';
  try {
    const text = await workspaceApi.readWorkspaceFile(project.currentProjectId, props.path);
    content.value = text;
    originalContent.value = text;
    syncingDoc = true;
    view.value?.dispatch({
      changes: { from: 0, to: view.value.state.doc.length, insert: text }
    });
    syncingDoc = false;
    dirty.value = false;
    savedAt.value = '';
    await nextTick();
    relocate();
  } catch (e: any) {
    error.value = e?.message || '读取失败';
  } finally {
    loading.value = false;
  }
}

function resetState() {
  content.value = '';
  error.value = '';
  dirty.value = false;
  savedAt.value = '';
}

async function save() {
  if (!project.currentProjectId || !props.path || saving.value || !dirty.value) return;
  saving.value = true;
  try {
    const text = view.value?.state.doc.toString() ?? '';
    await workspaceApi.writeWorkspaceFile(project.currentProjectId, props.path, text);
    content.value = text;
    originalContent.value = text;
    dirty.value = false;
    const d = new Date();
    savedAt.value = `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}`;
    ElMessage.success('已保存');
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function reload() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('当前修改还没保存，重新加载会丢掉这些改动。', '重新加载', {
        confirmButtonText: '放弃并加载',
        cancelButtonText: '取消',
        type: 'warning'
      });
    } catch {
      return;
    }
  }
  await load();
}

/** 定位到 focusLine */
async function relocate() {
  if (!props.focusLine || !view.value) return;
  await nextTick();
  const target = Math.max(1, Math.min(props.focusLine, view.value.state.doc.lines));
  const line = view.value.state.doc.line(target);
  view.value.dispatch({
    selection: { anchor: line.from },
    scrollIntoView: true
  });
  view.value.focus();
}
</script>

<style scoped>
.fe-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.fe-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
  flex-shrink: 0;
}
.fe-file {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  min-width: 0;
}
.fe-file-name {
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}
.fe-dirty {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(230, 162, 60, 0.15);
  color: #e6a23c;
}
.fe-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.fe-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 7px;
  font-size: 11px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s;
}
.fe-btn:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
.fe-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.fe-btn.primary {
  background: var(--primary);
  color: #fff;
  border-color: var(--primary);
}
.fe-btn.primary:hover:not(:disabled) { filter: brightness(1.1); }

.fe-body {
  flex: 1;
  overflow: hidden;
  position: relative;
}
.fe-cm {
  height: 100%;
  width: 100%;
}
.fe-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 13px;
}
.fe-empty p { margin: 0; }
.fe-empty-sub { font-size: 11px; color: var(--text-secondary); opacity: 0.7; }

.fe-status {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 3px 10px;
  border-top: 1px solid var(--border);
  font-size: 10px;
  color: var(--text-secondary);
  background: var(--surface-2);
  flex-shrink: 0;
}
.fe-diff-stat {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}
.fe-add { color: var(--success); font-weight: 600; }
.fe-del { color: var(--danger); font-weight: 600; }
.fe-diff-src { color: var(--text-secondary); opacity: 0.7; }
</style>
