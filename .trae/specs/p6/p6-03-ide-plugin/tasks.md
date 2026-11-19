# P6-03 IDE 薄插件（先 VS Code）- 技术任务清单

> 约定：落点 `ide/vscode/`；纯逻辑（`src/shared/`）只用可擦除 TS，`node --test "ide/vscode/test/**/*.test.ts"` 直接跑；宿主层（依赖 `vscode` 模块）不进单测，靠人工验收。
> 跨入口共享契约（SSE 事件名、`sinceSeq` 语义、审批 / 撤回请求形态）一旦改动必须同步改 spec 与 CLI 文档。

## T1 扩展脚手架

- 新增 `ide/vscode/package.json`：`engines.vscode >=1.95`、`extensionKind: ["ui"]`、`main: ./dist/extension.js`、`activationEvents`（命令 / 视图）、`contributes`（8 条命令、侧边栏视图容器、状态栏由代码注册、2 项设置）；`devDependencies` 仅 `esbuild` / `typescript` / `@types/vscode` / `@vscode/vsce`，运行时零依赖。
- 新增 `tsconfig.json`（`strict`）、`esbuild` 脚本（bundle → `dist/extension.js`，`external: ["vscode"]`）、`.vscodeignore`、最小 `src/extension.ts`（注册命令占位）。
- 约束：不新增根级 `package.json` / workspace；`src/shared/` 禁 `enum` / 参数属性 / `namespace`。
- 完成判据：`npm run build` 产出单文件；F5 能起 Extension Development Host，命令面板能看到 8 条命令。

## T2 本机 API 客户端与契约校验（含 diffText 核实）

- 新增 `src/shared/endpoints.ts`：「端点 → 功能」映射（对齐 spec FR-1/3/5/6/7），`tokenKind` 区分 client / local；测试与 `src/test/resources/contract/public-api.json` 比对，唯一 local 例外 `POST /api/clients`。
- 新增 `src/shared/http.ts`：`fetch` 封装、Bearer、`ApiResponse` 信封解析、超时；错误映射与 CLI 一致（401/403 → 认证引导，5xx/网络 → 可重试错误，业务错误原样透出 message）。
- 新增 `src/shared/types.ts`：`ProjectSummary` / `SessionSummary` / `RunEventItem` / `ChangeSetView` / `ApprovalRequired` / `RevertResult` 等，以后端 DTO 为准。
- **核实 `ChangeView.diffText`**：读 `ChangeSetService` 生成逻辑，确认是否完整 unified diff、是否截断、新增 / 删除文件的表示；结论写回 spec 决策 7，并据此定 `diff` 模块输入。
- 完成判据：端点映射测试绿；错误映射各分支有单测（注入假 `fetch`）；`diffText` 结论已落到 spec。

## T3 连接与令牌引导

- 新增 `src/vscode/credentials.ts`：SecretStorage 读写、`connect` 流程（health → 既有令牌校验 → 本地用户令牌换发 → 存 SecretStorage）、手动粘贴令牌入口。
- 新增 `disconnect`：`DELETE /api/clients/self` → 清 SecretStorage 与 `workspaceState`。
- 未连接 / 401 / 403（含「缺少 scope」）时的引导通知，不弹堆栈；health 校验 `apiContractVersion == 1`。
- 完成判据：无后端、无本地令牌、令牌失效、scope 不足四种场景各有明确提示；换发流程的纯逻辑部分（令牌来源优先级、health 判定）有单测；`connect` 后 `GET /api/clients/self` 可见 `vscode-<hostname>`。

## T4 SSE 解析与事件归约 + 续传

