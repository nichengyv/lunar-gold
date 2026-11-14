# P5-11-1 前端测试基线（Vitest + Playwright E2E）- Product Requirements Document

## Overview

- **Summary**：为前端建立自动化测试基线。Vitest 覆盖不依赖浏览器的纯逻辑（SSE 解析、store 状态迁移、审批卡、Markdown 代码块）；Playwright 覆盖关键 UI 路径（发送与流式、审批允许/拒绝、停止生成、断线续传）以及 P5-14 的「修改 → 审批 → verify → 审查 → 撤回」交付路径。CI 新增前端单测与 E2E 独立 job，不阻塞后端打包。
- **Purpose**：当前 `frontend/package.json` 只有 dev/build/preview，全仓无任何 `*.spec.*`，改一个按钮或 SSE 解析只能手点。目标是「改错会红」——关键逻辑与路径有自动化保护，而不是多写测试文件。
- **Target Users**：开发者（本地跑单测/E2E 防回归）、CI（PR 门禁）。

## Goals

1. Vitest + `@vue/test-utils` + jsdom 落地，能跑、能在 CI 跑通。
2. 单测覆盖 `api/sse.ts` 解析（多行 data、CRLF、done/error 终止、run_id/turn_id）、`stores/chat.ts` 状态迁移（runId 重放、检查点恢复、停止）、`ApprovalCard` 决策与倒计时、`MarkdownContent` 代码块与复制。
3. Playwright 落地，配置可复现，不强制下载浏览器（优先系统 Chromium / Playwright 自带 channel）。
4. Playwright E2E 覆盖 5 条关键路径：发送与流式、审批允许/拒绝、停止生成、断线续传、P5-14 完整交付路径。
5. E2E 后端以「嵌入式 PostgreSQL + 确定性脚本化 AgentClient + 种子数据」启动，无需真实模型 Key、无需外部数据库，可在 CI 复现。
6. CI 新增两个 job：前端单测（lint+vitest）与 E2E（启动后端 → Playwright → 失败上传 trace），互不阻塞后端构建。

## Non-Goals

- 不做前端组件级视觉回归（无 Percy/Chromatic）。
- 不接入真实模型 Key 跑 E2E；所有模型交互由 `ScriptedAgentClient` 回放确定性序列。
- 不做 P5-11-2 体验指标、P5-11-3 长会话性能与虚拟滚动。
- 不重写现有组件以「为了可测」；如需小改造（提取纯函数、暴露 props），保持行为不变。
- 不替换 `vue-tsc` 类型检查；Vitest 只做运行时断言，类型仍由 `vue-tsc` 兜底。
- 不承诺 E2E 在离线环境跑通（Playwright 浏览器二进制需提前安装或系统自带）。

## Background & Context

- `frontend/src/api/sse.ts`：手动解析 POST SSE（`fetch` + `ReadableStream`），关键逻辑是「空行分隔事件」「done/error 立即终止读取并 cancel」。已有断线续传（`runId` → `GET /api/agent/runs/{runId}/events?sinceSeq=`）。
- `frontend/src/stores/chat.ts`：Pinia store，含 `sendMessage`、`stopGenerating`、`recoverRun`、`activeRunId`、生成状态机。runId 重放与检查点恢复是 P5-10 已实现能力。
- `frontend/src/components/ApprovalCard.vue`：props `approval`（含 risk/tool/preview/expiresAt/resolved），emit 调用 `chat.respondApproval`；倒计时到 0 自动 dismiss；快捷键 A/D。
- `frontend/src/components/MarkdownContent.vue`：`marked` + `hljs` 渲染代码块，`.copy-code-btn` 复制。
- `frontend/vite.config.ts`：dev server 端口 5173，`/api` 代理到 `http://localhost:8080`。
- 后端测试已有 `ScriptedAgentClient`（`src/test/java/.../eval/ScriptedAgentClient.java`）回放固定工具调用序列，以及 zonky `EmbeddedPostgres`（`EmbeddedPgSupport`）启动含 pgvector 的内嵌 PG。这两块可复用于 E2E 后端。
- 后端 `application-dev.yml` 用外部 PG(5433)；E2E 需独立 profile，避免依赖本机 PG。
- `.github/workflows/ci.yml` 当前只有 `backend`（mvn verify + eval gate）和 `frontend`（npm ci + build）两个 job。

## Functional Requirements

### FR-1 Vitest 基础设施

