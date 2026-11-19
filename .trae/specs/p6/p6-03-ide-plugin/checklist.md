# P6-03 IDE 薄插件（先 VS Code）- 验收清单

> 状态：**已实施，本机 vsix 核心闭环通过，保留下列明确偏差**（2026-11-17）。`[x]` 表示已有单测或真机证据；`[ ]` 表示未完整验收并说明原因。
>
> 验收环境：Windows，VS Code 1.143.1，Node.js 24.21.0，扩展 0.1.0；后端 1.0.0-SNAPSHOT、API 契约版本 1，以 desktop profile 运行于 `http://127.0.0.1:8080`，沿用 `build/dev-desktop-data`，验收结束时 `/actuator/health` 为 `UP`。
>
> 真机对象：通过 `code --install-extension` 安装到隔离扩展目录的 `lunargold-vscode-0.1.0.vsix`，不是仅运行 Extension Development Host。截图保存在 `build/vscode-acceptance/`。验收中一次后端无响应后已沿用原数据重启恢复；未取得 runId 的卡住请求未计入通过案例。

## 工程与依赖

- [x] `ide/vscode/package.json` 运行时零依赖；`engines.vscode >=1.95`、`extensionKind: ["ui"]`；`npm run build` / `npm run package` 产出单文件 `dist/extension.js`。
- [x] `src/shared/` 未使用 `enum` / 参数属性 / `namespace`；`npm run check` 通过，Node 24 可直接执行纯逻辑测试。
- [x] `npm test`：43 tests / 43 pass / 0 fail（仅有 Node 的 typeless package 性能提示）。
- [x] 本批次未新增根级 `package.json` / workspace，未修改 `frontend/` 与 `cli/`；工作区在这批实施前已有其他 Phase 5/6 改动，不将其误记为本扩展引入。

## 命令与契约

- [x] 8 条命令全部注册且可在命令面板检索：`connect` / `newTask` / `openPanel` / `stop` / `refreshChanges` / `revertRun` / `revertSession` / `disconnect`。证据：`commands.png`。
- [x] 「端点 → 功能」映射与 `src/test/resources/contract/public-api.json` 一致，无清单外调用；唯一 local 例外 `POST /api/clients`。证据：`endpoints.test.ts`。
- [x] `revert-run` / `revert-session` 用查询参数调用（无 body）；审批 body 为 `{decision, remember}`。证据：`http.test.ts`、真实审批与撤回请求。
- [x] 本批次未修改 `src/main/java`、`PublicApiCatalog`、`public-api.json`，未新增公开端点。

## 连接与令牌

- [x] `connect` 在后端未启动时提示先运行 `scripts/dev-desktop.ps1`，不弹堆栈。证据：`http.test.ts` 网络错误分支。
- [x] 无客户端令牌时可从本地用户令牌换发 `vscode-<hostname>`，并存入 SecretStorage；也支持手动粘贴 `lgc_` 令牌。已安装 vsix 连接通过，证据：`connected.png`。
- [x] 令牌不出现在 settings.json、日志与任何磁盘配置文件中；连接逻辑只经 SecretStorage 读取，隔离验收配置不含客户端令牌。
- [x] health 校验 `status == UP` 且 `apiContractVersion == 1`，不匹配时提示升级。证据：`connection.test.ts`。
- [x] 401 / 403（含「缺少 scope」）给出「重新连接」引导；`disconnect` 撤销当前客户端并清理 SecretStorage 与恢复状态，失效认证重新进入连接引导。证据：`connection.test.ts`、`http.test.ts` 及断开流程核对。

## 流式与续传

- [x] 发起任务后面板依次出现 `session_id` / `run_id` / `turn_id`、正文增量、工具摘要、`done`；状态栏状态同步。证据：`task-running.png`、`latest-reloaded.png` 及 Run12–16 真机任务。
- [x] SSE 解析覆盖与 CLI 同源的 6 个 fixture，心跳注释不产生事件。证据：`sse.test.ts`。
- [x] 断流后按 `sinceSeq` 续传，面板显示断线 / 续传状态，正文与工具事件不重复。证据：`resume.test.ts`、`reducer.test.ts` 的断流回放与指纹去重测试。
- [x] 扩展重载后能按 `workspaceState` 恢复 session / run 身份、已落库事件与待审批卡片。证据：`latest-reloaded.png`、`approval-restored.png`，以及稀疏回放保留审批元数据的回归测试。
- [ ] **活跃 Run 续跑偏差**：当前后端会把活跃 SSE 断流视为取消。窗口重载或扩展宿主重启后，扩展可恢复身份、历史事件与终态，但不保证活跃 Run 继续执行；待审批可能被 `SESSION_STOP` 拒绝、Run 可能失败。本批次不改后端，不把恢复卡片等同于任务仍在执行。
- [x] 同时只能有一个活跃 Run，重复发起被提示；由 `TaskController` 单任务状态约束。

## 变更与 diff

