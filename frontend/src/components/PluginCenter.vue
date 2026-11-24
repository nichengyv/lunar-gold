<template>
  <div class="plugin-center">
    <el-tabs v-model="activeTab" class="pc-tabs">
      <el-tab-pane name="installed">
        <template #label>
          <span class="pc-tab-label"><Icon name="cpu" :size="13" /> 已安装</span>
        </template>
        <McpServersPanel />
      </el-tab-pane>

      <el-tab-pane name="guide">
        <template #label>
          <span class="pc-tab-label"><Icon name="sparkles" :size="13" /> 安装指引</span>
        </template>
        <div class="pc-guide">
          <section class="pc-section">
            <h3>1. 去哪里找 MCP Server</h3>
            <p>本应用不内置在线插件市场，来源由你自己决定。以下目录收录了大量现成 server：</p>
            <ul class="pc-links">
              <li>
                <a href="https://github.com/modelcontextprotocol/servers" target="_blank" rel="noopener">官方参考实现</a>
                — 官方维护，质量最稳
              </li>
              <li>
                <a href="https://smithery.ai" target="_blank" rel="noopener">Smithery</a>
                — 条目最多，支持在线试用
              </li>
              <li>
                <a href="https://glama.ai/mcp/servers" target="_blank" rel="noopener">Glama</a>
                — 带评分与安全提示
              </li>
              <li>
                <a href="https://mcp.so" target="_blank" rel="noopener">mcp.so</a>
                — 社区目录
              </li>
            </ul>
          </section>

          <section class="pc-section">
            <h3>2. 怎么添加</h3>
            <p>
              在 server 的介绍页复制配置 JSON，回到「已安装」点「添加」粘贴即可。支持三种写法，
              也支持一次粘贴多个 server。
            </p>
            <div v-for="ex in examples" :key="ex.title" class="pc-example">
              <div class="pc-example-head">
                <span class="pc-example-title">{{ ex.title }}</span>
                <button class="pc-copy" @click="copy(ex.json, ex.title)">复制</button>
              </div>
              <pre class="pc-code">{{ ex.json }}</pre>
              <div class="pc-example-note">{{ ex.note }}</div>
            </div>
          </section>

          <section class="pc-section">
            <h3>3. 运行环境</h3>
            <ul class="pc-links">
              <li>
                Node.js ≥ 18（npx 启动的 server 需要）—
                <a href="https://nodejs.org/" target="_blank" rel="noopener">下载</a>
              </li>
              <li>
                uv / Python（uvx 或 python 启动的 server 需要）—
                <a href="https://github.com/astral-sh/uv" target="_blank" rel="noopener">下载 uv</a>
              </li>
            </ul>
            <p class="pc-muted">Windows 下命令照抄 npx 即可，程序会自动补 .cmd。</p>
          </section>

          <section class="pc-section pc-section-warn">
            <h3>4. 安全提示</h3>
            <p>
              添加的命令会以当前用户权限在本机执行，能读写你的文件。只添加可信来源；
              每个工具调用都会走审批，不需要时随时用开关停用或删除。
            </p>
          </section>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import Icon from './Icon.vue';
import McpServersPanel from './McpServersPanel.vue';

const activeTab = ref<'installed' | 'guide'>('installed');

const examples = [
  {
    title: '标准 mcpServers 格式（推荐）',
    note: '大多数 server 的介绍页给的就是这种，支持一次粘贴多个。',
    json: JSON.stringify(
      {
        mcpServers: {
          everything: {
            command: 'npx',
            args: ['-y', '@modelcontextprotocol/server-everything']
          }
        }
      },
      null,
      2
    )
  },
  {
    title: '需要凭证的 server',
    note: '凭证写在 env 里，保存后只回显变量名，不回显值。',
    json: JSON.stringify(
      {
        mcpServers: {
          github: {
            command: 'npx',
            args: ['-y', '@modelcontextprotocol/server-github'],
            env: { GITHUB_TOKEN: 'ghp_xxxxxxxxxxxx' }
          }
        }
      },
      null,
      2
    )
  },
  {
    title: '单个 server 平铺',
    note: '不想用 mcpServers 包裹时，也可以直接给 name / command / args。',
    json: JSON.stringify(
      {
        name: 'everything',
        command: 'npx',
        args: ['-y', '@modelcontextprotocol/server-everything']
      },
      null,
      2
    )
  }
];

async function copy(text: string, label: string) {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(`已复制：${label}`);
  } catch {
    ElMessage.warning('复制失败，请手动选择文本');
  }
}
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
.pc-guide {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 4px;
}
.pc-section {
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface);
}
.pc-section h3 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}
.pc-section p {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-muted);
}
.pc-links {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
.pc-links a {
  color: var(--el-color-primary, #409eff);
  text-decoration: none;
}
.pc-muted {
  margin: 6px 0 0;
  font-size: 11px;
}
.pc-example {
  margin-top: 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}
.pc-example-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 12px;
  background: var(--surface-2, transparent);
  border-bottom: 1px solid var(--border);
}
.pc-example-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text);
}
.pc-copy {
  padding: 2px 10px;
  border: 1px solid var(--border);
  border-radius: 5px;
  background: transparent;
  color: var(--text-muted);
  font-size: 11px;
  cursor: pointer;
}
.pc-copy:hover {
  color: var(--text);
  background: var(--surface-hover);
}
.pc-code {
  margin: 0;
  padding: 12px;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11px;
  line-height: 1.6;
  color: var(--text);
  overflow: auto;
  background: transparent;
}
.pc-example-note {
  padding: 0 12px 10px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--text-muted);
}
.pc-section-warn {
  border-color: #c2410c44;
  background: #c2410c08;
}
</style>