- `frontend/package.json` 新增 scripts：`test`（vitest run）、`test:watch`（vitest）、`test:coverage`（vitest --coverage）。
- 新增 `frontend/vitest.config.ts`：基于 `vite.config.ts`，environment `jsdom`，`@vue/test-utils` 可用，测试文件匹配 `**/*.{test,spec}.{ts,tsx}`，排除 `node_modules` 和 `dist`。
- 新增 devDependencies：`vitest`、`@vue/test-utils`、`jsdom`（版本与现有 Vite 6 / Vue 3.5 兼容）。
- 不修改 `vite.config.ts` 的 dev/build 行为；Vitest 配置独立。
- `npm run build` 仍为 `vue-tsc --noEmit && vite build`，不把测试塞进构建链。

### FR-2 SSE 解析单测

文件：`frontend/src/api/sse.ts`，测试：`frontend/src/api/sse.test.ts`

- 多行 `data:` 合并为 `\n` 连接的单条 data。
- CRLF 与 LF 换行均能正确切分事件。
- `event: done` → 触发 `onDone` 并返回终止信号，`reader.cancel()` 被调用（用 mock reader 断言）。
- `event: error` → 触发 `onError` 并终止。
- `event: run_id` / `turn_id` / `content` / `approval_required` 正确解析 JSON payload。
- 流中途被掐断（reader 返回 done 但无 done 事件）时，缓冲区残余事件仍派发一次。
- `id:`、`retry:`、注释行忽略。

### FR-3 chat store 状态单测

文件：`frontend/src/stores/chat.ts`，测试：`frontend/src/stores/chat.test.ts`

- 使用 `setActivePinia(createPinia())` + mock `api` 模块。
- `sendMessage` 成功路径：`isGenerating` true → 收到 `run_id` → `activeRunId` 赋值 → `done` 后 `isGenerating` false。
- 停止生成：`stopGenerating` 触发 abort，后续 SSE 事件不再更新正文。
- runId 重放：模拟 `recoverRun` 拉取历史事件并按顺序回放正文，不重复渲染已接收内容。
- 状态迁移：`RUNNING → WAITING_APPROVAL → RUNNING → SUCCEEDED` 对应的 UI 状态正确。

### FR-4 ApprovalCard 单测

文件：`frontend/src/components/ApprovalCard.vue`，测试：`frontend/src/components/ApprovalCard.test.ts`

- 挂载后渲染工具名、风险等级、预览内容。
- 点击「允许」调用 `chat.respondApproval(approvalId, 'APPROVE')`；点击「拒绝」调用 `REJECT`。
- 已 resolved 状态不渲染操作按钮，显示「已批准/已拒绝」。
- 倒计时：`expiresAt` 临近时 `remaining` 递减，到 0 调用 `chat.dismissApproval`。
- 快捷键 A/D（仅 `shortcutActive` 为真时）触发对应决策。

### FR-5 MarkdownContent 单测

文件：`frontend/src/components/MarkdownContent.vue`，测试：`frontend/src/components/MarkdownContent.test.ts`

- 渲染带语言的代码块，`.code-lang` 显示语言名，`.copy-code-btn` 存在。
- 无语言代码块使用自动高亮，label 为 `code`。
- 点击复制按钮调用 `copyToClipboard`，按钮文本切换（如已有反馈态）。
- 普通文本、列表、链接正常渲染（不做像素断言，只断言关键 DOM 存在）。

### FR-6 Playwright 基础设施

- 新增 `frontend/playwright.config.ts`：
  - `testDir: './e2e'`，输出 `test-results`，trace `retain-on-failure`。
  - `baseURL` 由环境变量 `E2E_BASE_URL` 覆盖，默认 `http://localhost:5173`。
  - `webServer` 可选：当 `E2E_BACKEND_AUTOSTART` 为真时启动 `vite preview`（生产构建产物）或 dev server；否则假设后端与前端已由外部启动。
  - 浏览器：优先 `channel: 'chromium'`（系统 Chromium），不可用时回退 Playwright 自带；提供 `PLAYWRIGHT_BROWSERS_PATH` 环境变量入口，不强制 `npx playwright install`。
- 新增 devDependency：`@playwright/test`。
- 不新增其他运行时依赖。

### FR-7 E2E API 仿真（Playwright route）

