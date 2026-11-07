<template>
  <div class="ws-tree-wrap">
    <div class="ws-tree-toolbar">
      <span class="ws-tree-path clickable" :title="'点击打开工作区设置\n' + workspacePath" @click="$emit('open-settings')">{{ workspacePath }}</span>
      <button class="ws-tree-btn" title="新建文件" @click="onCreate(false)">
        <Icon name="plus" :size="13" />
      </button>
      <button class="ws-tree-btn" title="新建文件夹" @click="onCreate(true)">
        <Icon name="folder" :size="13" />
      </button>
      <button class="ws-tree-btn" title="刷新" @click="refresh">
        <Icon name="refresh" :size="13" />
      </button>
    </div>

    <div class="ws-tree">
      <div v-if="loadingRoot && !treeNodes.length" class="ws-tree-empty">加载中…</div>
      <template v-else-if="treeNodes.length">
        <div
          v-for="row in visibleRows"
          :key="row.data.relativePath"
          class="ws-row"
          :class="{
            'is-dir': row.data.isDirectory,
            selected: activePath === row.data.relativePath
          }"
          :style="{ paddingLeft: row.depth * 14 + 6 + 'px' }"
          @click="onRowClick(row)"
        >
          <span class="ws-toggle">
            <Icon
              v-if="row.data.isDirectory"
              :name="row.expanded ? 'chevron-down' : 'chevron-right'"
              :size="12"
            />
          </span>
          <span class="ws-row-icon">
            <Icon :name="row.data.isDirectory ? 'folder' : 'file-text'" :size="14" />
          </span>
          <span class="ws-row-name" :title="row.data.relativePath">{{ row.data.name }}</span>
          <span v-if="!row.data.isDirectory" class="ws-row-size">{{ formatSize(row.data.size) }}</span>
          <span v-if="row.loading" class="ws-row-loading">…</span>
          <!-- 每个条目悬停出现「更多」菜单：重命名 / 删除 -->
          <el-dropdown
            class="ws-row-more"
            trigger="click"
            @command="(c: string) => onRowCommand(c, row)"
          >
            <button class="ws-row-more-btn" title="更多" @click.stop>
              <Icon name="more" :size="14" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="rename">重命名</el-dropdown-item>
                <el-dropdown-item command="delete">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </template>
      <div v-else class="ws-tree-empty">空目录</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as workspaceApi from '../api/workspace';
import { useChatStore } from '../stores/chat';
import type { FileNode } from '../types/api';
import Icon from './Icon.vue';

const props = defineProps<{
  projectId: number | null;
  workspacePath?: string;
  /** 当前在编辑器里打开的文件，用于高亮 */
  activePath?: string | null;
}>();

const emit = defineEmits<{
  (e: 'open-settings'): void;
  (e: 'open-file', node: FileNode): void;
}>();

interface TreeNode {
  data: FileNode;
  children: TreeNode[] | null;
  expanded: boolean;
  loading: boolean;
}

interface VisibleRow {
  data: FileNode;
  depth: number;
  expanded: boolean;
  loading: boolean;
}

const chat = useChatStore();
const treeNodes = ref<TreeNode[]>([]);
const loadingRoot = ref(false);
/** 新建文件/文件夹落在这里（最近点开过的目录，默认根目录） */
const selectedDir = ref('');

// 展开状态按工作区持久化：刷新页面或切回工作区后树还保持原来的展开层级
function expandedKey(): string {
  return `wsTree.expanded.${props.projectId}`;
}

function loadExpanded(): string[] {
  if (!props.projectId) return [];
  try {
    const raw = localStorage.getItem(expandedKey());
    const list = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

/** 收集当前所有处于展开状态的目录路径并落盘 */
function saveExpanded() {
  if (!props.projectId) return;
  const paths: string[] = [];
  const walk = (list: TreeNode[]) => {
    for (const n of list) {
      if (n.data.isDirectory && n.expanded) {
        paths.push(n.data.relativePath);
        if (n.children) walk(n.children);
      }
    }
  };
  walk(treeNodes.value);
  localStorage.setItem(expandedKey(), JSON.stringify(paths));
}

watch(
  () => [props.projectId, props.workspacePath],
  () => {
    treeNodes.value = [];
    selectedDir.value = '';
    if (props.projectId && props.workspacePath) {
      void loadRoot().then(restoreExpanded);
    }
  },
  { immediate: true }
);

async function loadRoot() {
  if (!props.projectId) return;
  loadingRoot.value = true;
  try {
    const nodes = await workspaceApi.listWorkspaceFiles(props.projectId, '');
    treeNodes.value = nodes.map(toTreeNode);
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message);
  } finally {
    loadingRoot.value = false;
  }
}

function toTreeNode(n: FileNode): TreeNode {
  return { data: n, children: null, expanded: false, loading: false };
}

/** 请求某个目录的子节点并挂到树上（首次展开才请求，之后复用） */
async function loadChildren(node: TreeNode) {
  node.loading = true;
  try {
    const children = await workspaceApi.listWorkspaceFiles(props.projectId!, node.data.relativePath);
    node.children = children.map(toTreeNode);
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message);
    node.children = [];
  } finally {
    node.loading = false;
  }
}

