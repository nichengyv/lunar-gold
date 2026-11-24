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
            <p>
              本应用采用开放接入方式，不绑定特定插件源。你可以从任意目录或文档获取 MCP Server 配置，
              粘贴即可使用。以下目录收录了大量现成 server：
            </p>
            <ul class="pc-links">
              <li>
                <a href="https://github.com/modelcontextprotocol/servers" target="_blank" rel="noopener">官方参考实现</a>
                — 官方维护，质量最稳
              </li>
              <li>
                <a href="https://mcp.so" target="_blank" rel="noopener">mcp.so</a>
                — 纯目录，配置格式规范，复制即可用
              </li>
              <li>
                <a href="https://smithery.ai" target="_blank" rel="noopener">Smithery</a>
                — 条目最多，支持在线试用
              </li>
              <li>
                <a href="https://glama.ai/mcp/servers" target="_blank" rel="noopener">Glama</a>
                — 带评分与安全提示
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
            <p class="pc-muted">
              Windows 下 npx / npm / pnpm / yarn 会自动补 .cmd；uvx、python 或自定义程序请写完整命令名（含扩展名）。
            </p>
          </section>

          <section class="pc-section">
            <h3>4. 验证是否成功</h3>
            <p>
              添加后回到「已安装」列表，状态显示「已连接」即表示成功，点击工具数可展开确认它提供的工具。
              如果显示「连接失败」，错误原因会直接显示在条目下方，也可以点「测试连接」重新探测。
            </p>
            <ul class="pc-links">
              <li>未安装 Node.js：npx 启动的 server 需要 Node.js ≥ 18</li>
              <li>未安装 uv / Python：uvx 或 python 启动的 server 需要</li>
              <li>包名拼写错误或版本不存在：核对 args 里的包名</li>
              <li>缺少 API Key：检查 env 中是否填了必需凭证</li>
            </ul>
          </section>

          <section class="pc-section">
            <h3>5. 配置存储</h3>
            <p>
              所有配置保存在本机用户数据目录，不会上传。换机器时用「导出配置」生成 JSON 文件，
              在新机器上「导入文件」即可迁移；环境变量值不会导出，需要在导入后重新填写。
            </p>
          </section>

          <section class="pc-section pc-section-warn">
            <h3>6. 安全提示</h3>
            <p>
              添加的命令会以当前用户权限在本机执行，能读写你的文件。只添加可信来源。
              内置的写文件与命令执行按当前权限档位审批；MCP 的写入、删除类工具强制审批，
              只读工具不会弹窗；同一会话内可以「始终允许」某个工具，也支持批量批准。
              不需要时随时用开关停用或删除。
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
