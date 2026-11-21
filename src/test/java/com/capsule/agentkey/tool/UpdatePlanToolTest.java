package com.capsule.agentkey.tool;

import com.capsule.agentkey.agent.run.PlanEvidenceService;
import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.verify.VerifyResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第六十八批 A1：update_plan 工具的校验与快照写入。
 *
 * <p>R7c 起补充确定性验收契约：id 稳定性、路径安全、契约不可变、完成证据核对。
 */
class UpdatePlanToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;

    private UpdatePlanTool tool;

    @BeforeEach
    void setUp() {
        WorkspaceContext.set(1L, 2L);
        tool = new UpdatePlanTool(PlanEvidenceService.withLocator((userId, projectId) -> workspace));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    /** 每个 step 为 [title, status] */
    private ToolResult run(Object[]... steps) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] s : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", s[0]);
            item.put("status", s[1]);
            list.add(item);
        }
        return execute(list);
    }

    private ToolResult execute(List<Map<String, Object>> steps) throws Exception {
        return tool.execute(mapper.valueToTree(Map.of("steps", steps)));
    }

    private Map<String, Object> step(String title, String status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", title);
        item.put("status", status);
        return item;
    }

    private Map<String, Object> contractStep(String title, String status, String id,
                                             String path, String expected) {
        Map<String, Object> item = step(title, status);
        item.put("id", id);
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("path", path);
        artifact.put("expectedContains", List.of(expected));
        Map<String, Object> acceptance = new LinkedHashMap<>();
        acceptance.put("artifacts", List.of(artifact));
        item.put("acceptance", acceptance);
        return item;
    }

    private void write(String relative, String content) throws Exception {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void passedVerify() {
        WorkspaceContext.get().setVerify(new VerifyResult(VerifyResult.Status.PASSED,
                ".lunargold/verify.json", true, 1, 3, List.of(), "", "通过"));
    }

    // —— 旧两字段计划：行为保持不变 ——

    @Test
    void 正常计划写入快照并标记待推送() throws Exception {
        ToolResult result = run(
                new Object[]{"读取项目结构", "completed"},
                new Object[]{"修改登录接口", "in_progress"},
                new Object[]{"运行测试", "pending"}
        );

        assertTrue(result.isSuccess(), result.getError());
        WorkspaceContext ctx = WorkspaceContext.get();
        assertEquals(3, ctx.plan().size());
        assertEquals("读取项目结构", ctx.plan().get(0).title());
        assertEquals("in_progress", ctx.plan().get(1).status());
        assertTrue(ctx.drainPlanChanged(), "计划更新后应标记待推送");
        assertFalse(ctx.drainPlanChanged(), "推送后标记应被清空");
    }

    @Test
    void 多个进行中步骤被拒绝() throws Exception {
        ToolResult result = run(
                new Object[]{"步骤一", "in_progress"},
                new Object[]{"步骤二", "in_progress"}
        );

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("最多只能有一个进行中的步骤"));
        assertTrue(WorkspaceContext.get().plan().isEmpty());
    }

    @Test
    void 空标题被拒绝() throws Exception {
        ToolResult result = run(new Object[]{"   ", "pending"});

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("缺少标题"));
    }

    @Test
    void 非法状态被拒绝() throws Exception {
        ToolResult result = run(new Object[]{"步骤", "running"});

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("状态非法"));
    }

    @Test
    void 超过二十步被拒绝() throws Exception {
        List<Object[]> steps = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            steps.add(new Object[]{"步骤 " + (i + 1), "pending"});
        }

        ToolResult result = run(steps.toArray(new Object[0][]));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("步骤过多"));
    }

    @Test
    void 旧格式计划JSON可继续读取且输出保持两字段() throws Exception {
        List<PlanStep> legacy = mapper.readValue(
                "[{\"title\":\"a\",\"status\":\"completed\"}]",
                new TypeReference<List<PlanStep>>() {});
        assertNull(legacy.get(0).id());
        assertNull(legacy.get(0).acceptance());

        String json = mapper.writeValueAsString(List.of(new PlanStep("a", "pending")));
        assertEquals("[{\"title\":\"a\",\"status\":\"pending\"}]", json);
    }

    @Test
    void 带契约的计划可序列化并往返() throws Exception {
        PlanStep contract = new PlanStep("实现接口", "in_progress", "s1",
                new PlanStep.Acceptance(
                        List.of(new PlanStep.Artifact("src/App.java", List.of("class App"))), true));

        String json = mapper.writeValueAsString(List.of(contract));
        assertTrue(json.contains("\"requireVerify\":true"));

        List<PlanStep> parsed = mapper.readValue(json, new TypeReference<List<PlanStep>>() {});
        assertEquals(contract, parsed.get(0));
    }

    // —— R7c：验收契约校验 ——

    @Test
    void 契约步骤必须提供id() throws Exception {
        Map<String, Object> item = step("实现接口", "in_progress");
        Map<String, Object> acceptance = new LinkedHashMap<>();
        acceptance.put("requireVerify", true);
        item.put("acceptance", acceptance);

        ToolResult result = execute(List.of(item));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("必须同时提供稳定的 id"));
    }

    @Test
    void 契约步骤id重复被拒绝() throws Exception {
        ToolResult result = execute(List.of(
                contractStep("步骤一", "pending", "s1", "a.txt", "x"),
                contractStep("步骤二", "pending", "s1", "b.txt", "y")));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("id 重复"));
    }

    @Test
    void 工件路径越界被拒绝() throws Exception {
        for (String path : List.of("../outside.txt", "/etc/passwd", "C:\\Windows\\win.ini")) {
            ToolResult result = execute(List.of(
                    contractStep("实现接口", "in_progress", "s1", path, "x")));

            assertFalse(result.isSuccess(), "路径应被拒绝：" + path);
            assertTrue(result.getError().contains("安全相对路径"), result.getError());
        }
    }

    @Test
    void 空契约被拒绝() throws Exception {
        Map<String, Object> item = step("实现接口", "in_progress");
        item.put("id", "s1");
        item.put("acceptance", Map.of());

        ToolResult result = execute(List.of(item));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("至少要有一个 artifacts"));
    }

    @Test
    void 首次提交不能直接completed() throws Exception {
        write("src/App.java", "class App {}");

        ToolResult result = execute(List.of(
                contractStep("实现接口", "completed", "s1", "src/App.java", "class App")));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("首次提交不能直接为 completed"));
        assertTrue(WorkspaceContext.get().plan().isEmpty());
    }

    @Test
    void 经过进行中后completed且工件命中则成功() throws Exception {
        write("src/App.java", "class App { void run() {} }");

        assertTrue(execute(List.of(
                contractStep("实现接口", "in_progress", "s1", "src/App.java", "void run")))
                .isSuccess());

        ToolResult result = execute(List.of(
                contractStep("实现接口", "completed", "s1", "src/App.java", "void run")));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals("completed", WorkspaceContext.get().plan().get(0).status());
        assertEquals("s1", WorkspaceContext.get().plan().get(0).id());
    }

    @Test
    void completed时工件缺少文本则整次更新失败并保留旧快照() throws Exception {
        write("src/App.java", "class App {}");
        assertTrue(execute(List.of(
                contractStep("实现接口", "in_progress", "s1", "src/App.java", "void run")))
                .isSuccess());
        List<PlanStep> before = WorkspaceContext.get().plan();

        ToolResult result = execute(List.of(
                contractStep("实现接口", "completed", "s1", "src/App.java", "void run")));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("缺少必需内容"), result.getError());
        assertEquals("in_progress", WorkspaceContext.get().plan().get(0).status());
        assertEquals(before, WorkspaceContext.get().plan());
    }

    @Test
    void 工件文本补齐后可通过() throws Exception {
        write("src/App.java", "class App {}");
        assertTrue(execute(List.of(
                contractStep("实现接口", "in_progress", "s1", "src/App.java", "void run")))
                .isSuccess());
        assertFalse(execute(List.of(
                contractStep("实现接口", "completed", "s1", "src/App.java", "void run")))
                .isSuccess());

        write("src/App.java", "class App { void run() {} }");
        ToolResult result = execute(List.of(
                contractStep("实现接口", "completed", "s1", "src/App.java", "void run")));

        assertTrue(result.isSuccess(), result.getError());
    }

    @Test
    void 验收契约不可修改或删除() throws Exception {
        write("a.txt", "x");
        assertTrue(execute(List.of(
                contractStep("步骤", "in_progress", "s1", "a.txt", "x"))).isSuccess());

        ToolResult changed = execute(List.of(
                contractStep("步骤", "in_progress", "s1", "a.txt", "y")));
        assertFalse(changed.isSuccess());
        assertTrue(changed.getError().contains("不可修改或删除"));

        Map<String, Object> withoutContract = step("步骤", "in_progress");
        withoutContract.put("id", "s1");
        ToolResult removed = execute(List.of(withoutContract));
        assertFalse(removed.isSuccess());
        assertTrue(removed.getError().contains("不可修改或删除"));
    }

    @Test
    void 已完成步骤不能重新打开() throws Exception {
        write("a.txt", "x");
        assertTrue(execute(List.of(
                contractStep("步骤", "in_progress", "s1", "a.txt", "x"))).isSuccess());
        assertTrue(execute(List.of(
                contractStep("步骤", "completed", "s1", "a.txt", "x"))).isSuccess());

        ToolResult result = execute(List.of(
                contractStep("步骤", "in_progress", "s1", "a.txt", "x")));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("不能重新打开"));
    }

    @Test
    void 不能从计划中移除已完成的契约步骤() throws Exception {
        write("a.txt", "x");
        assertTrue(execute(List.of(
                contractStep("步骤", "in_progress", "s1", "a.txt", "x"))).isSuccess());
        assertTrue(execute(List.of(
                contractStep("步骤", "completed", "s1", "a.txt", "x"))).isSuccess());

        ToolResult result = execute(List.of(step("换个计划", "pending")));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("不能从计划中移除"));
    }

    @Test
    void requireVerify未运行时不通过() throws Exception {
        ToolResult inProgress = execute(List.of(requireVerifyStep("in_progress")));
        assertTrue(inProgress.isSuccess(), inProgress.getError());

        ToolResult result = execute(List.of(requireVerifyStep("completed")));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("当前验证状态为 未运行"), result.getError());
    }

    @Test
    void requireVerify通过且覆盖最新变更时通过() throws Exception {
        assertTrue(execute(List.of(requireVerifyStep("in_progress"))).isSuccess());
        passedVerify();
        WorkspaceContext.get().runContext().markVerified();

        ToolResult result = execute(List.of(requireVerifyStep("completed")));

        assertTrue(result.isSuccess(), result.getError());
    }

    @Test
    void 验证通过后又有文件变更则证据过期() throws Exception {
        assertTrue(execute(List.of(requireVerifyStep("in_progress"))).isSuccess());
        passedVerify();
        WorkspaceContext.get().runContext().markVerified();
        WorkspaceContext.get().runContext().bumpChangeEpoch();

        ToolResult result = execute(List.of(requireVerifyStep("completed")));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("早于最新文件变更"), result.getError());
    }

    private Map<String, Object> requireVerifyStep(String status) {
        Map<String, Object> item = step("运行验证", status);
        item.put("id", "verify");
        item.put("acceptance", new LinkedHashMap<>(Map.of("requireVerify", true)));
        return item;
    }
}
