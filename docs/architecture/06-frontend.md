# 06 前端工作台（部分完成）

> 更新日期：2026-11-13
> 状态：核心任务流、流式渲染、审批、断线续传与完成状态展示已完成；视觉与体验剩余项未完成
> 关联：[总架构](README.md)、[API 与事件流](05-api-sse.md)

---

## 1. 技术栈与目录

Vue 3 + TypeScript + Vite + Pinia + Vue Router，Element Plus 提供基础组件，Markdown 渲染独立组件。

```text
frontend/src/
  api/         13 个接口模块（http、sse、agent、workspace、knowledge 等）
  components/  消息、审批、工作台、文件树、编辑器、Markdown、状态组件
  layouts/     AppLayout 三栏布局
  router/      路由与登录/能力守卫
  stores/      auth、chat、project、meta、theme
  styles/      tokens.css（唯一 token 入口）、style.css
  types/       请求/响应与聊天类型
  utils/       格式化等
  views/       7 个页面
```

页面：`ChatView`、`KnowledgeView`、`SettingsView`、`KeysView`、`ProfileView`、`LoginView`、`DownloadView`。

---

## 2. 状态管理

| Store | 职责 |
|---|---|
| `auth` | 登录态、令牌、用户信息 |
| `chat` | 会话列表、消息、工具轨迹、计划、审批、运行恢复、SSE 生命周期 |
| `project` | 项目、工作区路径、项目指令 |
| `meta` | 部署模式与能力集合，驱动入口显隐 |
| `theme` | 亮暗主题、字号档位、首屏防闪白 |

`chat` 是最复杂的 store：它同时消费实时 SSE、历史消息 trace 与 Run 事件，三条来源最终归一到同一份 UI 状态。

---

## 3. SSE 消费

`api/sse.ts` 手写解析 `text/event-stream`：

- 支持多行 `data:`、`event:` 类型分发；
- `done` 结束读取，`error` 统一错误处理；
- 事件映射：`content`、`thinking`、`tool_call`、`tool_result`、`approval_required`、`plan`、`sources`、`usage`、`completion`、`no_progress`、`done`、`error`。

`no_progress` 不单独弹出提示，最终由 `completion` 统一展示结果，避免重复打扰。

**长会话节流（P5-11-3）**：`content` / `thinking` 事件不直接写响应式状态，而是交给 `utils/frameThrottle.ts` 按帧合并（rAF + 50ms 最小间隔，最多 20 次/秒），同一帧只保留最新全量文本；`done`、`error`、用户停止与 `finally` 强制 flush，保证最终正文与后端全量文本逐字一致。自动跟底同样合帧，并且只在用户停留底部时跟随。

---

## 4. 三条恢复路径

```text
路径一：实时 SSE（正常流式）
路径二：历史消息 trace（刷新后读消息自带的工具/计划/completion）
路径三：Run 事件重放（发现 running run，拉 GET /runs/{id}/events 补齐）
```

三者最终都写入相同的消息结构，因此切换会话、刷新、断网重连后展示一致。

---

## 5. 关键组件

| 组件 | 职责 |
|---|---|
| `ChatMessage.vue` | 消息正文、工具轨迹、完成状态徽标（已完成/未验证/未完成及原因） |
| `MarkdownContent.vue` | Markdown 渲染、代码块、diff；流式时只重算变化段落，结束后整篇解析 |
| `composables/useVirtualMessages.ts` | 消息数 > 200 时按可视窗口渲染，实测高度 + 上下占位，供定位与缩略导航复用偏移 |
| `utils/focusTrap.ts` | 快捷键面板的焦点查询与 Tab 循环，避免焦点逃逸到背景页面 |
| `ApprovalCard.vue` | 审批卡片、A/D 快捷键、倒计时、详情折叠 |
| `PendingChangesCard.vue` | 待处理文件变更与撤回入口 |
| `WorkbenchDock.vue` | 右侧工作台：文件树、编辑器、变更审查 |
| `WorkspaceFileTree.vue` | 工作区文件树 |
| `FileEditorPane.vue` | 文件编辑与 diff 查看 |
| `RulesPanel.vue` | 自定义规则管理 |
| `components/state/` | 骨架屏与错误态等异步三态组件 |