> 实施调整（2026-11-14）：原计划启动 e2e profile 的 Spring 后端（嵌入 PG + `ScriptedAgentClient`）。实际发现 `ScriptedAgentClient` 位于 test scope、桌面端登录依赖本机令牌文件，在 Playwright 外部进程中复现成本高且不稳定，因此改为浏览器层 `page.route('**/api/**')` 仿真。这样能确定性驱动真实前端组件、store、SSE 解析与交互，覆盖 UI 回归目标；后端契约由 `ChangeSetServiceTest` 等单测覆盖。

- 新增 `frontend/e2e/fixtures/api.ts`，提供 `mockApi(page, options)`：
  - 注入登录令牌（`addInitScript` 写 localStorage）。
  - 仿真 `/api/meta`（DESKTOP 模式 + 全部能力）、`/api/projects`、`/api/agent/sessions`、`/api/agent/runs/running`、`/api/agent/runs/{id}/events`、`/api/settings/defaults`。
  - 仿真 `/api/agent/chat/stream`：返回确定性 SSE 文本（session_id / run_id / turn_id / content / approval_required / done）。
  - 仿真 `/api/file-changes`、`/api/file-changes/changeset`、`accept-all`、`revert-session`、`revert-run`。
  - 提供 `changeSetFixture()`、`defaultStream()`、`approvalStream()` 与可配置的 `revertResult`。
- 不为 E2E 修改生产代码；不新增后端启动脚本与 e2e profile。
- 保留后续可选项：真实后端 E2E 待 `ScriptedAgentClient` 提升到可注入契约后另行评估。

### FR-8 Playwright E2E 用例

测试目录：`frontend/e2e/`，每个用例独立 `describe`。

**TC-1 发送与流式**（`chat-stream.spec.ts`）
- 登录 → 进入聊天 → 输入「你好」→ 回车 → 断言「正在生成」出现 → 流式正文逐字出现 → `done` 后「已完成」或正文收尾。
- 断言：发送后输入框被禁用/清空，生成中状态 chip 可见。

**TC-2 审批允许与拒绝**（`approval.spec.ts`）
- 触发需要审批的工具调用（scripted 脚本含 write_file）。
- 允许路径：审批卡出现 → 点「允许」→ 工具执行结果回显 → 继续。
- 拒绝路径：点「拒绝」→ 审批卡切已拒绝 → 任务完成状态为未完成/失败（按脚本）。

**TC-3 停止生成**（`stop.spec.ts`）
- 发送后在生成中点「停止」→ 断言生成中止、状态为已停止、可继续输入。

**TC-4 断线续传**（`reconnect.spec.ts`）
- 发送后人为断开网络（`page.route` 拒绝 / `context.setOffline`）→ 再恢复 → 断言正文按 runId 续传补全，不重复渲染。
- 依赖 P5-10 已实现的 `recoverRun`。

**TC-5 P5-14 完整交付路径**（`changeset-delivery.spec.ts`）
- 前置：工作区有一个可修改文件。
- 发送「修改该文件」→ 审批允许 → verify（脚本化通过/失败）→ 断言 PendingChangesCard 显示变更与验证状态。
- 审查 diff → 「全部保留」→ 断言变更 status ACCEPTED，验证状态不变（仍 UNVERIFIED/NOT_CONFIGURED）。
- 或：整体撤回 → 断言文件恢复原内容、变更 REVERTED。
- 外部修改冲突：脚本化外部修改后撤回 → 断言冲突提示、文件未被覆盖。

所有 E2E 用例：
- 使用 `data-testid` 或 `getByRole`/`getByText` 定位，不用脆弱的 CSS 选择器。
- 断言用可观测条件（`expect(...).toBeVisible()`、URL 变化、接口响应），不用固定 `page.waitForTimeout`。
- 测试账号从 `.env.e2e` 或 Playwright `use.storageState` 注入，不硬编码密码。

### FR-9 CI 集成

- `.github/workflows/ci.yml` 新增 job `frontend-test`：
  - `npm ci` → `npx vitest run` → 上传 coverage（可选）。
  - 失败不阻塞 `backend` job，但阻塞合并（required check）。
- 新增 job `frontend-e2e`：
  - 启动 E2E 后端（`scripts/start-e2e-backend.ps1` 或 bash 等价）→ 健康检查 → `npx playwright test` → 失败上传 `test-results` 与 `playwright-report` → 关闭后端。
  - `timeout-minutes: 15`，`needs: frontend-test`（单测过了才跑 E2E）。
  - 浏览器缓存：`actions/cache` 缓存 `~/.cache/ms-playwright`；如系统有 Chromium 则用 channel。
