# P5-11-1 前端测试基线 - 验收清单

## A. Vitest 基础设施

- [x] A1. `frontend/package.json` 新增 `vitest`、`@vue/test-utils`、`jsdom` devDependencies 与 `test`/`test:watch` scripts；`test:coverage` 未接入（需第 5 个 devDependency，违反 NFR-4，偏差已记录）。
- [x] A2. `frontend/vitest.config.ts` 存在，environment 为 jsdom，test match 正确，不影响 `vite.config.ts`。
- [x] A3. `npm run test` 空跑退出码 0；`npm run build` 仍通过。

## B. 单测覆盖

- [x] B1. `sse.test.ts` 覆盖多行 data、CRLF/LF、done 终止并 cancel reader、error 终止、run_id/turn_id/content/approval_required 解析、残余派发、忽略 id/retry/注释。
- [x] B2. 破坏 done 终止逻辑后 `sse.test.ts` 对应用例失败（实测 2 项失败，已还原）。
- [x] B3. `chat.test.ts` 覆盖 sendMessage 状态机、stopGenerating、recoverRun runId 重放、状态迁移。
- [x] B4. `ApprovalCard.test.ts` 覆盖渲染、允许/拒绝调用、resolved 态、倒计时 dismiss、快捷键 A/D。
- [x] B5. 破坏允许→拒绝后允许用例失败（实测 1 项失败，已还原）。
- [x] B6. `MarkdownContent.test.ts` 覆盖带语言/无语言代码块、复制按钮、普通文本。

## C. Playwright 基础设施

- [x] C1. `@playwright/test` 已加入 devDependencies；`playwright.config.ts` 存在，testDir 为 `./e2e`，trace retain-on-failure，baseURL 可由 `E2E_BASE_URL` 覆盖。
- [x] C2. 默认 `channel: 'chromium'`，`E2E_CHANNEL=none` 时使用 Playwright 自带浏览器；本机改为 `E2E_CHANNEL=msedge` 绕过 CDN 下载失败。
- [x] C3. `test-results`、`playwright-report` 已在 `.gitignore`。

## D. E2E API 仿真

- [x] D1. `frontend/e2e/fixtures/api.ts` 的 `mockApi` 能注入登录态并仿真 meta、项目、会话、运行事件、SSE 与文件变更接口。
- [x] D2. 提供 `changeSetFixture`、`defaultStream`、`approvalStream`，支持延迟流、接受后视图、撤回结果与恢复后历史配置。
- [x] D3. E2E 不依赖外部 PostgreSQL、后端进程或模型 Key。
- [x] D4. `playwright.config.ts` 自动启动 Vite dev server，`E2E_BASE_URL` 可覆盖。

## E. E2E 用例

- [x] E1. `chat-stream.spec.ts`：发送→流式→完成，状态正确。
- [x] E2. `approval.spec.ts`：允许与拒绝两条路径，校验提交 decision 并移除卡片。
- [x] E3. `stop.spec.ts`：停止生成后状态为已停止，可继续输入。
- [x] E4. `reconnect.spec.ts`：断网恢复后正文按 runId 续传，不重复渲染。
- [x] E5. `changeset-delivery.spec.ts`：修改→审批→verify→审查→（保留/撤回），验证状态不因接受改变；外部修改撤回返回冲突且不覆盖。
- [x] E6. 所有用例使用可观测条件断言，无 `waitForTimeout`；测试 token 由 `addInitScript` 注入，不硬编码真实账号。

## F. CI

- [x] F1. `ci.yml` 新增 `frontend-test` job（npm ci → vitest run）。
- [x] F2. `ci.yml` 新增 `frontend-e2e` job（Vite dev server → playwright → 上传 trace），`needs: frontend-test`；后端由浏览器层 route 仿真，不启动 Spring。
- [x] F3. 浏览器缓存配置；E2E 不阻塞后端 job。

## G. 回归与文档

- [x] G1. 后端 `mvn -o test` 全量通过（279 项，0 失败 / 0 错误 / 12 skipped，BUILD SUCCESS），无新增失败。
- [x] G2. 前端 `npm run test` 全绿（22/22）；`npx playwright test --repeat-each=3` 连续 21/21 全绿。
- [x] G3. 四份文档（UPDATE_PLAN、phase5/plan、COMPLETED_WORK、architecture/README）同步 P5-11-1 已实施状态，并更新 P5-14 的 E2E 依赖项。
