package com.capsule.agentkey.agent.run;

import com.capsule.agentkey.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * 打包冒烟回归：PlanEvidenceService 同时有生产构造器和私有工厂构造器，
 * 必须能被 Spring 选中并注入 ProjectService，否则桌面侧车启动即失败。
 */
class PlanEvidenceServiceWiringTest {

    @Test
    void spring能实例化证据服务() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProjectService.class, () -> mock(ProjectService.class));
            context.register(PlanEvidenceService.class);
            context.refresh();

            assertNotNull(context.getBean(PlanEvidenceService.class));
        }
    }
}