- `frontend` 现有 job 保持 `npm run build` 不变。

## Non-Functional Requirements

- **NFR-1 可离线运行**：Vitest 完全离线；Playwright 浏览器二进制需提前安装（CI 缓存或 `playwright install chromium` 一次性）。
- **NFR-2 稳定**：E2E 用例不依赖真实时延，不用 `waitForTimeout`；连续 5 次本地运行无 flaky 才算通过。
- **NFR-3 不污染**：E2E 后端用临时工作区与嵌入 PG，关闭后清理；种子数据独立于开发库。
- **NFR-4 最小依赖**：只新增 `vitest`、`@vue/test-utils`、`jsdom`、`@playwright/test` 四个 devDependency，不引入断言库/测试框架替代品。
- **NFR-5 不阻塞构建**：测试失败不影响 `npm run build` 产物；E2E 不阻塞后端打包。

## Constraints

- Node 22（CI 已用）；Vite 6；Vue 3.5；TypeScript 5.7。
- 后端 Java 21 / Spring Boot 4.1.2；嵌入 PG 用 zonky（已在 test scope）。
- 不修改生产代码的公共契约；如需暴露测试钩子，用 `if (import.meta.env.DEV)` 或 props 可选方式。
- 颜色与样式测试不做像素断言。

## Assumptions

- Playwright 浏览器可通过 `npx playwright install chromium` 安装；也支持 `E2E_CHANNEL` 指定系统浏览器。
- 前端登录可被绕过：DESKTOP 模式下 `addInitScript` 预置 localStorage token，`meta` 与运行接口均由 route 仿真。
- `page.route` 的 `fulfill` 使用静态 body；断线续传通过「轮询已落库事件」路径验证，不模拟同一条 SSE 连接的中途断开。

## Acceptance Criteria

### AC-1 Vitest 可运行
- **Given** 前端依赖已安装
- **When** 执行 `npm run test`
- **Then** 所有 `*.test.ts` 通过，退出码 0；`npm run build` 仍通过。

### AC-2 SSE 解析改坏必红
- **Given** `sse.test.ts` 覆盖 done 终止逻辑
- **When** 把 `parseSSE` 中 done 的 `return true` 改为 `return`（不终止）
- **Then** 该测试失败。

### AC-3 审批按钮改坏必红
- **Given** `ApprovalCard.test.ts` 覆盖允许/拒绝调用
- **When** 把 `respond('APPROVE')` 改为 `respond('REJECT')`
- **Then** 允许测试失败。

### AC-4 Playwright E2E 5 条路径通过
- **Given** E2E 后端已启动、前端已构建并运行
- **When** 执行 `npx playwright test`
- **Then** TC-1～TC-5 全部通过，无 flaky（连续 3 次本地运行全绿）。

### AC-5 E2E 无后端与模型依赖
- **Given** 无外部 PostgreSQL、无后端进程、无模型 Key
- **When** 执行 `npx playwright test`
- **Then** 所有 `/api/**` 由 `mockApi` 仿真，5 条路径可确定性运行。

### AC-6 CI 双 job
- **Given** PR 推送
- **When** CI 运行
- **Then** `frontend-test` 跑 vitest，`frontend-e2e` 跑 Playwright 并上传 trace；两个 job 互不阻塞后端 job。

### AC-7 文档同步
- 四份文档（`UPDATE_PLAN.md`、`phases/phase5/plan.md`、`COMPLETED_WORK.md`、`architecture/README.md`）同步 P5-11-1 状态与剩余边界。

## Verification Plan

1. 本地：`cd frontend && npm run test` 全绿；`npm run build` 通过。
2. 本地 E2E：`npx playwright install chromium`（一次性）→ `npx playwright test`（自动启动 Vite dev server）全绿，连续 3 次。
3. CI：推送分支触发 `frontend-test` 与 `frontend-e2e`，确认 trace 上传。
4. 回归：`mvn -o test` 后端全量测试通过（E2eBackend 不影响现有测试）。
5. 改坏验证：手动破坏 SSE done 终止与审批按钮，确认对应单测变红。

## Required Approval

实施前需用户确认本规格。确认后按 `tasks.md` 顺序执行；若实现中发现 `ScriptedAgentClient` 注入或嵌入 PG 启动与现有契约冲突，先记录并调整规格，不静默扩大范围。
