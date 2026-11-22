package com.capsule.agentkey.agent.subtask;

import com.capsule.agentkey.agent.run.PlanStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7c：子任务规格解析——稳定 id、目标长度与强制验收契约。
 */
class SubtaskSpecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<SubtaskSpec> parse(String json, int max) throws Exception {
        return SubtaskSpec.parse(mapper.readTree(json), max);
    }

    @Test
    void 解析合法子任务并保留验收契约() throws Exception {
        List<SubtaskSpec> specs = parse("""
                {"subtasks":[
                  {"id":"s1","objective":"实现登录","acceptance":{"artifacts":[
                     {"path":"src/Login.java","expectedContains":["class Login"]}]}},
                  {"id":"s2","objective":"补测试","acceptance":{"requireVerify":true}}
                ]}
                """, 4);

        assertEquals(2, specs.size());
        assertEquals("s1", specs.get(0).id());
        assertEquals("src/Login.java", specs.get(0).acceptance().artifacts().get(0).path());
        assertTrue(specs.get(1).acceptance().requireVerify());
        assertTrue(specs.get(0).describeAcceptance().contains("src/Login.java"));
    }

    @Test
    void 超过上限直接拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse("""
                {"subtasks":[
                  {"id":"s1","objective":"a","acceptance":{"requireVerify":true}},
                  {"id":"s2","objective":"b","acceptance":{"requireVerify":true}},
                  {"id":"s3","objective":"c","acceptance":{"requireVerify":true}},
                  {"id":"s4","objective":"d","acceptance":{"requireVerify":true}},
                  {"id":"s5","objective":"e","acceptance":{"requireVerify":true}}
                ]}
                """, 4));
        assertTrue(ex.getMessage().contains("最多 4"));
    }

    @Test
    void 缺少验收契约不能委派() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse("""
                {"subtasks":[{"id":"s1","objective":"a"}]}
                """, 4));
        assertTrue(ex.getMessage().contains("acceptance"));
    }

    @Test
    void id重复被拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse("""
                {"subtasks":[
                  {"id":"s1","objective":"a","acceptance":{"requireVerify":true}},
                  {"id":"s1","objective":"b","acceptance":{"requireVerify":true}}
                ]}
                """, 4));
        assertTrue(ex.getMessage().contains("重复"));
    }

    @Test
    void 非法id与超长目标被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
                {"subtasks":[{"id":"bad id","objective":"a","acceptance":{"requireVerify":true}}]}
                """, 4));
        String longObjective = "x".repeat(SubtaskSpec.MAX_OBJECTIVE_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> parse("""
                {"subtasks":[{"id":"s1","objective":"%s","acceptance":{"requireVerify":true}}]}
                """.formatted(longObjective), 4));
    }

    @Test
    void 空数组与缺失字段被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> parse("{}", 4));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"subtasks\":[]}", 4));
    }

    @Test
    void 工件断言要求至少一条期望文本() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
                {"subtasks":[{"id":"s1","objective":"a","acceptance":{"artifacts":[
                   {"path":"a.txt","expectedContains":[]}]}}]}
                """, 4));
        // 纯空 acceptance 也不构成证据
        assertThrows(IllegalArgumentException.class, () -> parse("""
                {"subtasks":[{"id":"s1","objective":"a","acceptance":{}}]}
                """, 4));
    }

    @Test
    void 验收契约是不可为空的对象() throws Exception {
        List<SubtaskSpec> specs = parse("""
                {"subtasks":[{"id":"s1","objective":"a","acceptance":{"requireVerify":true}}]}
                """, 1);
        PlanStep.Acceptance acceptance = specs.get(0).acceptance();
        assertEquals(0, acceptance.artifacts().size());
        assertTrue(acceptance.requireVerify());
    }
}
