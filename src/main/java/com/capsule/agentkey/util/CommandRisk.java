package com.capsule.agentkey.util;

/**
 * 命令破坏性判定。
 *
 * 用途：自动审批档位下不是「所有命令都要问」，而是只拦真正可能造成不可逆损失的命令
 * （递归删除、格式化、强推、硬重置等）；普通构建/脚本/查看命令直接放行。
 *
 * 注意：这里只做「是否需要人工确认」的软判定，硬性白名单仍在 CommandService。
 */
public final class CommandRisk {

    private CommandRisk() {
    }

    public static boolean isDestructive(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String c = command.trim().toLowerCase();

        // 递归删除才算破坏性；普通 rm file.txt 不弹确认
        if (c.startsWith("rm ") && (c.contains(" -r") || c.contains(" -rf") || c.contains(" -fr")
                || c.contains("--recursive"))) {
            return true;
        }
        // Windows 内建删除命令（若通过异常方式拿到）
        if ((c.startsWith("del ") || c.startsWith("erase ")) && c.contains("/s")) {
            return true;
        }
        if ((c.startsWith("rd ") || c.startsWith("rmdir ")) && c.contains("/s")) {
            return true;
        }
        // 磁盘/系统级操作
        if (c.startsWith("format ") || c.startsWith("mkfs") || c.startsWith("diskpart")
                || c.startsWith("shutdown") || c.startsWith("reboot")) {
            return true;
        }
        // git 不可逆操作（CommandService 已有硬拦截，这里作为软层的双保险）
        if (c.startsWith("git ")) {
            if (c.contains("push") && (c.contains("--force") || c.contains(" -f"))) return true;
            if (c.contains("reset") && c.contains("--hard")) return true;
            if (c.contains("clean")) return true;
        }
        // 数据库破坏性操作
        return c.contains("drop database") || c.contains("drop table") || c.contains("truncate table");
    }
}
