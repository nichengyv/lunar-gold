<template>
  <div class="app">
    <aside class="sidebar" :class="{ open: sidebarOpen, collapsed: sidebarCollapsed, 'is-explorer': isExplorerMode }">
      <div class="sidebar-header">
        <Icon name="logo" :size="22" />
        <span class="brand-text">Lunar Gold</span>
      </div>
      <nav v-if="!isExplorerMode" class="sidebar-nav">
        <router-link
          v-for="item in navItems"
          :key="item.page"
          :to="'/' + item.page"
          class="nav-item"
          active-class="active"
          :title="item.title"
          @click="closeSidebarOnMobile"
        >
          <span class="nav-icon"><Icon :name="item.icon" :size="15" /></span>
          <span class="nav-text">{{ item.title }}</span>
        </router-link>
      </nav>

      <button v-if="!isExplorerMode" class="new-session-btn" title="新任务" @click="onNewSession">
        <Icon name="plus" :size="14" /> <span class="new-session-text">新任务</span>
      </button>

      <!-- 工作区区域：任务列表 / 资源管理器，同一层级切换 -->
      <div class="ws-section">
        <div class="ws-section-header">
          <span class="ws-section-plain">{{ isExplorerMode ? '资源管理器' : '任务' }}</span>
          <div v-if="sideTab === 'sessions'" class="ws-section-actions">
            <button class="ws-action" title="搜索工作区/任务" aria-label="搜索工作区或任务" @click="wsSearchOpen = !wsSearchOpen">
              <Icon name="search" :size="14" />
            </button>
            <button v-if="hasLocalWorkspace" class="ws-action" title="新建工作区" @click="onCreateWorkspace">
              <Icon name="plus" :size="14" />
            </button>
          </div>
        </div>

        <template v-if="sideTab === 'sessions'">
        <div v-if="wsSearchOpen" class="ws-search">
          <div class="ws-search-box">
            <Icon name="search" :size="13" class="ws-search-icon" />
            <input
              ref="wsSearchEl"
              v-model="wsKeyword"
              type="text"
              placeholder="搜索工作区或任务..."
              @keydown="onSearchKeydown"
            >
            <button v-if="wsKeyword" class="ws-search-clear" title="清空" aria-label="清空搜索" @click="wsKeyword = ''">
              <Icon name="x" :size="12" />
            </button>
          </div>
          <!-- 搜索时用键盘上下选择会话，回车直接切换 -->
          <div v-if="wsKeyword.trim()" class="ws-search-tip">↑↓ 选择 · Enter 打开 · Esc 清空</div>
        </div>
        <!-- 正文命中：标题没筛选到的会话，直接给出命中的那句原文，点一下跳过去 -->
        <div v-if="contentHits.length" class="ws-content-hits">
          <div class="ws-content-head">正文命中 {{ contentHits.length }} 条</div>
          <button
            v-for="h in contentHits"
            :key="h.messageId"
            class="ws-content-hit"
            @click="onOpenContentHit(h)"
          >
            <span class="ws-content-session">{{ h.sessionTitle || `任务${h.sessionId}` }}</span>
            <span class="ws-content-snippet">
              <span
                v-for="(part, i) in highlightParts(h.snippet)"
                :key="i"
                :class="{ 'match-hit': part.hit }"
              >{{ part.text }}</span>
            </span>
          </button>
        </div>
        <div class="ws-list">
          <div v-for="p in filteredProjects" :key="p.id" class="ws-group">
            <div
              v-if="hasLocalWorkspace"
              class="ws-row"
              :class="{ active: p.id === project.currentProjectId, open: isExpanded(p.id) }"
              @click="onWorkspaceClick(p)"
            >
              <el-dropdown trigger="click" @command="(c: string) => onWorkspaceCommand(c, p)">
                <button class="more-btn" title="更多" aria-label="工作区操作" @click.stop>
                  <Icon name="more" :size="16" />
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="settings">工作区设置</el-dropdown-item>
                    <el-dropdown-item command="newSession">在此新建任务</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <span class="ws-icon"><Icon name="folder" :size="14" /></span>
              <span class="ws-name" :title="p.workspacePath || p.name">{{ p.name }}</span>
              <button class="ws-del" title="删除工作区" aria-label="删除工作区" @click.stop="onDeleteWorkspace(p)">
                <Icon name="trash" :size="13" />
              </button>
              <button class="ws-new-session" title="在此新建任务" aria-label="在此新建任务" @click.stop="onNewSessionIn(p)">
                <Icon name="plus" :size="14" />
              </button>
            </div>

            <div
              v-if="isExpanded(p.id) || !hasLocalWorkspace"
              class="ws-sessions"
              :class="{ flat: !hasLocalWorkspace }"
            >
              <div
                v-for="s in matchedSessions(p.id)"
                :key="s.id"
                class="session-item"
                :class="{
                  active: s.id === chat.currentSessionId,
                  'search-active': s.id === activeSearchSessionId
                }"
              >
                <el-dropdown trigger="click" @command="(c: string) => onSessionCommand(c, s.id, s.title)">
                  <button class="more-btn" title="更多" aria-label="任务操作" @click.stop>
                  <Icon name="more" :size="16" />
                </button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="rename">重命名</el-dropdown-item>
                      <el-dropdown-item command="delete">删除</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <button
                  type="button"
                  class="session-main"
                  :aria-current="s.id === chat.currentSessionId ? 'true' : undefined"
                  @click="onSwitchSession(s.id, p)"
                >
                  <div class="session-title" title="双击重命名">
                    <span
                      v-for="(part, i) in highlightParts(s.title || `任务${s.id}`)"
                      :key="i"
                      :class="{ 'match-hit': part.hit }"
                    >{{ part.text }}</span>
                  </div>
                  <span
                    v-if="sessionRunMeta(s)"
                    class="session-dot"
                    :class="sessionRunClass(s)"
                    :title="sessionRunTitle(s)"
                  ></span>
                  <div class="session-time" :title="formatDateTime(s.updateTime)">{{
                    formatRelativeTime(s.updateTime)
                  }}</div>
                </button>
              </div>
              <div v-if="matchedSessions(p.id).length === 0" class="session-empty">
                {{ sessionsOf(p.id).length === 0 ? '暂无任务' : '没有匹配的任务' }}
              </div>
            </div>
          </div>
          <div v-if="filteredProjects.length === 0" class="session-empty">
            {{ wsKeyword ? '没有匹配的工作区或任务' : '还没有工作区，点右上角 ＋ 新建' }}
          </div>
        </div>
        </template>

        <!-- 资源管理器：与「会话」同级，点文件在右侧专用页面打开编辑 -->
        <template v-else-if="sideTab === 'files' && hasLocalWorkspace">
          <WorkspaceFileTree
            v-if="project.currentProject?.workspacePath"
            :project-id="project.currentProjectId"
            :workspace-path="project.currentProject.workspacePath"
            :active-path="editingPath"
            @open-file="onOpenFile"
            @open-settings="openWorkspaceSettings"
          />
          <div v-else class="ws-file-empty">
            <p>当前工作区未绑定文件夹</p>
            <button class="ws-file-empty-btn" @click="openWorkspaceSettings">设置工作区</button>
          </div>
        </template>
      </div>
      <!-- 桌面端本地单用户，没有账号与登出概念，不展示用户信息 -->
      <div v-if="meta.mode !== 'DESKTOP'" class="sidebar-footer">
        <span class="sidebar-user" :title="auth.username || '未登录'">
          <Icon name="user" :size="14" /> <span class="footer-text">{{ auth.username || '未登录' }}</span>
        </span>
        <a href="#" class="footer-text" @click.prevent="onLogout">退出</a>
      </div>
    </aside>

    <main ref="mainEl" class="main">
      <header class="topbar">
        <div class="topbar-left">
          <button class="menu-toggle" @click="toggleSidebar" :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'" :aria-label="sidebarCollapsed ? '展开侧栏' : '收起侧栏'">
            <Icon name="menu" :size="18" />
          </button>
          <span class="page-title">{{ pageTitle }}</span>
        </div>
        <div class="topbar-right">
          <!-- 主侧栏：在「导航栏」与「项目结构资源管理器」之间切换 -->
          <button
            v-if="hasLocalWorkspace"
            class="icon-btn"
            :class="{ active: isExplorerMode }"
            :title="isExplorerMode ? '返回导航栏' : '打开工作区资源管理器'"
            :aria-label="isExplorerMode ? '返回导航栏' : '打开工作区资源管理器'"
            @click="toggleExplorer"
          >
            <Icon name="panel-left" :size="16" />
          </button>
          <!-- 任务详情侧边栏：打开/收起右侧工作台 -->
          <button
            v-if="route.name === 'chat'"
            class="icon-btn"
            :class="{ active: chat.dockOpen }"
            title="任务详情侧边栏"
            aria-label="任务详情侧边栏"
            @click="toggleTaskDock"
          >
            <Icon name="panel" :size="16" />
          </button>
          <button class="icon-btn" :title="theme.isDark ? '切换到浅色' : '切换到深色'" :aria-label="theme.isDark ? '切换到浅色主题' : '切换到深色主题'" @click="theme.toggle()">
            <Icon :name="theme.isDark ? 'sun' : 'moon'" :size="16" />
          </button>
        </div>
      </header>

      <div class="pages">
        <router-view />
      </div>

      <!-- 工作区设置弹窗：文件夹 + 自定义指令 -->
      <el-dialog v-if="hasLocalWorkspace" v-model="instructionDialogVisible" title="工作区设置" width="560px" destroy-on-close>
        <template #header>
          <div class="dialog-title">
            <span>工作区设置 · {{ project.currentProject?.name || '当前工作区' }}</span>
          </div>
        </template>

        <div class="ws-field">
          <label>工作区文件夹</label>
          <div class="folder-inline">
            <el-input
              v-model="settingsPath"
              placeholder="直接粘贴路径，如 D:\projects\my-app"
              :disabled="pickingFolder"
            >
              <template #append>
                <el-button :loading="pickingFolder" @click="pickFolderFor('settings')">
                  {{ settingsPath ? '重新选择' : '浏览…' }}
                </el-button>
              </template>
            </el-input>
          </div>
          <p class="ws-hint">
            直接粘贴或在末尾点「浏览…」从系统窗口选择。Agent 只能读写该文件夹内的内容。
          </p>
        </div>

        <div class="ws-field" style="margin-top:16px">
          <label>工作区指令</label>
          <p class="dialog-hint">
            定义这个工作区的背景、口吻或领域要求，助手在回答时会参考这些约束。留空则恢复通用助手行为。
          </p>
          <el-input
            v-model="instructionDraft"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="例如：这是一个 Spring Boot + Vue 项目，回答时优先给出可直接落地的代码改动"
          />
        </div>

        <div class="ws-field" style="margin-top:16px">
          <label>默认工作权限</label>
          <p class="dialog-hint">
            新任务默认继承这里的权限；单个任务仍可临时调整。未设置时按「手动审批」处理。
          </p>
          <el-select v-model="permissionDraft" style="width:100%">
            <el-option label="未设置（按手动审批）" value="" />
            <el-option label="手动审批 · 写文件和执行命令都要确认" value="MANUAL" />
            <el-option label="自动审批 · 写文件和普通命令自动通过，仅破坏性命令需确认" value="AUTO" />
            <el-option label="完全权限 · 全部自动通过" value="FULL" />
          </el-select>
        </div>

        <template #footer>
          <el-button @click="instructionDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="instructionSaving" @click="saveInstruction">保存</el-button>
        </template>
      </el-dialog>

      <!-- 新建工作区弹窗：名称 + 选择磁盘文件夹 -->
      <el-dialog v-if="hasLocalWorkspace" v-model="wsDialogVisible" title="新建工作区" width="560px" destroy-on-close>
        <div class="ws-form">
          <div class="ws-field">
            <label>工作区名称</label>
            <el-input v-model="wsName" placeholder="例如：我的博客项目、Lunar Gold" maxlength="50" />
          </div>
          <div class="ws-field">
            <label>工作区文件夹 <span class="required">*</span></label>
            <el-input
              v-model="wsPath"
              placeholder="直接粘贴路径，或点右侧「浏览…」"
              :disabled="pickingFolder"
            >
              <template #append>
                <el-button :loading="pickingFolder" @click="pickFolderFor('new')">浏览…</el-button>
              </template>
            </el-input>
            <p class="ws-hint">Agent 只能读写该文件夹内的内容。</p>
          </div>
        </div>
        <template #footer>
          <el-button @click="wsDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="wsSaving" @click="saveWorkspace">创建</el-button>
        </template>
      </el-dialog>
    </main>

    <!-- 任务详情侧边栏：main 的兄弟节点，整列顶开会话区；≥1024px 内联占宽，<1024px 浮层 -->
    <div
      v-if="showDock && !dockFullscreen && isNarrowDock"
      class="dock-backdrop"
      @click="closeTaskDock"
    ></div>
    <div
      v-if="showDock && !dockFullscreen"
      class="dock-resizer"
      :class="{ dragging: dockDragging }"
      title="拖动调整工作台宽度"
      @mousedown="startDockDrag"
    ></div>
    <WorkbenchDock
      v-if="showDock"
      :width="dockWidth"
      :fullscreen="dockFullscreen"
      @close="closeTaskDock"
      @toggle-fullscreen="dockFullscreen = !dockFullscreen"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '../stores/auth';