async function toggleFolder(node: TreeNode) {
  if (node.children === null) await loadChildren(node);
  node.expanded = !node.expanded;
  saveExpanded();
}

/** 按保存的路径逐层展开：路径不存在（目录已被删）就跳过 */
async function restoreExpanded() {
  for (const path of loadExpanded()) {
    await expandByPath(path);
  }
}

async function expandByPath(path: string) {
  const segments = path.split('/');
  let prefix = '';
  let list = treeNodes.value;
  for (const seg of segments) {
    prefix = prefix ? `${prefix}/${seg}` : seg;
    const node = list.find((n) => n.data.relativePath === prefix);
    if (!node || !node.data.isDirectory) return;
    if (node.children === null) await loadChildren(node);
    node.expanded = true;
    list = node.children ?? [];
  }
}

function onRowClick(row: VisibleRow) {
  const node = findNode(row.data.relativePath);
  if (!node) return;
  if (row.data.isDirectory) {
    selectedDir.value = row.data.relativePath;
    void toggleFolder(node);
  } else {
    emit('open-file', node.data);
  }
}

function refresh() {
  treeNodes.value = [];
  void loadRoot().then(restoreExpanded);
}

/** 新建空文件/目录：落在最近选中的目录下 */
async function onCreate(directory: boolean) {
  if (!props.projectId) return;
  const label = directory ? '文件夹' : '文件';
  const name = await promptName(`新建${label}`, label === '文件' ? '例如：notes.md' : '例如：docs');
  if (!name) return;
  const base = selectedDir.value;
  const path = base ? `${base}/${name}` : name;
  try {
    await workspaceApi.createWorkspaceEntry(props.projectId, path, directory);
    ElMessage.success(`已新建${label}`);
    await refreshDir(base);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  }
}

async function onRowCommand(command: string, row: VisibleRow) {
  if (command === 'rename') await onRename(row);
  else if (command === 'delete') await onDelete(row);
}

async function onRename(row: VisibleRow) {
  if (!props.projectId) return;
  const oldPath = row.data.relativePath;
  const name = await promptName('重命名', row.data.name, row.data.name);
  if (!name || name === row.data.name) return;
  const parent = oldPath.includes('/') ? oldPath.slice(0, oldPath.lastIndexOf('/')) : '';
  const newPath = parent ? `${parent}/${name}` : name;

  // 正在工作台编辑且未保存时先拦住：改名后标签会指向旧路径
  const open = chat.openFiles.filter((p) => p === oldPath || p.startsWith(oldPath + '/'));
  if (open.some((p) => chat.fileDirty[p])) {
    ElMessage.warning('这个文件还有未保存的改动，请先在工作台保存或关闭对应标签再重命名');
    return;
  }

  try {
    await workspaceApi.renameWorkspaceEntry(props.projectId, oldPath, newPath);
    chat.renameFileTab(oldPath, newPath);
    ElMessage.success('已重命名');
    await refreshDir(parent);
    void restoreExpanded();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  }
}

async function onDelete(row: VisibleRow) {
  if (!props.projectId) return;
  const path = row.data.relativePath;
  const open = chat.openFiles.filter((p) => p === path || p.startsWith(path + '/'));
  if (open.some((p) => chat.fileDirty[p])) {
    ElMessage.warning('这个路径下还有未保存的文件改动，请先在工作台保存或关闭对应标签再删除');
    return;
  }
  const what = row.data.isDirectory ? '文件夹（含其中所有内容）' : '文件';
  try {
    await ElMessageBox.confirm(`确定删除${what}「${path}」吗？此操作不可撤销。`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await workspaceApi.deleteWorkspaceEntry(props.projectId, path);
    // 删除后关掉指向它的工作台标签，避免编辑器指向已不存在的路径
    open.forEach((p) => chat.closeFile(p));
    ElMessage.success('已删除');
    const parent = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '';
    await refreshDir(parent);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  }
}

/** 输入名称的弹窗；取消或空值返回 null */
async function promptName(title: string, placeholder: string, value = ''): Promise<string | null> {
  try {
    const { value: name } = await ElMessageBox.prompt('请输入名称', title, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: value,
      inputPlaceholder: placeholder,
      inputValidator: (v: string) => {
        const t = (v || '').trim();
        if (!t) return '名称不能为空';
        if (t.includes('/') || t.includes('\\')) return '名称不能包含路径分隔符';
        return true;
      }
    });
    return name.trim();
  } catch {
    return null;
  }
}

