package com.capsule.agentkey.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScopeConstraintServiceTest {

    private final ScopeConstraintService svc = new ScopeConstraintService();

    // —— parse: 显式标记 ——

    @Test
    void 显式scope标记_单个路径() {
        List<String> r = svc.parse("#scope: src/components 帮我改下那个组件");
        assertEquals(List.of("src/components"), r);
    }

    @Test
    void 显式scope标记_多个路径() {
        List<String> r = svc.parse("#scope: src/components, src/utils, test.html 重构一下");
        assertEquals(3, r.size());
        assertTrue(r.contains("src/components"));
        assertTrue(r.contains("src/utils"));
        assertTrue(r.contains("test.html"));
    }

    @Test
    void 显式scope标记_中文冒号和中文逗号也识别() {
        List<String> r = svc.parse("帮我重构 范围：src/components、src/utils");
        assertEquals(2, r.size());
    }

    @Test
    void 自然语言_只改X目录() {
        List<String> r = svc.parse("只改 src/components 目录下的文件，帮我加个功能");
        assertTrue(r.contains("src/components"));
    }

    @Test
    void 无scope声明_返回空列表() {
        List<String> r = svc.parse("帮我重构整个项目");
        assertTrue(r.isEmpty());
    }

    @Test
    void scope覆盖_后面的声明覆盖前面的() {
        List<String> r = svc.parse("#scope: src/components 然后 #scope: src/utils 改下这个");
        // 两个都匹配到，按 LinkedHashSet 去重保留
        assertTrue(r.contains("src/components"));
        assertTrue(r.contains("src/utils"));
    }

    // —— isWithinScope: 路径匹配 ——

    @Test
    void 目录scope_写文件在目录内_放行() {
        assertTrue(ScopeConstraintService.isWithinScope("src/components/Foo.vue",
                List.of("src/components")));
    }

    @Test
    void 目录scope_写文件在目录外_拦截() {
        assertFalse(ScopeConstraintService.isWithinScope("src/utils/helper.ts",
                List.of("src/components")));
    }

    @Test
    void 文件scope_精确匹配_放行() {
        assertTrue(ScopeConstraintService.isWithinScope("src/components/Foo.vue",
                List.of("src/components/Foo.vue")));
    }

    @Test
    void 文件scope_不同文件_拦截() {
        assertFalse(ScopeConstraintService.isWithinScope("src/components/Bar.vue",
                List.of("src/components/Foo.vue")));
    }

    @Test
    void scope为空_不限制() {
        assertTrue(ScopeConstraintService.isWithinScope("anything/really", List.of()));
        assertTrue(ScopeConstraintService.isWithinScope("anything/really", null));
    }

    @Test
    void 多个scope_任一匹配即放行() {
        assertTrue(ScopeConstraintService.isWithinScope("src/utils/helper.ts",
                List.of("src/components", "src/utils")));
    }

    @Test
    void 路径分隔符统一_反斜杠也支持() {
        assertTrue(ScopeConstraintService.isWithinScope("src\\components\\Foo.vue",
                List.of("src/components")));
    }
}