import { useChatStore } from '../stores/chat';
import { useMetaStore } from '../stores/meta';
import { useProjectStore } from '../stores/project';
import { useThemeStore } from '../stores/theme';
import { formatDateTime, formatRelativeTime } from '../utils/format';
import * as workspaceApi from '../api/workspace';
import * as agentApi from '../api/agent';
import WorkspaceFileTree from '../components/WorkspaceFileTree.vue';
import WorkbenchDock from '../components/WorkbenchDock.vue';
import Icon from '../components/Icon.vue';
import type { FileNode, MessageSearchHit, ProjectInfo, SessionInfo } from '../types/api';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const chat = useChatStore();
const meta = useMetaStore();
const project = useProjectStore();
const theme = useThemeStore();

/** 网页端（cloud）不提供本地工作区，文件树、工作区管理与文件类入口全部隐藏 */
const hasLocalWorkspace = computed(() => meta.has('LOCAL_WORKSPACE'));

// ——— 任务详情侧边栏（右侧工作台）———
// 与 main 同级、整列顶开会话区；宽屏内联占宽，<1024px 浮层
const DOCK_WIDTH_KEY = 'chat.dockWidth';
// 对话区最小宽度：再窄输入框占位提示就会折成两行，因此把压缩下限提高
const MIN_CHAT_WIDTH = 540;
// 工作台与会话区之间用留白分隔，不再画竖线
const DOCK_GUTTER = 4;
const mainEl = ref<HTMLElement | null>(null);
const dockWidth = ref(Number(localStorage.getItem(DOCK_WIDTH_KEY)) || 520);
const dockFullscreen = ref(false);
const dockDragging = ref(false);
const viewportWidth = ref(window.innerWidth);
const isNarrowDock = computed(() => viewportWidth.value < 1024);
const showDock = computed(() => route.name === 'chat' && chat.dockOpen);

