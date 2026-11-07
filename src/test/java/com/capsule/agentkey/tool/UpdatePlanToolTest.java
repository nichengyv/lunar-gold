package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第六十八批 A1：update_plan 工具的校验与快照写入。
 */
class UpdatePlanToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UpdatePlanTool tool = new UpdatePlanTool();

    @BeforeEach
    void setUp() {
        WorkspaceContext.set(1L, 2L);
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
        return tool.execute(mapper.valueToTree(Map.of("steps", list)));
    }

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
}
