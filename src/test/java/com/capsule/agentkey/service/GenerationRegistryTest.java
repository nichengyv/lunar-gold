package com.capsule.agentkey.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6-06：断流与取消拆分后，登记表按会话保存多个 Run 的取消句柄。
 */
class GenerationRegistryTest {

    private final GenerationRegistry registry = new GenerationRegistry();

    @Test
    void 同一会话多个活跃Run会被一次停止全部取消() {
        AtomicBoolean first = new AtomicBoolean(false);
        AtomicBoolean second = new AtomicBoolean(false);
        registry.register(10L, first);
        registry.register(10L, second);

        assertTrue(registry.cancel(10L));
        assertTrue(first.get());
        assertTrue(second.get());
    }

    @Test
    void 旧Run收尾不会误删同会话新Run的标记() {
        AtomicBoolean oldFlag = new AtomicBoolean(false);
        AtomicBoolean newFlag = new AtomicBoolean(false);
        registry.register(10L, oldFlag);
        registry.register(10L, newFlag);

        registry.unregister(10L, oldFlag);

        assertTrue(registry.cancel(10L));
        assertFalse(oldFlag.get(), "已注销的旧句柄不应再被操作");
        assertTrue(newFlag.get(), "新 Run 的句柄必须保留");
    }

    @Test
    void 非同一句柄注销不会移除已有登记() {
        AtomicBoolean registered = new AtomicBoolean(false);
        registry.register(10L, registered);
        registry.unregister(10L, new AtomicBoolean(false));

        assertTrue(registry.cancel(10L));
        assertTrue(registered.get());
    }

    @Test
    void 无活跃Run与空参数都安全() {
        assertFalse(registry.cancel(10L));
        assertFalse(registry.cancel(null));
        assertDoesNotThrow(() -> {
            registry.register(null, new AtomicBoolean(false));
            registry.register(1L, null);
            registry.unregister(null, new AtomicBoolean(false));
            registry.unregister(1L, null);
        });
    }
}