/** 内联工作台是 main 的兄弟节点：main 已被顶窄，算上限时要把它占的宽度加回来 */
function containerWidth() {
  const mainWidth = mainEl.value?.clientWidth ?? window.innerWidth;
  return showDock.value && !isNarrowDock.value && !dockFullscreen.value
    ? mainWidth + dockWidth.value + DOCK_GUTTER
    : mainWidth;
}

function maxDockWidth() {
  return Math.max(300, Math.min(window.innerWidth * 0.7, containerWidth() - MIN_CHAT_WIDTH - DOCK_GUTTER));
}

function clampDockWidth(w: number) {
  return Math.min(maxDockWidth(), Math.max(300, w));
}

/** 拖动手柄：以工作台左缘为准，鼠标向左拖工作台变宽；拖动期间禁掉文本选择避免误选 */
function startDockDrag(e: MouseEvent) {
  e.preventDefault();
  const startX = e.clientX;
  const startW = dockWidth.value;
  dockDragging.value = true;
  document.body.style.userSelect = 'none';
  const onMove = (ev: MouseEvent) => {
    dockWidth.value = clampDockWidth(startW + (startX - ev.clientX));
  };
  const onUp = () => {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    document.body.style.userSelect = '';
    dockDragging.value = false;
    localStorage.setItem(DOCK_WIDTH_KEY, String(dockWidth.value));
  };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
}

