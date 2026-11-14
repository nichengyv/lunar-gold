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
| `MarkdownContent.vue` | Markdown 渲染、代码块、diff |
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
- 支持 `:focus-visible` 焦点环与 `prefers-reduced-motion`。

---

## 7. 现状边界

| 项 | 状态 |
|---|---|
| 断线续传 | 已完成（runId 重放 + 正文检查点） |
| 审批键盘化 | 部分完成：A/D 已有；J/K 切换、批量、审批历史未做 |
| 长任务进度 | 部分完成：计划条已有；阶段耗时、预算可视化、失败分级未做 |
| 三态 | 部分完成：会话骨架与错误态已有；工具运行态与统一空态未完成 |
| 消息排版 | 未完成：代码块头与复制、密度档位 |
| 响应式 | 部分完成：1024 图标栏、768 抽屉已有；960×640 实测未归档 |
| 前端自动化测试 | 未开始：无 Vitest / Playwright |
| 体验指标 | 未开始：无全局错误捕获与 Web Vitals |

---

## 8. 面试要点

1. **流式渲染怎么做的？** 手写 SSE 解析，按事件类型增量更新消息，避免整页重渲染。
2. **刷新为什么不会丢？** 消息 trace + Run 事件重放 + 运行中 run 查询三条路径归一。
3. **完成状态怎么展示？** 不把 `Run=SUCCEEDED` 当成功；按 `completion` 显示未验证/未完成。
4. **如何控制主题一致性？** token 单一入口 + 亮暗逐项对应 + 组件内禁止硬编码颜色。