完成状态展示规则：

| 状态 | 展示 |
|---|---|
| `COMPLETED` | 正常“已完成” |
| `UNVERIFIED` | “未验证”，提示尚未运行诊断或验证 |
| `INCOMPLETE` | “未完成”，展示后端原因（计划未完成、审批拒绝、轮次耗尽、无进展、取消等） |
| 旧消息无 completion 字段 | 保持原有展示，不误判 |

---

## 6. 样式体系

- `styles/tokens.css` 是唯一 token 层：颜色语义、焦点环、层级、密度、状态色。
- 亮暗两套 token 一一对应，组件内不写死 hex（白名单例外仅限主题预览）。
- `theme` store 负责切换、持久化、首屏防闪白与字号注入。
- 消息密度只切 token：`--density-row/--density-gap/--density-pad-y` 默认 32px / 6px / 6px，`[data-density="compact"]` 覆盖为 24px / 4px / 4px；设置页「外观」切换，`theme.density` 持久化并在挂载前应用。
- 支持 `:focus-visible` 焦点环。
- 减少动效由 `theme.reduceMotion` 统一控制：默认跟随系统并监听 `matchMedia`，设置页可手动开启并持久化，挂载前写入 `html[data-motion="reduce"]`；平滑滚动同步降级为 `auto`。
- 图标按钮具备 `aria-label`，审批队列有区域语义；流式、连接与审批状态经 `aria-live="polite"` 播报。

---

## 7. 现状边界

| 项 | 状态 |
|---|---|
| 断线续传 | 已完成（runId 重放 + 正文检查点） |
| 审批键盘化 | 已完成：A/D 决策、J/K 切换、低风险批量与审批历史；会话可 Tab + Enter 切换 |
| 键盘与无障碍 | 已完成：`Ctrl + /` 面板焦点陷阱与恢复、图标按钮可读名称、审批播报、手动减少动效；axe 扫描 `/chat`、`/settings` critical 为 0 |
| 长任务进度 | 已完成：五阶段时间线、阶段耗时、预算可视化与失败分级 |
| 三态 | 已完成：会话切换骨架、首屏项目概览、工作台各 tab 空态、错误态（错误码/原因/重试/导出诊断）；工具运行中显示名称与实时耗时 |
| 消息排版 | 已完成：代码块头与复制、表格斑马纹与密度行高、引用位置可见、密度档位（紧凑 24px / 舒适 32px）、操作条悬停或聚焦出现 |
| 响应式 | 已完成：≥1024 三栏、768-1023 图标栏 + 工作台浮层、<768 抽屉；960×640 与三档宽度 E2E 归档，无横向滚动 |
| 前端自动化测试 | 已完成基线：Vitest 12 文件 88 项，Playwright 23 条（含无障碍与响应式用例，`@perf` 按需运行） |
| 体验指标 | 已完成：本地异常/Web Vitals/Run 恢复采样 + 设置页诊断面板 |
| 长会话渲染 | 已完成：≤20 次/秒合帧、Markdown 只重算变化段落、>200 条窗口化渲染；500 条实测首屏 108ms、DOM 216 节点、60 秒流式 0 长任务 |

---

## 8. 面试要点

1. **流式渲染怎么做的？** 手写 SSE 解析，按事件类型增量更新消息，避免整页重渲染。
2. **刷新为什么不会丢？** 消息 trace + Run 事件重放 + 运行中 run 查询三条路径归一。
3. **完成状态怎么展示？** 不把 `Run=SUCCEEDED` 当成功；按 `completion` 显示未验证/未完成。
4. **如何控制主题一致性？** token 单一入口 + 亮暗逐项对应 + 组件内禁止硬编码颜色。