- [x] 变更 TreeView 按 run 分组，展示路径 / action / 增删行数 / 状态；`file_change` 与 `done` 后自动刷新。证据：`changes-visible.png`、`changes-expanded.png`。
- [x] 点击文件用 VS Code 原生 diff 查看；新增 / 删除文件及撤回后的状态表现正确。证据：Run12 的 `native-diff.png`、`revert-run-done.png`，以及 `diff.test.ts`。
- [x] `diffText` 截断或文件被再次修改时走降级（patch 预览 + 当前文件），不伪造逐行对比、不覆盖用户内容。证据：`diff.test.ts` 的截断、二进制、过大与不匹配分支。
- [x] 项目工作区与 VS Code 打开文件夹不一致时，发起前出现「打开该文件夹 / 仍要继续 / 取消」；继续后面板有错位提示。证据：Run21 / session14 的 `workspace-mismatch-confirm-2.png`、`workspace-mismatch-panel.png`。
- [x] 面板显示「未验证」标记（首版不接 Verify）。真机终态截图可见。

## 审批、撤回与停止

- [x] 审批卡片含工具名、风险、preview（路径 / 命令 / 增删行 / 说明）、过期时间。证据：Run13–16 的待审批截图与面板渲染核对。
- [x] 批准生效：Run14 / session11 点击真实「批准」，审批 `ap-1c3596c15f93`，`vscode-ui-approved.txt` 落盘。证据：`approve-ui-pending.png`、`approve-ui-done.png`。
- [x] 拒绝生效：Run13 / session11 点击真实「拒绝」，审批 `ap-3256396bb07a`，工具收到用户拒绝且 `vscode-rejected.txt` 未创建。证据：`reject-pending.png`、`reject-done.png`。
- [x] 本会话记住生效：Run15 点击「批准并记住」生成 `vscode-remembered.txt`；Run16 新建 `vscode-auto-approved.txt` 时无审批卡并直接成功。证据：`remember-pending.png`、`remember-done.png`、`remember-auto-approved.png`。
- [ ] **全部批准真机缺口**：扩展已调用 `POST /api/agent/approvals/batch` 并处理 `resolvedIds`，后端批量审批已有测试；但当前后端在单个活跃 Run 中串行处理写工具，扩展又限制单活跃 Run，未能自然形成双待审批卡，因此未宣称双卡批量批准成功路径真机通过。
- [x] 审批提交失败原样透出后端 message，并重新获取待审批、恢复卡片按钮。证据：`http.test.ts` 业务错误分支与 `TaskController.handleAction` 失败路径核对；未把该实现核对算作双卡真机证据。
- [x] 撤回前 modal 确认文件数与目标；成功后磁盘文件还原、TreeView 与 diff 刷新。Run12 按 Run 撤回通过；Run20 按会话撤回确认并删除 3 个目标文件，三项均为 `REVERTED`。证据：`revert-run-confirm.png`、`revert-run-done.png`、`revert-session-confirm.png`、`revert-session-done.png`；冲突原因按后端 `RevertResult` 原样展示。
- [x] 停止后不再产生新事件，面板保留已渲染内容并标记「已停止」。Run18 / session13 待审批时停止，确认后审批卡消失，继续等待无新事件且 `vscode-stopped.txt` 未创建。证据：`stop-pending.png`、`stop-confirm.png`、`stop-done.png`。

## 打包与端到端

- [x] `npm run package` 产出 `lunargold-vscode-0.1.0.vsix`；本机隔离安装后核心路径「连接 → 发起 → 审批 → diff → 撤回」复测通过，停止、拒绝、记住与工作区错位也已补测。
- [ ] **远程环境未真机验收**：实现已明确拒绝 SSH / WSL / Dev Container 窗口，而非静默失败；当前没有真实远程环境，首版仍声明不支持，未把实现核对写成远程真机通过。
- [x] 手动验收记录包含后端环境、VS Code / Node / 扩展版本、Run 编号与截图证据；证据目录为 `build/vscode-acceptance/`。
- [x] 零回归：扩展 `npm run check` 通过、43/43 单测通过；CLI 71/71 单测通过；后端最终 `mvn -o test` 为 **331 tests / 0 failures / 0 errors / 15 skipped**。首轮后端曾为 331/1/0/15，唯一失败是 `JavacDiagnosticsProviderTest.validJavaHasNoErrors` 的 `javac` 15 秒超时；关闭本次验收进程后，该测试类定向复核 4/4 通过，完整套件复跑通过，未修改后端源码。本批次未改 `frontend/`，其工作区既有 diff 不属于本批次。

## 文档

- [x] `ide/vscode/README.md` 含安装 / 卸载、连接引导、8 条命令、设置项、已知限制（单任务、远程不支持、不接 Verify、无自动批准、活跃 SSE 断流取消）。
- [x] spec 决策 7 已写入 `diffText` 核实结论；未新增端点，无需另开契约批次。
- [x] `docs/phases/phase6/plan.md` 的 P6-03 状态与实测结果一致，并链接回本清单的保留偏差。
