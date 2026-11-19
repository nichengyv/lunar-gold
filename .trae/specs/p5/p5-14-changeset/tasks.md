# P5-14 任务级 ChangeSet 与交付 - Implementation Plan

## Task 1: Run ↔ Turn 映射与仓储扩展
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - `AgentChatController.streamChat`：在 `agentRunService.create` 前生成 `final String turnId`，将 `turnId` 写入 `requestJson`（改为 `LinkedHashMap`），删除执行块内重复生成。
  - `AgentRunRepository` 新增 `findByUserIdAndSessionIdOrderByCreateTimeDescIdDesc`。
  - `PendingFileChangeRepository` 新增按 Session 全量/按状态且 `createTime DESC, id DESC` 的查询。
- **Acceptance Criteria Addressed**: AC-1, AC-7
- **Test Requirements**:
  - `rule` TR-1.1: `mvn -o -q compile` 通过。
  - `rule` TR-1.2: 新 Run 的 `requestJson` 含 `turnId`，Execute 仍收到同一 turnId。

## Task 2: ChangeSetService 聚合
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `service/ChangeSetService.java`，依赖见 spec FR-3。
  - 实现 Run 查询/过滤、requestJson/resultJson/事件解析、变更按 turnId 归属、totals/approvals/verification/status/statusLabel/accepted。
  - 实现 `continuePrompt`：原任务 + 验证失败命令与输出尾部 + 诊断 ERROR + 未完成计划 + 固定提示；无依据返回空串。
  - 单 Run 解析失败降级为空摘要，不影响整体。
- **Acceptance Criteria Addressed**: AC-1, AC-2, AC-5
- **Test Requirements**:
  - `rule` TR-2.1: 两次 Run 聚合字段完整、totals 正确。
  - `rule` TR-2.2: `runId` 过滤只保留目标 Run 与变更。
  - `rule` TR-2.3: 接受变更后 verification/status 不变。
  - `rule` TR-2.4: continuePrompt 含失败输出与未完成计划。

## Task 3: 撤回与冲突检测
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2
- **Description**:
  - `ChangeSetService.revertSingle/revertTurn/revertRun/revertSession/revertProject`。
  - 只处理 PENDING；`createTime DESC, id DESC` 逆序；`revertOne` 按 spec FR-4 检测外部修改与缺失文件。
  - 新建/修改/删除三类文件的冲突规则与换行归一比较。
  - 返回 `RevertResult{ok,count,failed,skippedAccepted,conflicts}`。
- **Acceptance Criteria Addressed**: AC-3, AC-4, AC-6
- **Test Requirements**:
  - `rule` TR-3.1: 同文件 A→B→C 整体撤回恢复 A。
  - `rule` TR-3.2: 按 Run 撤回只影响目标 turn。
  - `rule` TR-3.3: 外部修改返回冲突且不写盘。
  - `rule` TR-3.4: ACCEPTED 变更跳过且保留记录。

## Task 4: Controller 接入
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2, Task 3
- **Description**:
  - 新建 `controller/ChangeSetController.java`：`GET /changeset`、`POST /revert-session`、`POST /revert-run`，校验 Session 归属。
  - `FileReviewController` 注入 `ChangeSetService`，`/{id}/revert`、`/revert-all`、`/revert-turn` 改为委托；删除本地 `revertOnDisk`；保持 accept 行为。
- **Acceptance Criteria Addressed**: AC-1, AC-3, AC-4, AC-5
- **Test Requirements**:
  - `rule` TR-4.1: 新接口按 Session/Run 返回正确结构。
  - `rule` TR-4.2: 既有 revert 接口在冲突时返回 `ok=false` 与原因，不覆盖文件。

## Task 5: 前端类型、API 与 store
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 4
- **Description**:
  - `types/api.ts` 增加 ChangeSet 相关类型。
  - `api/fileChanges.ts` 增加 `fetchChangeSet/revertSession/revertRun`。
  - `stores/chat.ts` 增加 `changeSet/changeSetLoading/changeSetRunFilter` 与 `fetchChangeSet/setChangeSetRunFilter/revertTask/revertRunChanges/continueFix/refreshChanges`；接入会话切换、新会话、生成结束与 accept/revert 后刷新。
- **Acceptance Criteria Addressed**: AC-1, AC-2, AC-3, AC-7
- **Test Requirements**:
  - `rule` TR-5.1: `vue-tsc --noEmit` 通过。
  - `rule` TR-5.2: 无新 npm 依赖。

## Task 6: 任务交付看板
- [x] **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 5
- **Description**:
  - 升级 `PendingChangesCard.vue`：状态 chip、Run 过滤、验证/审批摘要、按 Run 分组文件与 diff、每组撤回、继续修复/整体撤回/全部保留。
  - 未验证状态在头部与验证区始终显示；接受后不变为验证通过。
  - `changeSet` 为空时退回现有 `pendingChanges` 展示。
  - `ChatView.vue` 在有 Session 时触发 `fetchChangeSet`。
- **Acceptance Criteria Addressed**: AC-2, AC-5, AC-7
- **Test Requirements**:
  - `rule` TR-6.1: 前端类型检查通过。
  - `rule` TR-6.2: 颜色只用 tokens 变量；窄窗口不溢出。

## Task 7: 测试、回归与文档
- [x] **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2, Task 3, Task 6
- **Description**:
  - 新增 `src/test/java/com/capsule/agentkey/service/ChangeSetServiceTest.java`，覆盖 spec Verification Plan 的 7 类场景。
  - 运行后端编译、全量测试、双评测门禁；前端 `vue-tsc --noEmit`。
  - 同步四份文档：`docs/UPDATE_PLAN.md`、`docs/phases/phase5/plan.md`、`docs/COMPLETED_WORK.md`、`docs/architecture/README.md`。
  - 明确记录 P5-11-1 E2E 仍待实施；P5-14 按验收边界标记「基础完成」。
- **Acceptance Criteria Addressed**: AC-8
- **Test Requirements**:
  - `rule` TR-7.1: 新增测试全部通过，不依赖外部网络/真实模型。
  - `rule` TR-7.2: 全量测试与双门禁无回归。
  - `rule` TR-7.3: 四份文档状态一致。

## Verification Commands

```powershell
$env:JAVA_HOME='C:\Users\16680\.jdks\ms-21.0.11'
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q compile
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q test "-Dtest=ChangeSetServiceTest"
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q test
& 'C:\Users\16680\.trae-cn\tools\maven\latest\bin\mvn.cmd' -o -q test "-Dtest=EvalGate,RuntimeEvalGate"
# frontend/
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
```