const sidebarOpen = ref(false);

// 侧栏折叠（桌面端）：收起成 56px 图标轨，专注对话时释放横向空间；
// 手动选择持久化，窗口 <1024px 时自动收起、拉宽后恢复用户手动选择
const COLLAPSE_KEY = 'ui.sidebarCollapsed';
const sidebarCollapsed = ref(localStorage.getItem(COLLAPSE_KEY) === '1');
// 标记本次收起是否为窗口变窄触发的自动行为，避免覆盖用户的手动选择
let autoCollapsed = false;

function toggleCollapse() {
  sidebarCollapsed.value = !sidebarCollapsed.value;
  localStorage.setItem(COLLAPSE_KEY, sidebarCollapsed.value ? '1' : '0');
  autoCollapsed = false;
}

/**
 * 顶栏侧栏按钮：桌面端切换「收起/展开」，移动端切换浮层显隐。
 * 原来侧栏头里的「<<」按钮已删，统一走这里。
 */
function toggleSidebar() {
  if (window.innerWidth < 768) {
    sidebarOpen.value = !sidebarOpen.value;
  } else {
    toggleCollapse();
  }
}

function onWindowResize() {
  viewportWidth.value = window.innerWidth;
  if (window.innerWidth < 1024) {
    if (!sidebarCollapsed.value) {
      sidebarCollapsed.value = true;
      autoCollapsed = true;
    }
  } else if (autoCollapsed) {
    sidebarCollapsed.value = localStorage.getItem(COLLAPSE_KEY) === '1';
    autoCollapsed = false;
  }
  // 窗口变窄时立刻收敛工作台宽度，避免临时把对话区挤到最小宽度以下
  if (chat.dockOpen && !dockFullscreen.value) {
    const clamped = clampDockWidth(dockWidth.value);
    if (clamped !== dockWidth.value) {
      dockWidth.value = clamped;
      localStorage.setItem(DOCK_WIDTH_KEY, String(clamped));
    }
  }
}
// 侧栏搜索：同时过滤工作区名和其下会话名
const wsSearchOpen = ref(false);
const wsKeyword = ref('');
const wsSearchEl = ref<HTMLInputElement | null>(null);
// 搜索候选项的键盘位置：只在一处维护，上下键跨工作区连续移动
const searchHitIndex = ref(0);
// 正文命中：标题没命中但聊天内容命中的会话，由后端全文搜索返回
const contentHits = ref<MessageSearchHit[]>([]);
let contentSearchTimer: number | undefined;
let contentSearchSeq = 0;
// 展开的工作区 id 集合（点击工作区行展开/收起）
const expanded = reactive<Record<number, boolean>>({});

// 侧栏两个同级面板：会话列表 / 资源管理器
const sideTab = ref<'sessions' | 'files'>('sessions');
/** 资源管理器模式：顶栏按钮唤出，替代原来的「任务/文件」切换按钮 */
const isExplorerMode = computed(() => hasLocalWorkspace.value && sideTab.value === 'files');

/** 主侧栏按钮：导航栏 ↔ 资源管理器；收起状态下先展开 */
function toggleExplorer() {
  if (sidebarCollapsed.value) {
    sidebarCollapsed.value = false;
    localStorage.setItem(COLLAPSE_KEY, '0');
    autoCollapsed = false;
  }
  sideTab.value = sideTab.value === 'files' ? 'sessions' : 'files';
  sidebarOpen.value = true;
}

/** 收起工作台前先确认：编辑器里的未保存内容会随挂载一起消失 */
async function closeTaskDock() {
  if (chat.editorDirty) {
    try {
      await ElMessageBox.confirm('工作台里还有未保存的改动，收起后会丢失。', '未保存的改动', {
        confirmButtonText: '放弃改动并收起',
        cancelButtonText: '继续编辑',
        type: 'warning'
      });
    } catch {
      return;
    }
  }
  chat.closeDock();
}

/** 任务详情侧边栏：打开或收起 */
async function toggleTaskDock() {
  if (!chat.dockOpen) {
    chat.openDock(chat.dockTab);
    return;
  }
  await closeTaskDock();
}
/** 编辑器里当前激活的文件，用于资源管理器里高亮 */
const editingPath = computed(() => chat.activeFilePath);

/** 资源管理器里点文件：在对话区右侧工作台打开（已开过的 tab 直接切过去） */
function onOpenFile(node: FileNode) {
  chat.openFile(node.relativePath);
  if (route.name !== 'chat') router.push({ name: 'chat' });
  else closeSidebarOnMobile();
}

//工作区设置弹窗状态
const instructionDialogVisible = ref(false);
const instructionDraft = ref('');
const instructionSaving = ref(false);
const permissionDraft = ref('');

/** 旧版持久化的权限值（EXECUTE/WRITE）映射到新的「手动审批」档；空值表示未设置 */
function normalizeLegacyPermission(p: string | null | undefined): string {
  if (!p) return '';
  if (p === 'MANUAL' || p === 'AUTO' || p === 'FULL' || p === 'READ_ONLY') return p;
  return 'MANUAL';
}
const settingsPath = ref('');

function openWorkspaceSettings() {
  if (!project.currentProjectId) return;
  instructionDraft.value = project.currentProject?.instruction || '';
  permissionDraft.value = normalizeLegacyPermission(project.currentProject?.defaultPermission);
  settingsPath.value = project.currentProject?.workspacePath || '';
  instructionDialogVisible.value = true;
}

