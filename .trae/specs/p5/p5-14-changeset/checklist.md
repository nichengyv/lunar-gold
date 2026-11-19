# P5-14 任务级 ChangeSet 与交付 - 验收清单

## A. 持久化与聚合

- [x] A1. `AgentChatController` 在 `agentRunService.create` 前生成 `turnId` 并写入 `requestJson`；执行块复用同一 `turnId`，无重复生成。
- [x] A2. 未新增 Flyway 迁移，未修改 `PendingFileChange` 表结构；`AgentRunRepository` 与 `PendingFileChangeRepository` 只新增所需查询方法。
- [x] A3. `ChangeSetService.view` 返回 `sessionId/projectId/runFilter/status/statusLabel/accepted/verification/approvals/totals/runs/changes/continuePrompt`，多 Run 变更按 `turnId` 归属，无法归属时 `runId=null` 且仍展示。
- [x] A4. `runId` 过滤后只保留目标 Run 与其变更，`totals/status/verification` 基于过滤结果。

## B. 继续修复

- [x] B1. `continuePrompt` 包含固定提示语、原任务 prompt、失败命令（含 `outputTail`，最多 3 条且单条截断 800 字符）、诊断 ERROR（最多 10 条）、未完成计划；无依据时为空串。
- [x] B2. 前端「继续修复」在当前 Session 调用 `sendMessage`，不新建会话。

## C. 撤回与冲突

- [x] C1. 只回滚 `PENDING` 变更，按 `createTime DESC, id DESC` 逆序；同一文件 A→B→C 整体撤回恢复为 A。
- [x] C2. 按 Run 撤回只影响目标 turn，其他 Run 变更状态不变。
- [x] C3. `oldContent` 为空、修改、删除三类文件按规则处理；内容比较忽略 CRLF/LF 差异。
- [x] C4. 外部修改/删除返回冲突，不写盘、不改状态，批量继续处理其余项，`RevertResult` 返回 `conflicts`。
- [x] C5. `ACCEPTED` 变更被跳过并计入 `skippedAccepted`，记录保留可查询。

## D. 接受不覆盖未验证

- [x] D1. `verification` 只来自 VERIFY 事件或 completion reason，与 `PendingFileChange.status` 无关；接受后仍为 `UNVERIFIED/NOT_CONFIGURED` 等原状态，任务状态不变为验证通过。
- [x] D2. `accepted` 仅在全部变更均为 `ACCEPTED` 时为真，混合 `REVERTED` 不误判。

## E. 接口与前端

- [x] E1. `ChangeSetController` 提供 `GET /api/file-changes/changeset`、`POST /revert-session`、`POST /revert-run`，均校验 Session 归属，空结果不抛 404。
- [x] E2. `FileReviewController` 保留原列表与 accept/accept-all 语义，revert/revert-all/revert-turn 委托 `ChangeSetService`，删除本地覆盖写盘逻辑，冲突返回 `ok=false` 与原因。
- [x] E3. 前端新增 ChangeSet 类型、`fetchChangeSet/revertSession/revertRun`，store 具备 `changeSet/changeSetLoading/changeSetRunFilter` 与 `fetchChangeSet/setChangeSetRunFilter/revertTask/revertRunChanges/continueFix/refreshChanges`，并在会话加载、生成结束、accept/revert 后刷新。
- [x] E4. `PendingChangesCard.vue` 展示状态 chip、Run 过滤、验证/审批摘要、按 Run 分组文件与 diff、撤回此轮、继续修复/整体撤回/全部保留；`changeSet` 为空时回退 `pendingChanges`；颜色只用 tokens 变量。
- [x] E5. `ChatView.vue` 在本地工作区已有会话时 onMounted 触发 `fetchChangeSet`，刷新后 ChangeSet 由后端重建。

## F. 安全与回归

- [x] F1. 所有新接口校验 Session/Project 归属，路径仍经 `WorkspaceFileService.resolveInWorkspace`，不会写出工作区。
- [x] F2. `ChangeSetServiceTest` 覆盖 7 类场景（多 Run 聚合、runId 过滤、continuePrompt、接受不改验证、逆序撤回、按 Run 撤回、外部冲突、已接受跳过）并全部通过。
- [x] F3. 后端 `mvn -o -q compile`、`mvn -o -q test` 通过，无新增失败。
- [x] F4. `mvn -o -q test -Dtest=EvalGate,RuntimeEvalGate` 通过（评估门禁无回退）。
- [x] F5. 前端 `vue-tsc --noEmit` 通过，未新增 npm 依赖。
- [x] F6. 四份文档状态一致：`docs/UPDATE_PLAN.md`、`docs/phases/phase5/plan.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md` 均按「基础完成」记录，并明确 P5-11-1 前端 E2E 未实施。
