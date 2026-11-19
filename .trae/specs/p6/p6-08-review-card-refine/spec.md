# P6-08 待审查改动卡片收敛 - 产品需求文档

> 状态：**已实施**（2026-11-19）。
> 上游：P6-07 交互收敛之后，用户对「待审查改动」卡片（chat 输入区上方）提出的四点调整。

## Overview

- **Summary**：把「待审查改动」卡片收敛成一张只回答两件事的卡：**有哪些文件等你处理、一键保留还是撤销**。折叠态就能批量操作；展开态只列待审查文件（名字、路径、`+n -m`），点文件直接进右侧工作台的文件标签；删掉卡片内再套一层的 diff 抽屉。
- **Purpose**：当前展开后同时出现任务交付摘要、验证详情、失败命令、Run 编号与状态、按 Run 筛选、撤回此轮、逐文件四个图标按钮和 inline diff。这些是研发视角的内部信息，普通用户读不懂，且 inline diff 与右侧工作台的文件标签重复，形成「侧边栏里再拉一层抽屉」。
- **Target Users**：Web 工作台里审查 Agent 文件改动的日常用户。

## Goals

1. **折叠态可直接批量决策**：卡片收起时头部的「全部撤销」「全部保留」始终可见。
2. **展开态只列待审查文件**：每行给出文件名、路径、`+n -m`；不再出现摘要计数、Run 编号/状态、筛选 chip、失败命令、逐文件图标按钮。
3. **删掉多余的 diff 抽屉**：点击任意文件直接在工作台的文件标签里打开，不再在卡片内展开 inline diff。
4. **文件详情头部显示路径**：工作台文件标签栏下方不再只显示文件名，改为显示相对路径（目录弱化、文件名保留辨识度）。

## Non-Goals

- 不改后端接口与数据库；不新增 `/accept-all` 的会话级变体。
- 不改工具审批卡 `ApprovalCard.vue`（P6-07 已收敛）。
- 不改「变更 / 资料 / 计划 / 追踪 / 监控」这些工作台标签页本身。
- 不做主题、布局、响应式重做。

## Design Decisions

1. **批量操作上移到头部，且只作用于卡片里列出的文件**
   - 「全部保留」不再调用项目级 `POST /api/file-changes/accept-all`（会把其他会话的 PENDING 也一并接受），改为逐个保留当前卡片中的变更，成功后只弹一次汇总提示。
   - 「全部撤销」沿用会话级 `POST /api/file-changes/revert-session`（`chat.revertTask()`），与「本任务」语义一致。
   - 两者都放在折叠头右侧，`@click.stop`，不触发展开/收起。

2. **卡片只在有 PENDING 文件时出现**
   - `visible` 从「任务有文件」收紧为「仍有待审查文件」；全部保留或撤销后卡片自动消失，不再留一个空壳。任务级审计信息不在本卡片承担。

3. **展开态 = 待审查文件列表**
   - 每行：文件名（主色/加粗）+ 目录路径（弱化等宽）+ `+n -m`（绿/红），`title` 给完整路径。
   - 整行可点：`chat.openFile(filePath)`，直接在工作台文件标签打开并高亮改动行。
   - 删除逐文件的「查看 diff / 在编辑器打开 / 保留 / 撤销」四个图标按钮；单文件决策由「点开文件 → 看完 → 用头部批量按钮或不处理」覆盖。

4. **保留两项用户能理解的必要信息**
   - 头部状态 chip（`changeSet.statusLabel`，如「未验证 / 验证失败」）：它回答「这轮改动跑过测试没有」，是审查时唯一需要看的非文件信息，保留。
   - 验证失败且后端给出 `continuePrompt` 时，头部显示一个「继续修复」按钮：这是可操作的动作，不是信息噪声。
   - 其余（审批 `1/1` 计数、`#101`、`SUCCEEDED`、失败命令输出、已接受数量）全部从卡片移除；排障时仍可在「追踪 / 监控」里看到。

5. **删除 inline diff 抽屉**
   - 移除 `diffOpenId`、`parseDiff`、`toggleDiff`、`pfc-diff` 模板与样式。
   - 用户想逐行看改动时，文件已经在工作台以 CodeMirror + 红绿行装饰展示，不再需要卡片内副本。

6. **文件详情头部显示完整相对路径**
   - `FileEditorPane` 顶部把 `path` 拆成「目录 + 文件名」渲染：目录用次级色、文件名正常色，`title` 为完整路径；标签栏已经显示文件名，下方不再重复纯文件名。

## Background & Context

| 项 | 事实 | 位置 |
|---|---|---|
| 卡片组件 | 折叠头 + 展开列表 + 底部批量按钮 + inline diff | `frontend/src/components/PendingChangesCard.vue` |
| 批量保留 | 项目级接口，接受该项目所有 PENDING | `POST /api/file-changes/accept-all`、`chat.acceptAll()` |
| 批量撤销 | 会话级接口，回滚本任务 PENDING | `POST /api/file-changes/revert-session`、`chat.revertTask()` |
| 打开文件 | 写入 `openFiles`、`activeFilePath`，切到 `file` 标签 | `chat.openFile()`（`frontend/src/stores/chat.ts`） |
| 文件详情 | 头部当前直接渲染 `path`，无目录/文件名分区 | `frontend/src/components/FileEditorPane.vue` |
| 既有 E2E | 依赖 `.pfc-summary`、展开后的「全部保留」「整体撤回」 | `frontend/e2e/changeset-delivery.spec.ts` |

## Acceptance

- 折叠态头部同时出现「全部撤销」「全部保留」；点击任一按钮不展开列表。
- 展开态只有文件行：文件名、目录、`+n -m`；不存在 `.pfc-summary`、`.pfc-verify-detail`、`.pfc-run-filter`、`.pfc-run-head`、`.pfc-diff`、逐文件操作按钮。
- 点击文件行后右侧工作台打开对应文件标签；卡片内不再出现 diff 抽屉。
- 文件详情头部显示相对路径（目录弱化、文件名正常色），不再只有文件名。
- 全部保留只接受卡片列出的文件；全部撤销回滚本任务未审查改动；成功后卡片自动消失。
- 验证失败且有 `continuePrompt` 时出现「继续修复」；无失败上下文时不占位。
- `npx vue-tsc --noEmit`、`npm test -- --run` 通过；`e2e/changeset-delivery.spec.ts` 更新后通过。