async function saveInstruction() {
  instructionSaving.value = true;
  try {
    await project.updateInstruction(instructionDraft.value.trim());
    if (settingsPath.value.trim() && settingsPath.value !== project.currentProject?.workspacePath) {
      await project.setWorkspacePath(settingsPath.value.trim());
    }
    // 默认权限仅在变了的时候才发请求，避免每次都写库
    if (permissionDraft.value !== (project.currentProject?.defaultPermission || '')) {
      await project.updateDefaultPermission(permissionDraft.value);
      // 还没开始的会话立刻同步会话头，用户下一次发消息就按新权限执行
      chat.syncDefaultPermission();
    }
    ElMessage.success('工作区设置已保存，下一轮回答开始生效');
    instructionDialogVisible.value = false;
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  } finally {
    instructionSaving.value = false;
  }
}

// 个人助手定位：主导航只保留「对话 / 资料 / 下载 / 设置」，账号与密钥收进设置页
const navItems = computed(() => {
  const items = [
    { page: 'chat', icon: 'chat', title: '对话' },
    { page: 'knowledge', icon: 'book', title: '我的资料' }
  ];
  if (meta.downloadEnabled) items.push({ page: 'download', icon: 'download', title: '下载桌面端' });
  items.push({ page: 'settings', icon: 'settings', title: '设置' });
  return items;
});

const pageTitle = computed(() => (route.meta.title as string) || 'Lunar Gold');

/** 搜索时同时匹配工作区名和其下会话标题；命中会话的工作区也会保留并自动展开 */
function sessionsOf(projectId: number) {
  return chat.sessionsByProject[projectId] || [];
}

function matchedSessions(projectId: number) {
  const kw = wsKeyword.value.trim().toLowerCase();
  const list = sessionsOf(projectId);
  if (!kw) return list;
  // 工作区名本身命中时展示其下全部会话，只有会话名命中时才收窄
  const proj = project.projects.find((p) => p.id === projectId);
  if (proj && proj.name.toLowerCase().includes(kw)) return list;
  return list.filter((s) => (s.title || '').toLowerCase().includes(kw));
}

// ——— P5-12-2：任务状态点（运行中 / 待审批 / 已完成 / 失败）———
const RUN_STATE_META: Record<string, { cls: string; text: string }> = {
  RUNNING: { cls: 'running', text: '运行中' },
  WAITING_APPROVAL: { cls: 'waiting', text: '待审批' },
  SUCCEEDED: { cls: 'done', text: '已完成' },
  FAILED: { cls: 'failed', text: '失败' },
  CANCELLED: { cls: 'cancelled', text: '已停止' }
};

/** 运行中的轮次优先（runs/running 实时态），其次用会话列表里最近一轮的终态 */
function sessionRunMeta(s: SessionInfo): { cls: string; text: string } | null {
  return RUN_STATE_META[chat.runningRunMap[s.id] || s.lastRunState || ''] || null;
}

function sessionRunClass(s: SessionInfo): string {
  const meta = sessionRunMeta(s);
  return meta ? `is-${meta.cls}` : '';
}

function sessionRunTitle(s: SessionInfo): string {
  return sessionRunMeta(s)?.text || '';
}

const filteredProjects = computed(() => {
  const kw = wsKeyword.value.trim().toLowerCase();
  if (!kw) return project.projects;
  return project.projects.filter(
    (p) => p.name.toLowerCase().includes(kw) || matchedSessions(p.id).length > 0
  );
});

/** 搜索命中的会话，按侧栏展示顺序拍平；键盘上下键在这份列表里移动 */
const searchHits = computed(() => {
  if (!wsKeyword.value.trim()) return [] as { project: ProjectInfo; sessionId: number }[];
  const hits: { project: ProjectInfo; sessionId: number }[] = [];
  filteredProjects.value.forEach((p) => {
    matchedSessions(p.id).forEach((s) => hits.push({ project: p, sessionId: s.id }));
  });
  return hits;
});

const activeSearchSessionId = computed(() => searchHits.value[searchHitIndex.value]?.sessionId ?? null);

// 关键字变了要回到第一条，否则高亮会停在越界的位置
watch(wsKeyword, () => {
  searchHitIndex.value = 0;
});

// 正文搜索：输入停顿 300ms 后才请求后端，避免每敲一个字都扫一遍消息表；
// 序号只认最后一次请求，慢请求回来不会覆盖新关键字的结果
watch(wsKeyword, (kw) => {
  window.clearTimeout(contentSearchTimer);
  const trimmed = kw.trim();
  if (!trimmed) {
    contentSearchSeq++;
    contentHits.value = [];
    return;
  }
  contentSearchTimer = window.setTimeout(async () => {
    const seq = ++contentSearchSeq;
    try {
      const hits = await agentApi.searchMessages(trimmed);
      if (seq === contentSearchSeq) contentHits.value = hits;
    } catch {
      if (seq === contentSearchSeq) contentHits.value = [];
    }
  }, 300);
});

// 打开搜索时自动聚焦，少一次点击
watch(wsSearchOpen, (open) => {
  if (open) nextTick(() => wsSearchEl.value?.focus());
});

function onSearchKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    wsKeyword.value = '';
    return;
  }
  const hits = searchHits.value;
  if (hits.length === 0) return;
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    searchHitIndex.value = (searchHitIndex.value + 1) % hits.length;
    scrollSearchHitIntoView();
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    searchHitIndex.value = (searchHitIndex.value - 1 + hits.length) % hits.length;
    scrollSearchHitIntoView();
  } else if (e.key === 'Enter') {
    e.preventDefault();
    const hit = hits[searchHitIndex.value];
    if (hit) void onSwitchSession(hit.sessionId, hit.project);
  }
}

