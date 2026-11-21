package com.capsule.agentkey.agent.run;

import com.capsule.agentkey.verify.VerifyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7c：计划完成证据的确定性核对。
 */
class PlanEvidenceServiceTest {

    @TempDir
    Path workspace;

    private RunContext context() {
        return RunContext.create(1L, 2L, 3L, 4L, "turn-1", new AtomicBoolean(false),
                List.of(), null);
    }

    private PlanEvidenceService service() {
        return PlanEvidenceService.withLocator((userId, projectId) -> workspace);
    }

    private PlanStep completedArtifact(String path, String expected) {
        return new PlanStep("实现", "completed", "s1",
                new PlanStep.Acceptance(
                        List.of(new PlanStep.Artifact(path, List.of(expected))), false));
    }

    private PlanStep completedVerify() {
        return new PlanStep("验证", "completed", "s2",
                new PlanStep.Acceptance(List.of(), true));
    }

    @Test
    void 工件存在且包含文本时满足() throws Exception {
        Path file = workspace.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App { void run() {} }");

        List<PlanEvidenceService.Issue> issues =
                service().checkCompleted(context(), List.of(completedArtifact("src/App.java", "void run")));

        assertTrue(issues.isEmpty());
    }

    @Test
    void 工件不存在或缺少文本时不满足() throws Exception {
        Path file = workspace.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}");

        assertFalse(service().checkCompleted(context(),
                List.of(completedArtifact("src/App.java", "void run"))).isEmpty());
        assertFalse(service().checkCompleted(context(),
                List.of(completedArtifact("src/Missing.java", "x"))).isEmpty());
    }

    @Test
    void 越界路径被拒绝() {
        List<PlanEvidenceService.Issue> issues = service().checkCompleted(context(),
                List.of(completedArtifact("../outside.txt", "x")));

        assertEquals(1, issues.size());
        assertEquals(PlanEvidenceService.ISSUE_MISSING, issues.get(0).kind());
        assertEquals("s1", issues.get(0).stepId());
    }

    @Test
    void 没有工作区时一律不满足() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "x");

        List<PlanEvidenceService.Issue> issues = PlanEvidenceService.withoutWorkspace()
                .checkCompleted(context(), List.of(completedArtifact("a.txt", "x")));

        assertFalse(issues.isEmpty());
        assertEquals(PlanEvidenceService.ISSUE_MISSING, issues.get(0).kind());
    }

    @Test
    void requireVerify未运行或失败时不满足() {
        assertFalse(service().checkCompleted(context(), List.of(completedVerify())).isEmpty());

        RunContext failed = context();
        failed.setVerify(new VerifyResult(VerifyResult.Status.FAILED, "verify.json", true,
                1, 3, List.of(), "fp", "失败"));
        failed.markVerified();
        assertFalse(service().checkCompleted(failed, List.of(completedVerify())).isEmpty());
    }

    @Test
    void requireVerify通过且覆盖最新变更时满足() {
        RunContext ctx = context();
        ctx.bumpChangeEpoch();
        ctx.setVerify(new VerifyResult(VerifyResult.Status.PASSED, "verify.json", true,
                1, 3, List.of(), "", "通过"));
        ctx.markVerified();

        assertTrue(service().checkCompleted(ctx, List.of(completedVerify())).isEmpty());
    }

    @Test
    void 验证后又有变更时报告过期() {
        RunContext ctx = context();
        ctx.setVerify(new VerifyResult(VerifyResult.Status.PASSED, "verify.json", true,
                1, 3, List.of(), "", "通过"));
        ctx.markVerified();
        ctx.bumpChangeEpoch();

        List<PlanEvidenceService.Issue> issues =
                service().checkCompleted(ctx, List.of(completedVerify()));

        assertEquals(1, issues.size());
        assertEquals(PlanEvidenceService.ISSUE_STALE, issues.get(0).kind());
    }

    @Test
    void 只核对已完成且有契约的步骤() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "x");
        PlanStep pending = new PlanStep("待办", "pending", "s3",
                new PlanStep.Acceptance(
                        List.of(new PlanStep.Artifact("missing.txt", List.of("x"))), false));
        PlanStep legacy = new PlanStep("旧步骤", "completed");

        assertTrue(service().checkCompleted(context(), List.of(pending, legacy)).isEmpty());
    }

    @Test
    void 路径安全校验拒绝绝对路径与上级目录() {
        assertTrue(PlanEvidenceService.isSafeRelativePath("src/main/App.java"));
        assertTrue(PlanEvidenceService.isSafeRelativePath("a.txt"));
        assertFalse(PlanEvidenceService.isSafeRelativePath("../a.txt"));
        assertFalse(PlanEvidenceService.isSafeRelativePath("/etc/passwd"));
        assertFalse(PlanEvidenceService.isSafeRelativePath("C:\\Windows\\win.ini"));
        assertFalse(PlanEvidenceService.isSafeRelativePath(""));
        assertFalse(PlanEvidenceService.isSafeRelativePath(null));
    }
}
