package com.capsule.agentkey.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 内置 Node 运行时定位。
 *
 * <p>打包后由桌面壳通过 {@code LUNAR_MCP_NODE_HOME} 指向 {@code resources/node-runtime}；
 * 开发态回退到仓库下的 {@code build/node-runtime}。找不到则返回 null，
 * 调用方回退到系统命令（保持旧行为，不影响未打包环境的测试）。
 */
public final class McpNodeRuntime {

    private static final String ENV_HOME = "LUNAR_MCP_NODE_HOME";

    private static volatile boolean resolved;
    private static volatile Path home;

    private McpNodeRuntime() {
    }

    /** 内置 node 目录；没有内置运行时时返回 null */
    public static Path home() {
        if (!resolved) {
            synchronized (McpNodeRuntime.class) {
                if (!resolved) {
                    home = locate();
                    resolved = true;
                }
            }
        }
        return home;
    }

    /** node 可执行文件；没有内置运行时时返回 null */
    public static Path executable() {
        Path dir = home();
        if (dir == null) {
            return null;
        }
        Path candidate = isWindows() ? dir.resolve("node.exe") : dir.resolve("bin").resolve("node");
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        Path flat = dir.resolve("node");
        return Files.isRegularFile(flat) ? flat : null;
    }

    /** npm / npx 的 CLI 入口（node <cli.js> 方式执行，避免依赖 shell）；找不到返回 null */
    public static Path cli(String tool) {
        Path dir = home();
        if (dir == null) {
            return null;
        }
        String file = tool + "-cli.js";
        List<Path> candidates = List.of(
                dir.resolve("node_modules").resolve("npm").resolve("bin").resolve(file),
                dir.resolve("lib").resolve("node_modules").resolve("npm").resolve("bin").resolve(file));
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    /** npm 缓存目录：默认放应用数据目录，避免与用户全局缓存互相锁；未配置返回 null */
    public static String npmCacheDir() {
        String configured = System.getenv("LUNAR_MCP_NPM_CACHE");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("lunar.mcp.npm-cache");
        }
        return configured == null || configured.isBlank() ? null : configured;
    }

    /** 取命令的基础名：去路径、去扩展名、转小写（"C:\\x\\npx.cmd" → "npx"） */
    public static String baseName(String command) {
        if (command == null) {
            return "";
        }
        String name = command.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static Path locate() {
        String configured = System.getenv(ENV_HOME);
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("lunar.mcp.node-home");
        }
        if (configured != null && !configured.isBlank()) {
            Path dir = Path.of(configured);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        Path dev = Path.of(System.getProperty("user.dir", "."), "build", "node-runtime");
        return Files.isDirectory(dev) ? dev : null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