/** 会话很多时键盘选择可能落在可视区外，等 DOM 更新后滚到最近位置 */
function scrollSearchHitIntoView() {
  nextTick(() => {
    document.querySelector('.session-item.search-active')?.scrollIntoView({ block: 'nearest' });
  });
}

/** 标题里的关键字高亮：拆成命中/未命中片段，避免用 v-html */
function highlightParts(text: string) {
  const kw = wsKeyword.value.trim().toLowerCase();
  if (!kw) return [{ text, hit: false }];
  const parts: { text: string; hit: boolean }[] = [];
  const lower = text.toLowerCase();
  let i = 0;
  while (i < text.length) {
    const idx = lower.indexOf(kw, i);
    if (idx < 0) {
      parts.push({ text: text.slice(i), hit: false });
      break;
    }
    if (idx > i) parts.push({ text: text.slice(i, idx), hit: false });
    parts.push({ text: text.slice(idx, idx + kw.length), hit: true });
    i = idx + kw.length;
  }
  return parts;
}

function isExpanded(projectId: number) {
  // 搜索状态下自动展开命中的工作区
  return expanded[projectId] || (!!wsKeyword.value.trim() && matchedSessions(projectId).length > 0);
}

onMounted(async () => {
  window.addEventListener('resize', onWindowResize);
  onWindowResize();
  // 布局尺寸要等容器挂载后才知道，这里再收敛一次工作台初始宽度
  nextTick(() => {
    dockWidth.value = clampDockWidth(dockWidth.value);
  });
  try {
    // 先拿到项目（没有则后端自动建默认项目），再加载各工作区下的会话
    await project.load();
  } catch (e) {
    // 项目加载失败不阻塞对话：后端不传 projectId 时会回落到默认项目
    console.warn('[Project] 加载失败', e);
  }
  await chat.loadAllSessions();
  // P5-12-2：侧栏状态点需要知道哪些任务还在跑（非对话页进入时也刷新一次）
  chat.refreshRunningRuns();
  // C2: 拉用户设置的默认值（默认模型/推理档位/权限），否则前端硬编码值不会同步后端
  chat.fetchDefaults();
  // 刷新/首屏：拉审查流，避免待审查的文件变更卡片消失（仅桌面端有文件审查）
  if (hasLocalWorkspace.value && project.currentProjectId != null) {
    await chat.fetchPendingChanges(project.currentProjectId);
  }
  // 首屏新会话的会话头权限要对齐工作区默认值（否则显示可执行、后端按项目默认执行）
  chat.syncDefaultPermission();
  // 默认展开当前工作区，用户一眼能看到最近会话
  if (project.currentProjectId != null) expanded[project.currentProjectId] = true;
});

onUnmounted(() => window.removeEventListener('resize', onWindowResize));

/** 工作台里有未保存改动时二次确认，返回 true 表示可以继续 */
async function confirmDiscardEditor(): Promise<boolean> {
  if (!chat.editorDirty) return true;
  try {
    await ElMessageBox.confirm('右侧工作台里还有未保存的文件改动，继续操作会丢失这些改动。', '未保存的改动', {
      confirmButtonText: '放弃改动',
      cancelButtonText: '取消',
      type: 'warning'
    });
    return true;
  } catch {
    return false;
  }
}

/** 切换工作区前：工作台里的文件都属于旧工作区，确认后全部关掉，避免新工作区里还挂着旧路径 */
async function closeEditorBeforeProjectSwitch(): Promise<boolean> {
  if (!chat.openFiles.length) return true;
  if (!(await confirmDiscardEditor())) return false;
  chat.closeAllFiles();
  return true;
}

/** 点击工作区行：切换为当前工作区并展开/收起其会话 */
async function onWorkspaceClick(p: ProjectInfo) {
  if (chat.isProcessing && p.id !== project.currentProjectId) {
    ElMessage.warning('当前回答还在生成中，请稍后再切换工作区');
    return;
  }
  if (p.id !== project.currentProjectId) {
    if (!(await closeEditorBeforeProjectSwitch())) return;
    project.setCurrent(p.id);
    chat.onProjectChanged();
    chat.loadSessions();
    expanded[p.id] = true;
    router.push('/chat');
  } else {
    expanded[p.id] = !expanded[p.id];
  }
}

async function onWorkspaceCommand(command: string, p: ProjectInfo) {
  if (command === 'settings') {
    // 设置针对当前工作区：不是当前工作区时先切过去，保证弹窗里的内容对得上
    if (p.id !== project.currentProjectId) {
      if (chat.isProcessing) {
        ElMessage.warning('当前回答还在生成中，请稍后再切换工作区');
        return;
      }
      if (!(await closeEditorBeforeProjectSwitch())) return;
      project.setCurrent(p.id);
      chat.onProjectChanged();
      chat.loadSessions();
      expanded[p.id] = true;
    }
    openWorkspaceSettings();
  } else if (command === 'newSession') {
    onNewSessionIn(p);
  }
}

function onSessionCommand(command: string, id: number, title?: string) {
  if (command === 'rename') onRenameSession(id, title);
  else if (command === 'delete') onDeleteSession(id);
}

/** 在指定工作区下新建会话（先切到该工作区，避免会话落到别的项目里） */
async function onNewSessionIn(p: ProjectInfo) {
  if (chat.isProcessing) {
    ElMessage.warning('当前回答还在生成中，请稍后再新建任务');
    return;
  }
  if (p.id !== project.currentProjectId) {
    if (!(await closeEditorBeforeProjectSwitch())) return;
    project.setCurrent(p.id);
    chat.onProjectChanged();
    chat.loadSessions();
  }
  expanded[p.id] = true;
  onNewSession();
}