/** 刷新根目录并重新展开指定目录（新建/重命名/删除后保持操作所在的层级） */
async function refreshDir(dir: string) {
  treeNodes.value = [];
  await loadRoot();
  await restoreExpanded();
  if (dir) await expandByPath(dir);
}

function findNode(relativePath: string): TreeNode | undefined {
  const walk = (list: TreeNode[]): TreeNode | undefined => {
    for (const n of list) {
      if (n.data.relativePath === relativePath) return n;
      if (n.children) {
        const found = walk(n.children);
        if (found) return found;
      }
    }
    return undefined;
  };
  return walk(treeNodes.value);
}

/** 把树拍平成可见行（只含已展开文件夹的后代） */
const visibleRows = computed<VisibleRow[]>(() => {
  const rows: VisibleRow[] = [];
  const walk = (list: TreeNode[], depth: number) => {
    for (const n of list) {
      rows.push({ data: n.data, depth, expanded: n.expanded, loading: n.loading });
      if (n.data.isDirectory && n.expanded && n.children) walk(n.children, depth + 1);
    }
  };
  walk(treeNodes.value, 0);
  return rows;
});

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}
</script>

<style scoped>
.ws-tree-wrap {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
}

.ws-tree-toolbar {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 8px 6px;
    flex-shrink: 0;
}
.ws-tree-path {
    flex: 1;
    min-width: 0;
    font-size: var(--fs-xs);
    color: var(--text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.ws-tree-path.clickable {
    cursor: pointer;
    border-radius: var(--radius-sm);
    padding: 2px 6px;
    margin: -2px -6px;
    transition: background 120ms, color 120ms;
}
.ws-tree-path.clickable:hover {
    background: var(--bg-hover);
    color: var(--brand);
}
.ws-tree-btn {
    width: 22px;
    height: 22px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: none;
    border-radius: var(--radius-sm);
    color: var(--text-secondary);
    cursor: pointer;
    flex-shrink: 0;
}
.ws-tree-btn:hover { background: var(--surface-hover); color: var(--text); }

.ws-tree {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding-bottom: 4px;
}

.ws-row {
    display: flex;
    align-items: center;
    gap: 4px;
    height: 26px;
    padding-right: 8px;
    cursor: pointer;
    font-size: var(--fs-sm);
    user-select: none;
    border-radius: var(--radius-sm);
    margin: 0 2px;
}
.ws-row:hover { background: var(--surface-hover); }
.ws-row.selected { background: var(--primary-light); }
.ws-row.selected .ws-row-name { color: var(--primary); font-weight: 500; }

.ws-toggle {
    width: 16px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-secondary);
}

.ws-row-icon {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    color: var(--text-secondary);
}
.ws-row.is-dir .ws-row-icon { color: #e0a84a; }

.ws-row-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--text);
}

.ws-row-size {
    flex-shrink: 0;
    font-size: var(--fs-xs);
    color: var(--text-secondary);
    opacity: .7;
}
.ws-row-loading {
    flex-shrink: 0;
    font-size: var(--fs-xs);
    color: var(--text-secondary);
}

/* 行尾「更多」按钮：平时隐藏，悬停该行才出现 */
.ws-row-more {
    flex-shrink: 0;
    opacity: 0;
}
.ws-row:hover .ws-row-more { opacity: 1; }
.ws-row-more-btn {
    width: 20px;
    height: 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: none;
    border-radius: var(--radius-sm);
    color: var(--text-secondary);
    cursor: pointer;
}
.ws-row-more-btn:hover { background: var(--surface-2); color: var(--text); }

.ws-tree-empty {
    padding: 20px 12px;
    color: var(--text-secondary);
    font-size: var(--fs-sm);
    text-align: center;
}
</style>
