<template>
  <!-- 用户消息 -->
  <div v-if="msg.role === 'user'" class="message user">{{ msg.content }}</div>

  <!-- 附件提示条 -->
  <div v-else-if="msg.role === 'hint'" class="message attachment-hint">{{ msg.content }}</div>

  <!-- 助手消息 -->
  <div v-else class="message assistant">
    <!-- 生成中且还没有正文：思考过程自动展开，让用户看到当前动作 -->
    <div v-if="msg.thinking && msg.streaming && !msg.content" class="thinking-box">
      <div class="thinking-title"><Icon name="sparkles" :size="14" /> 正在思考…</div>
      <div class="thinking-content">{{ msg.thinking }}</div>
    </div>

    <!-- 思考与步骤合并成一个折叠入口：完成后只占一行「已完成，用时…」 -->
    <div v-else-if="msg.thinking || msg.tools.length" class="progress">
      <button class="progress-toggle" @click="detailOpen = !detailOpen">
        <span v-if="hasRunning" class="steps-spinner"></span>
        <Icon v-else name="sparkles" :size="13" />
        <span class="progress-text">{{ progressSummary }}</span>
        <span class="steps-chevron">{{ detailOpen ? '收起' : '查看' }}</span>
      </button>
      <div v-if="detailOpen" class="progress-body">
        <!-- 思考链与工具调用按真实顺序排成一条时间线：思考在前，工具调用紧跟其后 -->
        <div class="timeline">
          <template v-for="(item, i) in timeline" :key="i">
            <div v-if="item.type === 'thought'" class="thinking-content progress-thinking">{{ item.text }}</div>
            <div v-else class="step-item">
              <div class="step-row">
                <Icon :name="stepIcon(item.tool.name)" :size="14" class="step-icon" />
                <span class="step-label">{{ stepLabel(item.tool) }}</span>
                <span class="step-state" :class="item.tool.status">{{ stepState(item.tool) }}</span>
              </div>
              <!-- 展开后补上这次调用的参数与结果摘要，不用再翻后端日志 -->
              <div v-if="stepParams(item.tool) || stepOutput(item.tool)" class="step-detail">
                <div v-if="stepParams(item.tool)" class="step-detail-row">
                  <span class="step-detail-label">参数</span>
                  <span class="step-detail-text">{{ stepParams(item.tool) }}</span>
                </div>
                <div v-if="stepOutput(item.tool)" class="step-detail-row">
                  <span class="step-detail-label">结果</span>
                  <span class="step-detail-text">{{ stepOutput(item.tool) }}</span>
                </div>
              </div>
              <!-- 失败时直接给出原因：否则用户只看到「没成功」，排查要翻后端日志 -->
              <div v-if="item.tool.status === 'error'" class="step-error">
                {{ truncate(stepError(item.tool), 200) }}
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>

    <div class="ai-answer">
      <MarkdownContent v-if="msg.content" :content="msg.content" />
      <div v-else-if="msg.streaming" class="md-content">
        <div class="typing"><span></span><span></span><span></span></div>
      </div>
      <!-- 已有部分正文但仍在生成：末尾追加动态省略号，避免看起来像回答卡住 -->
      <span v-if="msg.content && msg.streaming && !hasRunning" class="typing-inline" aria-label="正在生成">
        <i></i><i></i><i></i>
      </span>
      <div v-if="msg.stopped" class="stopped-hint"><Icon name="stop" :size="12" /> 已停止生成</div>
      <div v-if="msg.sources?.length" class="sources">
        <span class="sources-label">参考了</span>
        <span
          v-for="s in msg.sources"
          :key="s.id"
          class="source-chip"
          :class="{ expanded: expandedSources.has(s.id) }"
          :title="`相关度 ${s.score.toFixed(2)} · 点击${expandedSources.has(s.id) ? '收起' : '查看片段'}`"
          @click="toggleSource(s.id)"
        ><Icon name="file-text" :size="12" /> {{ s.name }}</span>
        <div
          v-for="s in msg.sources"
          v-show="expandedSources.has(s.id) && s.content"
          :key="'c-' + s.id"
          class="source-content"
        >
          <p class="source-text" :class="{ truncated: !expandedAll.has(s.id) }">
            {{ (expandedAll.has(s.id) ? s.content! : truncate(s.content!, 300)).trim() }}
          </p>
          <button
            v-if="s.content && s.content.length > 300"
            class="source-toggle"
            @click.stop="toggleAll(s.id)"
          >{{ expandedAll.has(s.id) ? '收起片段' : '展开全部' }}</button>
        </div>
      </div>
    </div>

    <!-- 断线/报错：给出原因和「重试这一轮」，不用重新输入问题 -->
    <div v-if="msg.failed" class="failed-box">
      <div class="failed-text"><Icon name="alert" :size="14" /> {{ msg.errorText || '这一轮没有完成' }}</div>
      <button class="retry-btn" @click="chat.regenerate(msg.id)">
        <Icon name="refresh" :size="13" /> 重试这一轮
      </button>
    </div>

    <div v-if="!msg.streaming && msg.content && !msg.failed" class="msg-actions">
      <button class="msg-action-btn" @click="onCopy"><Icon name="copy" :size="13" /> 复制</button>
      <button class="msg-action-btn" @click="chat.regenerate(msg.id)"><Icon name="refresh" :size="13" /> 重新生成</button>
    </div>

    <!-- 本次改动放在回答最下面：先看总变动，展开是文件清单，点某个文件再到右侧查看逐行 diff -->
    <div v-if="msg.changes?.length" class="file-changes">
      <button class="fc-summary" @click="changesOpen = !changesOpen">
        <span class="fc-summary-left">
          <Icon name="file-text" :size="15" />
          <span class="fc-title">{{ msg.changes.length }} 个文件已更改</span>
        </span>
        <span class="fc-summary-right">
          <span class="fc-add">+{{ totalAdditions }} 行</span>
          <span class="fc-del">-{{ totalDeletions }} 行</span>
          <span class="steps-chevron">{{ changesOpen ? '收起' : '展开' }}</span>
        </span>
      </button>
      <div v-if="changesOpen" class="file-change-list">
        <button
          v-for="c in msg.changes"
          :key="c.path"
          class="file-change-row"
          :title="c.action === 'delete' ? `${c.path} 已删除，可在待审查改动中撤回` : `在右侧查看 ${c.path} 的改动`"
          @click="emit('open-change', c.path, c.action)"
        >
          <Icon :name="c.action === 'create' ? 'plus' : c.action === 'delete' ? 'trash' : 'file-text'" :size="14" class="fc-icon" />
          <span class="fc-name">{{ fileName(c.path) }}</span>
          <span class="fc-path" :title="c.path">{{ dirName(c.path) }}</span>
          <span v-if="c.source === 'run_command'" class="fc-source">命令</span>
          <span class="fc-stat">
            <span class="fc-add">+{{ c.additions }}</span>
            <span class="fc-del">-{{ c.deletions }}</span>
          </span>
          <Icon name="chevron-right" :size="13" class="fc-arrow" />
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import type { ToolInvocation, UiMessage } from '../types/api';
import { useChatStore } from '../stores/chat';
import { copyToClipboard } from '../utils/format';
import MarkdownContent from './MarkdownContent.vue';
import Icon from './Icon.vue';

