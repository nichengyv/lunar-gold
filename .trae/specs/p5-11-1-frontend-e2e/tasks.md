# P5-11-1 前端测试基线（Vitest + Playwright E2E）- Implementation Plan

## Task 1: Vitest 基础设施
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - `frontend/package.json` 新增 devDependencies：`vitest`、`@vue/test-utils`、`jsdom`（选与 Vite 6 / Vue 3.5 兼容的版本）。
  - 新增 scripts：`test`、`test:watch`、`test:coverage`。
  - 实施偏差：`test:coverage` 未加入（需第 5 个 devDependency，违反 NFR-4），覆盖率以用例清单体现。
  - 新增 `frontend/vitest.config.ts`：environment `jsdom`，test match `**/*.{test,spec}.{ts,tsx}`，排除 node_modules/dist。
  - 运行 `npm install` 并确认 `npx vitest run` 在无测试文件时正常退出（0）。
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-1.1: `npm run test` 退出码 0（空跑）。
  - `rule` TR-1.2: `npm run build` 仍通过（vue-tsc + vite build）。

## Task 2: SSE 解析单测
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `frontend/src/api/sse.test.ts`。
  - 用 mock `ReadableStream` reader 构造分块输入，覆盖：多行 data 合并、CRLF/LF、done 终止并 cancel reader、error 终止、run_id/turn_id/content/approval_required 解析、流中断残余派发、id/retry/注释忽略。
- **Acceptance Criteria Addressed**: AC-2
- **Test Requirements**:
  - `rule` TR-2.1: 全部用例通过。
  - `rule` TR-2.2: 手动破坏 done 终止逻辑后该用例失败。

## Task 3: chat store 状态单测
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `frontend/src/stores/chat.test.ts`，mock `api/*` 模块。
  - 覆盖 sendMessage 状态机、stopGenerating、recoverRun runId 重放、RUNNING→WAITING_APPROVAL→SUCCEEDED 迁移。
- **Acceptance Criteria Addressed**: AC-1
- **Test Requirements**:
  - `rule` TR-3.1: 全部用例通过。

## Task 4: ApprovalCard 与 MarkdownContent 单测
- [x] **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 1
- **Description**:
  - `ApprovalCard.test.ts`：挂载渲染、允许/拒绝调用、resolved 态、倒计时到 0 dismiss、快捷键 A/D。
  - `MarkdownContent.test.ts`：带语言/无语言代码块渲染、复制按钮调用 copyToClipboard、普通文本渲染。
- **Acceptance Criteria Addressed**: AC-3
- **Test Requirements**:
  - `rule` TR-4.1: 全部用例通过。
  - `rule` TR-4.2: 手动把允许改为拒绝后允许用例失败。

## Task 5: Playwright 基础设施
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新增 devDependency `@playwright/test`。
  - 新增 `frontend/playwright.config.ts`：testDir `./e2e`、baseURL 走环境变量、trace retain-on-failure、webServer 可选、浏览器优先 channel chromium。
  - 新增 `frontend/e2e/` 目录与 `.gitignore` 忽略 `test-results`、`playwright-report`。
  - 新增 `package.json` script：`test:e2e`。
- **Acceptance Criteria Addressed**: AC-4
- **Test Requirements**:
  - `rule` TR-5.1: `npx playwright test --list` 能列出用例（空目录也不报错）。

## Task 6: E2E API 仿真夹具
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 5
- **Description**:
  - 新建 `frontend/e2e/fixtures/api.ts`：`mockApi(page, options)` 注入 token，仿真 `/api/meta`、`/api/projects`、`/api/agent/sessions`、`/api/agent/runs/running`、`/api/agent/runs/{id}/events`、`/api/settings/defaults`、`/api/agent/chat/stream`、`/api/file-changes*`。
  - 提供 `changeSetFixture()`、`defaultStream()`、`approvalStream()`，支持 `streamDelayMs`、`changeSetAfterChange`、`revertResult` 等可配置项。
  - 实施调整：不启动 Spring 后端（`ScriptedAgentClient` 在 test scope、桌面登录依赖本机令牌，外部进程复现不稳定），改为浏览器层 route 仿真；原因已记录到 spec FR-7。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-6.1: E2E 不依赖外部 PG、后端进程或模型 Key。
  - `rule` TR-6.2: SSE、审批、变更与撤回响应均可由夹具确定性返回。

## Task 7: Playwright E2E 用例
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 6
- **Description**:
  - `frontend/e2e/chat-stream.spec.ts`：TC-1 发送与流式。
  - `frontend/e2e/approval.spec.ts`：TC-2 审批允许/拒绝。
  - `frontend/e2e/stop.spec.ts`：TC-3 停止生成。
  - `frontend/e2e/reconnect.spec.ts`：TC-4 断线续传（按 runId 轮询已落库事件）。
  - `frontend/e2e/changeset-delivery.spec.ts`：TC-5 P5-14 交付路径（接受不改验证、撤回冲突提示）。
- **Acceptance Criteria Addressed**: AC-4
- **Test Requirements**:
  - `rule` TR-7.1: 7 个用例全部通过（chat-stream 1、approval 2、stop 1、reconnect 1、changeset-delivery 2）。
  - `rule` TR-7.2: 连续 3 次本地运行全绿（无 flaky）。

## Task 8: CI 集成
- [x] **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 4, Task 7
- **Description**:
  - `.github/workflows/ci.yml` 新增 `frontend-test` job（npm ci → vitest run）与 `frontend-e2e` job（Vite dev server → playwright test → 上传 trace）。
  - 浏览器缓存配置。
  - 实施偏差：`frontend-e2e` 用 `E2E_CHANNEL=none` 走 Playwright 自带 chromium，不启动 Spring 后端（后端由浏览器层 route 仿真）。
- **Acceptance Criteria Addressed**: AC-6
- **Test Requirements**:
  - `rule` TR-8.1: CI yml 语法正确（`act` 或 dry-run 校验）。

## Task 9: 回归与文档
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 7, Task 8
- **Description**:
  - 后端 `mvn -o test` 全量通过（E2E 夹具不进入后端测试）。
  - 前端 `npm run test` 与 `npx playwright test` 通过。
  - 同步四份文档：`docs/UPDATE_PLAN.md`、`docs/phases/phase5/plan.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`。
  - 在 P5-14 状态处把「E2E 待 P5-11-1」更新为已实施。
- **Acceptance Criteria Addressed**: AC-7
- **Test Requirements**:
  - `rule` TR-9.1: 后端全量测试与前端单测/E2E 无回归。
  - `rule` TR-9.2: 四份文档状态一致。

## Task Dependencies
- Task 2, 3, 4 depend on Task 1
- Task 5 depends on Task 1
- Task 6 depends on Task 5
- Task 7 depends on Task 6
- Task 8 depends on Task 4, Task 7
- Task 9 depends on Task 7, Task 8

## Verification Commands

```powershell
# frontend/
npm install
npm run test
npm run build
# E2E（自动启动 Vite dev server）
npx playwright test
# backend (root)
$env:JAVA_HOME='C:\Users\16680\.jdks\ms-21.0.11'
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o test
```