// 新建工作区弹窗：必须输入名称 + 选择磁盘文件夹
const wsDialogVisible = ref(false);
const wsName = ref('');
const wsPath = ref('');
const wsSaving = ref(false);
// 系统文件夹选择窗口：请求会一直挂起到窗口关闭，期间按钮显示加载态防止重复弹窗
const pickingFolder = ref(false);

function openWorkspaceDialog() {
  wsName.value = '';
  wsPath.value = '';
  wsDialogVisible.value = true;
}

/** 打开操作系统原生的文件夹选择窗口，把选中的绝对路径回填到对应弹窗 */
async function pickFolderFor(target: 'new' | 'settings') {
  pickingFolder.value = true;
  try {
    const picked = await workspaceApi.pickFolder();
    if (!picked) return; // 用户取消
    if (target === 'new') wsPath.value = picked;
    else settingsPath.value = picked;
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  } finally {
    pickingFolder.value = false;
  }
}

async function onCreateWorkspace() {
  if (chat.isProcessing) {
    ElMessage.warning('当前回答还在生成中，请稍后再新建工作区');
    return;
  }
  openWorkspaceDialog();
}

async function saveWorkspace() {
  if (!wsName.value.trim()) {
    ElMessage.warning('请输入工作区名称');
    return;
  }
  if (!wsPath.value.trim()) {
    ElMessage.warning('请选择工作区文件夹');
    return;
  }
  wsSaving.value = true;
  try {
    const created = await project.create(wsName.value.trim());
    // 新建后绑定文件夹
    await project.setWorkspacePath(wsPath.value.trim());
    chat.onProjectChanged();
    await chat.loadAllSessions();
    expanded[created.id] = true;
    router.push('/chat');
    ElMessage.success(`已创建工作区「${created.name}」并绑定文件夹`);
    wsDialogVisible.value = false;
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  } finally {
    wsSaving.value = false;
  }
}

function closeSidebarOnMobile() {
  if (window.innerWidth < 768) sidebarOpen.value = false;
}

function onNewSession() {
  chat.startNewSession();
  router.push('/chat');
  closeSidebarOnMobile();
}

async function onSwitchSession(id: number, p?: ProjectInfo) {
  // 侧栏可以点开任意工作区下的会话，先把工作区切过去，避免上下文串味
  if (p && p.id !== project.currentProjectId) {
    if (chat.isProcessing) {
      ElMessage.warning('当前回答还在生成中，请稍后再切换任务');
      return;
    }
    if (!(await closeEditorBeforeProjectSwitch())) return;
    project.setCurrent(p.id);
    chat.onProjectChanged();
    await chat.loadSessions();
  }
  await chat.switchSession(id);
  router.push('/chat');
  closeSidebarOnMobile();
}

/** 正文命中项：切到对应工作区/会话，并滚动到命中的那条消息 */
async function onOpenContentHit(hit: MessageSearchHit) {
  if (chat.isProcessing) {
    ElMessage.warning('当前回答还在生成中，请稍后再打开其他任务');
    return;
  }
  const p = project.projects.find((x) => x.id === hit.projectId);
  await onSwitchSession(hit.sessionId, p);
  // 放到切换完成之后设置：ChatView 收到定位目标时消息已经加载好，才能滚动到具体那一条
  chat.focusMessageId = hit.messageId;
}

async function onDeleteSession(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该任务？', '提示', { type: 'warning' });
  } catch {
    return;
  }
  await chat.removeSession(id);
}

