# P6-08 待审查改动卡片收敛 - 任务拆分

> 状态：已实施（2026-11-19）。

## [x] T1 折叠态批量操作

- `PendingChangesCard.vue` 折叠头右侧新增「全部撤销」「全部保留」两个按钮，`@click.stop`，不触发展开。
- `visible` 改为 `count > 0`：没有待审查文件时卡片不渲染。
- 「全部撤销」沿用 `chat.revertTask()`；确认框文案改为用户可读：`撤销本任务中所有待审查的文件改动？文件会恢复为执行前的状态；执行后被外部修改过的文件不会被覆盖。`
- 「全部保留」新走逐文件保留（见 T2），不再调用项目级 `acceptAll`。

## [x] T2 批量保留只作用于卡片内文件

- `stores/chat.ts` 新增 `acceptChanges(ids: number[])`：对给定 id 调 `fileChangesApi.acceptChange`，用 `Promise.allSettled` 并发执行；统计成功数，只弹一次汇总提示；随后 `fetchChangeSet()`。
- 卡片传入当前 `pendingChanges.map(c => c.id)`。
- 保留既有的 `acceptAll(projectId)` 与 `revertAll` API 方法不动（其他入口仍可能使用）。

## [x] T3 展开态收敛为文件列表

- 删除模板：`pfc-summary`、`pfc-verify-detail`、`pfc-run-filter`、`pfc-run-head`、`pfc-empty`、`pfc-footer`。
- 删除脚本：`verification`、`statusClass`/`verifyClass`、`failedCommands`、`groups`、`latestTurnId`、`diffOpenId`、`parseDiff`、`toggleDiff`、`onRevertOne`、`onAcceptOne`、`onRevertRun`、`onRevertTurn`、`onAcceptAll` 中的项目级逻辑、`onContinueFix` 保留。
- 展开列表每行改为：
  - 文件名 `baseName(c.filePath)`（主色、加粗）；
  - 目录 `dirName(c.filePath)`（次级色、等宽、省略），`title` 为完整路径；
  - `+additions -deletions`（成功/危险色，tabular-nums）；
  - 整行 `@click="onOpen(c.filePath)"` → `chat.openFile(path)`。
- 删除逐文件 diff 图标、外链图标、保留/撤销图标及其事件。
- 头部保留 `pfc-status`（`changeSet.statusLabel`）；`changeSet?.continuePrompt` 时在头部渲染「继续修复」文字按钮（`chat.continueFix()`）。
- 明确不展示：审批计数、Run 编号与状态、失败命令、已接受数量。

## [x] T4 删除 inline diff 抽屉

- 移除 `pfc-diff` 模板、`.pfc-diff*` 样式与 `diffOpenId` 相关状态。
- 移除 `.pfc-item-actions`、`.pfc-action-btn` 等不再用的样式。
- 保留 `.pfc-add` / `.pfc-del` 供新行使用，补齐 `.pfc-name` / `.pfc-path` / `.pfc-head-actions` 样式。

## [x] T5 文件详情头部显示路径

- `FileEditorPane.vue` 头部 `.fe-file-name` 改为两段：目录（`--text-tertiary`）+ 文件名（`--text`），`title` 为完整路径；无目录时只渲染文件名。
- 约束：不使用 `direction: rtl` 之类会打乱路径顺序的技巧；中间省略交给 `text-overflow: ellipsis` 作用于目录段。

## [x] T6 测试与验证

- 更新 `frontend/e2e/changeset-delivery.spec.ts`：
  - 折叠态直接断言并点击「全部保留」「全部撤销」；
  - 删除对 `.pfc-summary` 的断言，改为断言 `.pfc-item` 含文件名与 `+2 / -1`；
  - 「整体撤回」改名为「全部撤销」；
  - 断言点击文件行后右侧工作台 `.wb-tab-file.active` 出现且文件名匹配。
- 运行：
  - `cd frontend && npx vue-tsc --noEmit`
  - `cd frontend && npm test -- --run`
  - `$env:E2E_BASE_URL="http://localhost:5173"; $env:E2E_CHANNEL="msedge"; npx playwright test e2e/changeset-delivery.spec.ts`
- 浏览器复核（browser_use）：折叠态截图确认两个批量按钮；展开后确认只有文件行；点文件确认工作台文件标签打开且头部显示路径。