const props = defineProps<{ msg: UiMessage }>();
const emit = defineEmits<{ (e: 'open-change', path: string, action: string): void }>();
const chat = useChatStore();

// 思考内容 + 步骤明细合并后的展开状态（默认只显示一行摘要）
const detailOpen = ref(false);
// 文件改动清单的展开状态；点某个文件后统一到右侧面板看 diff
const changesOpen = ref(false);

const totalAdditions = computed(() =>
  (props.msg.changes || []).reduce((sum, c) => sum + (c.additions || 0), 0)
);
const totalDeletions = computed(() =>
  (props.msg.changes || []).reduce((sum, c) => sum + (c.deletions || 0), 0)
);

/** 文件清单里把文件名和所在目录拆开显示，长路径在窄屏也能看清文件本身 */
function fileName(path: string): string {
  const parts = path.replace(/\\/g, '/').split('/');
  return parts[parts.length - 1] || path;
}
function dirName(path: string): string {
  const idx = path.replace(/\\/g, '/').lastIndexOf('/');
  return idx < 0 ? '' : path.slice(0, idx + 1);
}

//片段展开状态：Set<sourceId>
const expandedSources = ref(new Set<number>());
const expandedAll = ref(new Set<number>());

function toggleSource(id: number) {
  const next = new Set(expandedSources.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  expandedSources.value = next;
}
function toggleAll(id: number) {
  const next = new Set(expandedAll.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  expandedAll.value = next;
}
function truncate(s: string, n: number): string {
  return s.length <= n ? s : s.slice(0, n) + '…';
}
const hasRunning = computed(() => props.msg.tools.some((t) => t.status === 'running'));
const runningTool = computed(() => props.msg.tools.find((t) => t.status === 'running'));

/** 毫秒转「秒 / 分 秒」，参考样式里的「已完成，用时 10 分 48 秒」 */
function formatDuration(ms?: number): string {
  if (!ms || ms < 0) return '';
  const total = Math.round(ms / 1000);
  if (total < 1) return '不到 1 秒';
  if (total < 60) return `${total} 秒`;
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return seconds ? `${minutes} 分 ${seconds} 秒` : `${minutes} 分钟`;
}

// 一行摘要：运行中显示当前动作；完成后显示耗时（老消息没有耗时则退回步骤数）
const progressSummary = computed(() => {
  if (runningTool.value) return stepLabel(runningTool.value);
  if (props.msg.streaming) return `正在执行 · 已完成 ${props.msg.tools.length} 个步骤`;
  const duration = formatDuration(props.msg.durationMs);
  if (duration) return `已完成，用时 ${duration}`;
  return props.msg.tools.length ? `已完成 ${props.msg.tools.length} 个步骤` : '已完成';
});

function stepIcon(name: string): string {
  const icons: Record<string, string> = {
    web_search: 'search',
    fetch_webpage: 'file-text',
    get_current_weather: 'cloud',
    get_current_time: 'clock',
    calculator: 'hash',
    list_dir: 'folder',
    read_file: 'book',
    write_file: 'save',
    edit_file: 'pencil',
    delete_file: 'trash',
    search_files: 'search',
    run_command: 'terminal'
  };
  return icons[name] || 'search';
}

/** 时间线条目：思考片段或一次工具调用（discriminated union，模板里不用再判空） */
type TimelineItem =
  | { type: 'thought'; text: string }
  | { type: 'tool'; tool: ToolInvocation };

/** 思考链按行切开，工具调用按顺序插入其后，形成「想→做→想」的时间线 */
const timeline = computed<TimelineItem[]>(() => {
  const items: TimelineItem[] = [];
  const lines = (props.msg.thinking || '').split('\n').map((l) => l.trim()).filter(Boolean);
  lines.forEach((l) => items.push({ type: 'thought', text: l }));
  props.msg.tools.forEach((t) => items.push({ type: 'tool', tool: t }));
  return items;
});

/** 把工具名 + 参数翻译成一句人话，例如「搜索网络「北京天气」」 */
function stepLabel(tool: ToolInvocation): string {
  let args: Record<string, unknown> = {};
  try {
    args = JSON.parse(tool.args) || {};
  } catch {
    /* 参数不是 JSON 时按空处理 */
  }
  const pick = (key: string) => (typeof args[key] === 'string' ? (args[key] as string) : '');
  const running = tool.status === 'running';
  const short = (s: string, n = 40) => (s.length > n ? s.slice(0, n) + '…' : s);

  // 第五十一批：MCP 外部工具参数不固定，统一用中性文案，别把 mcp_xxx 工具名暴露给用户
  if (tool.name.startsWith('mcp_')) {
    return running ? '正在调用外部工具…' : '调用外部工具';
  }

  switch (tool.name) {
    case 'web_search': {
      const q = pick('query');
      return q ? `搜索网络「${q}」` : '搜索网络';
    }
    case 'fetch_webpage':
      return '打开网页查看内容';
    case 'get_current_weather': {
      const city = pick('city');
      return city ? `查询「${city}」的天气` : '查询天气';
    }
    case 'get_current_time':
      return '查看当前时间';
    case 'calculator':
      return '进行计算';
    case 'list_dir': {
      const p = pick('path');
      return running ? '正在查看目录结构…' : p ? `查看目录「${p}」` : '查看目录结构';
    }
    case 'read_file': {
      const p = pick('path');
      return running ? `正在读取「${p || '文件'}」…` : p ? `读取「${p}」` : '读取文件';
    }
    case 'write_file': {
      const p = pick('path');
      return running ? `正在写入「${p || '文件'}」…` : p ? `写入「${p}」` : '写入文件';
    }
    case 'edit_file': {
      const p = pick('path');
      return running ? `正在修改「${p || '文件'}」…` : p ? `修改「${p}」` : '修改文件';
    }
    case 'search_files': {
      const q = pick('pattern');
      return running ? `正在搜索「${q}」…` : q ? `搜索代码「${q}」` : '搜索代码';
    }
    case 'run_command': {
      const cmd = pick('command');
      const label = cmd ? short(cmd) : '终端命令';
      return running ? `正在运行 ${label}…` : `运行 ${label}`;
    }
    default:
      return '查找信息';
  }
}

function stepState(tool: ToolInvocation): string {
  if (tool.status === 'running') return '进行中';
  return tool.status === 'success' ? '已完成' : '没成功';
}

/** 参数摘要：按工具把关键字段翻成人话，未知结构退回压缩后的 JSON；不展开长内容 */
function stepParams(tool: ToolInvocation): string {
  let args: Record<string, unknown> = {};
  try {
    args = JSON.parse(tool.args) || {};
  } catch {
    return truncate(tool.args || '', 200);
  }
  const keys = Object.keys(args);
  if (!keys.length) return '';
  const parts: string[] = [];
  const add = (label: string, key: string, max = 120) => {
    const v = args[key];
    if (typeof v === 'string' && v.trim()) parts.push(`${label}：${truncate(v.trim(), max)}`);
    else if (typeof v === 'number') parts.push(`${label}：${v}`);
  };

  switch (tool.name) {
    case 'web_search':
      add('关键词', 'query');
      break;
    case 'fetch_webpage':
      add('网址', 'url');
      break;
    case 'get_current_weather':
      add('城市', 'city');
      break;
    case 'calculator':
      add('算式', 'expression');
      break;
    case 'list_dir':
      add('目录', 'path');
      break;
    case 'read_file':
      add('文件', 'path');
      break;
    case 'write_file':
      add('文件', 'path');
      if (typeof args.content === 'string') parts.push(`写入 ${args.content.length} 字`);
      break;
    case 'edit_file':
      add('文件', 'path');
      if (typeof args.oldText === 'string' && typeof args.newText === 'string') {
        parts.push(`替换 ${args.oldText.length} → ${args.newText.length} 字`);
      }
      break;
    case 'search_files':
      add('搜索', 'pattern');
      add('范围', 'glob');
      break;
    case 'run_command':
      add('命令', 'command', 200);
      add('目录', 'cwd');
      break;
  }
  if (parts.length) return parts.join(' · ');
  // 第五十一批：MCP 外部工具按常见字段拼人话，避免直接甩一整段 JSON
  if (tool.name.startsWith('mcp_')) {
    add('路径', 'path');
    add('地址', 'uri');
    add('关键词', 'query');
    add('模式', 'pattern');
    if (parts.length) return parts.join(' · ');
  }
  return truncate(JSON.stringify(args).replace(/\s+/g, ' '), 200);
}

/** 结果摘要：成功时截取开头一段；失败原因已单独展示，这里不重复 */
function stepOutput(tool: ToolInvocation): string {
  if (tool.status === 'running' || tool.status === 'error') return '';
  const raw = (tool.result || '').replace(/\s+/g, ' ').trim();
  return raw ? truncate(raw, 300) : '';
}

/** 失败原因：优先 error，退回 result 的首行，保证永远有可读信息 */
function stepError(tool: ToolInvocation): string {
  const raw = tool.error || tool.result || '执行失败，没有返回原因';
  const firstLine = raw.split('\n').find((line) => line.trim()) || raw;
  return firstLine.trim();
}

async function onCopy() {
  await copyToClipboard(props.msg.content);
}
</script>

<style scoped>
/* 失败提示条：原因在左、重试按钮在右，宽度跟随内容不铺满 */
.failed-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  padding: 8px 12px;
  border: 1px solid var(--danger);
  border-radius: 8px;
  background: var(--danger-bg);
  color: var(--danger);
  font-size: 13px;
}

.failed-text {
  display: flex;
  align-items: center;
  gap: 6px;
  word-break: break-word;
}

.retry-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border: 1px solid var(--danger);
  border-radius: 6px;
  background: transparent;
  color: var(--danger);
  font-size: 12px;
  cursor: pointer;
}

.retry-btn:hover {
  background: var(--danger);
  color: var(--surface);
}

/* 图标与文字统一基线：只在需要的地方补对齐，不改动原有排版 */
.thinking-title,
.fc-title,
.stopped-hint,
.source-chip,
.msg-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.fc-icon {
  display: inline-flex;
  align-items: center;
  color: var(--text-secondary);
}

/* 正文末尾的动态省略号：现有内容还在继续输出时给一个明确信号 */
.typing-inline {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 8px;
  margin-top: 2px;
}

.typing-inline i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--primary);
  animation: typing-blink 1.4s infinite ease-in-out both;
}

.typing-inline i:nth-child(1) { animation-delay: -0.32s; }
.typing-inline i:nth-child(2) { animation-delay: -0.16s; }

@keyframes typing-blink {
  0%, 80%, 100% { transform: scale(0.4); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

</style>
