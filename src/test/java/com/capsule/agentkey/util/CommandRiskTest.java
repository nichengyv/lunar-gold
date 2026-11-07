package com.capsule.agentkey.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRiskTest {

    @Test
    void 普通构建查看与单文件操作不应判为破坏性() {
        assertFalse(CommandRisk.isDestructive(null));
        assertFalse(CommandRisk.isDestructive("mvn test"));
        assertFalse(CommandRisk.isDestructive("git status"));
        assertFalse(CommandRisk.isDestructive("git push origin main"));
        assertFalse(CommandRisk.isDestructive("rm file.txt"));
    }

    @Test
    void 递归删除必须判为破坏性() {
        assertTrue(CommandRisk.isDestructive("rm -rf build"));
        assertTrue(CommandRisk.isDestructive("rm -r build"));
        assertTrue(CommandRisk.isDestructive("del /s build"));
        assertTrue(CommandRisk.isDestructive("rmdir /s build"));
    }

    @Test
    void 磁盘与系统级操作必须判为破坏性() {
        assertTrue(CommandRisk.isDestructive("format C:"));
        assertTrue(CommandRisk.isDestructive("mkfs.ext4 /dev/sda"));
        assertTrue(CommandRisk.isDestructive("diskpart"));
        assertTrue(CommandRisk.isDestructive("shutdown /s"));
        assertTrue(CommandRisk.isDestructive("reboot"));
    }

    @Test
    void Git不可逆操作必须判为破坏性() {
        assertTrue(CommandRisk.isDestructive("git push origin main --force"));
        assertTrue(CommandRisk.isDestructive("git push origin main -f"));
        assertTrue(CommandRisk.isDestructive("git reset --hard HEAD~1"));
        assertTrue(CommandRisk.isDestructive("git clean -fd"));
    }

    @Test
    void 数据库删除与清空操作必须判为破坏性() {
        assertTrue(CommandRisk.isDestructive("psql -c 'drop database app'"));
        assertTrue(CommandRisk.isDestructive("psql -c 'drop table users'"));
        assertTrue(CommandRisk.isDestructive("psql -c 'truncate table users'"));
    }
}