/** 删除工作区：会话与资料一并删除，磁盘上的代码文件夹保留不动 */
async function onDeleteWorkspace(p: ProjectInfo) {
  if (chat.isProcessing) {
    ElMessage.warning('当前回答还在生成中，请稍后再删除工作区');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确定删除工作区「${p.name}」？其下所有任务和资料会一并删除，磁盘上的文件夹不会被删除。`,
      '删除工作区',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    const wasCurrent = p.id === project.currentProjectId;
    // remove() 内部会重新拉列表，当前工作区被删时自动回落到默认工作区
    await project.remove(p.id);
    delete expanded[p.id];
    if (wasCurrent) {
      chat.onProjectChanged();
      router.push('/chat');
    }
    await chat.loadAllSessions();
    ElMessage.success('工作区已删除');
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  }
}

/** 重命名会话：双击标题触发，避免误点 */
async function onRenameSession(id: number, current: string | undefined) {
  if (chat.isProcessing) {
    ElMessage.warning('当前回答还在生成中，请稍后再重命名');
    return;
  }
  try {
    const { value } = await ElMessageBox.prompt('给这个任务起个更好找的名字', '重命名任务', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: current || '',
      inputValidator: (v: string) => (!!v && !!v.trim()) || '请输入名称'
    });
    await chat.renameSession(id, value);
    ElMessage.success('已重命名');
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message);
  }
}

async function onLogout() {
  // 先清本地会话与项目选择，避免换账号登录时短暂看到上一个账号的内容
  chat.onProjectChanged();
  project.setCurrent(null);
  await auth.logout();
  router.push('/login');
}
</script>

<style scoped>
/* 图标统一后补一点对齐：只加布局属性，不覆盖原有配色 */
.sidebar-header,
.nav-item,
.sidebar-user,
.new-session-btn,
.ws-action,
.ws-del,
.ws-new-session,
.menu-toggle {
    display: flex;
    align-items: center;
}

.sidebar-header { gap: 8px; }
.nav-item { gap: 8px; }
.sidebar-user { gap: 6px; }
.new-session-btn { justify-content: center; gap: 6px; }
.ws-action,
.ws-del,
.ws-new-session,
.menu-toggle { justify-content: center; }

.sidebar { transition: width 0.2s ease; }

/* 侧栏同级切换：会话 / 文件 */
.side-switch {
    display: flex;
    gap: 2px;
    background: var(--surface-2);
    border-radius: var(--radius-sm);
    padding: 2px;
}
.side-switch-btn {
    border: none;
    background: none;
    padding: 4px 12px;
    border-radius: var(--radius-sm);
    font-size: var(--fs-xs);
    color: var(--text-secondary);
    cursor: pointer;
    transition: all var(--dur-fast, .15s);
}
.side-switch-btn:hover { color: var(--text); }
.side-switch-btn.active {
    background: var(--surface);
    color: var(--primary);
    font-weight: 600;
    box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,.06));
}

/* 网页端没有工作区切换：只保留「会话」标题，外观对齐切换条高度 */
.ws-section-plain {
    font-size: var(--fs-xs);
    color: var(--text-secondary);
    padding: 4px 12px;
}

/* 网页端不展示工作区行，会话列表去掉缩进 */
.ws-sessions.flat { padding-left: 0; }

.ws-file-empty {
    padding: 24px 12px;
    text-align: center;
    color: var(--text-secondary);
    font-size: var(--fs-sm);
}
.ws-file-empty p { margin: 0 0 10px; }
.ws-file-empty-btn {
    border: 1px solid var(--border);
    background: var(--surface);
    color: var(--primary);
    border-radius: var(--radius-sm);
    padding: 5px 12px;
    font-size: var(--fs-xs);
    cursor: pointer;
}
.ws-file-empty-btn:hover { background: var(--surface-hover); }

/* 折叠态只作用于桌面端；<768px 时侧栏是 fixed 浮层，走 .open 逻辑 */
@media (min-width: 769px) {
    .sidebar.collapsed { width: 56px; }
    .sidebar.collapsed .brand-text,
    .sidebar.collapsed .nav-text,
    .sidebar.collapsed .new-session-text,
    .sidebar.collapsed .footer-text { display: none; }
    .sidebar.collapsed .ws-section { display: none; }
    .sidebar.collapsed .sidebar-header { justify-content: center; gap: 4px; padding: 0 4px; }
    .sidebar.collapsed .sidebar-nav { padding: 10px 6px; }
    .sidebar.collapsed .new-session-btn { margin: 0 6px 8px; }
    .sidebar.collapsed .sidebar-footer { padding: 12px 6px; }
    .sidebar.collapsed .nav-item,
    .sidebar.collapsed .new-session-btn { justify-content: center; padding-left: 0; padding-right: 0; }
    .sidebar.collapsed .sidebar-footer { justify-content: center; }
}

/* 第五十二批：MCP Servers 状态面板 */
.mcp-loading, .mcp-empty {
  font-size: 13px;
  color: var(--text-secondary);
  padding: 8px 0;
}
.mcp-empty code {
  background: var(--bg-secondary);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 12px;
}
.mcp-servers {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 4px;
}
.mcp-server-item {
  background: var(--bg-secondary);
  border-radius: 6px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.mcp-server-info {
  display: flex;
  align-items: center;
  gap: 8px;
}
.mcp-server-name {
  font-weight: 500;
  font-size: 13px;
}
.mcp-server-status {
  font-size: 12px;
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}
.mcp-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}
.mcp-dot.mcp-up { background: var(--success); }
.mcp-dot.mcp-down { background: var(--danger); }
.mcp-dot.mcp-unknown { background: var(--warning); }
.mcp-server-error {
  font-size: 12px;
  color: var(--danger);
  line-height: 1.4;
}
.mcp-server-fails {
  font-size: 11px;
  color: var(--text-secondary);
  font-style: italic;
}

/* 任务详情侧边栏是 main 的兄弟节点：整列高度，直接把会话区顶窄 */
.dock-resizer {
    width: 4px;
    flex-shrink: 0;
    position: relative;
    cursor: col-resize;
    background: transparent;
}
/* 悬停/拖动才在留白中间显一小段细线，平时只是一段背景留白；
   上下各留出头部高度与边距，任何时候都不会贯穿顶栏把顶层切开 */
.dock-resizer::after {
    content: '';
    position: absolute;
    top: calc(var(--header-height) + 6px);
    bottom: 8px;
    left: 50%;
    width: 2px;
    transform: translateX(-50%);
    border-radius: 2px;
    background: var(--primary);
    opacity: 0;
    transition: opacity var(--dur-fast);
}
.dock-resizer:hover::after,
.dock-resizer.dragging::after {
    opacity: 0.5;
}

/* <1024px：工作台不挤压正文，改为右侧整列浮层 + 遮罩 */
@media (max-width: 1023px) {
    .dock-backdrop {
        position: fixed;
        inset: 0;
        z-index: 2399;
        background: rgb(0 0 0 / 28%);
    }
    .workbench:not(.fullscreen) {
        position: fixed;
        top: 0;
        right: 0;
        bottom: 0;
        z-index: 2400;
        width: min(calc(100vw - 56px), 520px) !important;
        box-shadow: var(--shadow-overlay);
    }
    .dock-resizer { display: none; }
}

/* 全屏态让出左侧导航；折叠成图标轨时只让 56px */
@media (min-width: 769px) {
    .sidebar.collapsed ~ .workbench.fullscreen { left: 56px; }
}
</style>