- 新增 `src/shared/sse.ts`（参考 `cli/src/sse.ts`），单测直接读 `cli/test/fixtures/sse/*.txt` 六个 fixture，断言与 CLI 一致。
- 新增 `src/shared/reducer.ts`：SSE 事件 / 落库 `RunEventItem` → 统一的「面板事件」序列（session/run/turn/content/tool/approval/file_change/done/error），含回放去重（正文累计长度取后缀、工具 / 审批 / 完成按指纹计数）。
- 新增 `src/shared/resume.ts`：`runId + lastSeq` 轮询续传，3 次重试退避 1s/2s/4s，401/403 不重试。
- 完成判据：断流 → 回放场景单测证明正文不重复、工具事件不重复；`sinceSeq` 参数断言正确；重试与退避有单测（注入假时钟）。

## T5 发起任务与流式面板

- 新增 `src/vscode/taskController.ts`：项目 / 会话 QuickPick、工作区一致性校验（决策 6）、单任务约束、`POST /api/agent/chat/stream`、状态栏状态机。
- 新增 `src/vscode/panel.ts` + Webview 资源：流式渲染（FR-3）、终态展示、「未验证」标记；`workspaceState` 持久化 `{projectId, sessionId, runId, lastSeq}`，重载后按 T4 恢复。
- 完成判据：人工路径「发起 → 看到 content/tool → done」可用；重载窗口后能恢复同一 run 的后续事件；项目工作区与打开文件夹不一致时出现三选一提示。

## T6 变更 TreeView 与原生 diff

- 新增 `src/vscode/changesView.ts`：按 run 分组的文件列表（路径 / action / 增删 / 状态），`file_change` 事件与 `done` 后自动刷新。
- 新增 `src/shared/diff.ts`：按 T2 结论实现 before 重建（或 patch 降级预览）；`lunargold-before:` / `lunargold-patch:` 两个虚拟文档 provider，点击文件调 `vscode.diff`。
- 新增 / 删除文件、二进制 / 过大文件、文件被再次修改的降级路径明确。
- 完成判据：`diff.ts` 有单测（含截断与不匹配降级）；人工路径「点击变更文件 → 看到原生 diff」可用；降级时不报错、不伪造对比。

## T7 审批、撤回与停止

- 面板审批卡片（FR-6）：批准 / 拒绝 / 本会话记住 / 全部批准；失败透出 message 并刷新待审批。
- 面板与状态栏的「等待审批」常驻提示；重载后 `GET /api/agent/sessions/{sessionId}/approvals` 恢复卡片。
- `revertRun` / `revertSession`：modal 确认（文件数 + 目标），查询参数调用，成功后刷新 TreeView 与 diff，冲突透出 `RevertResult`。
- `stop`：确认后调用，面板标记已停止。
- 完成判据：人工路径「危险工具 → 卡片 → 批准后任务继续」「拒绝后任务按后端语义结束」「撤回后磁盘文件还原」「停止后不再产生新事件」逐条通过。

## T8 打包、人工验收与文档收口

- 新增 `ide/vscode/README.md`：安装（vsix 手动安装 / 卸载）、连接引导、8 条命令、设置项、已知限制（单任务、远程不支持、首版不接 Verify、无自动批准）。
- `npm run package` 产出 vsix，本机 `code --install-extension` 安装后复测核心路径。
- 按 `checklist.md` 逐条人工验收并记录证据（含后端环境启动方式与版本）；更新 `docs/phases/phase6/plan.md` 的 P6-03 状态。
- 完成判据：checklist 全勾或逐条交代偏差；零回归成立（后端 `mvn -o test` 331/0/0/15、CLI 71 条单测、`frontend/` 无 diff）。

## 依赖顺序

T1 → T2 → T3 / T4（可并行）→ T5 → T6 / T7（可并行）→ T8。

## 不做

- 不修改 `src/main/java`、`PublicApiCatalog`、`public-api.json`；不新增公开端点。
- 不改 `frontend/`、`cli/` 任何文件；不引入根级构建或 workspace。
- 不做 LSP / 补全 / 内联聊天 / 会话管理后台 / 附件 / 自动批准 / 多任务并发 / 远程开发适配。
- 不做 `@vscode/test-electron` 自动化 UI 测试（首版人工验收；后续需要回归保护时另立批次）。
- 不做签名、市场发布与自动更新。
