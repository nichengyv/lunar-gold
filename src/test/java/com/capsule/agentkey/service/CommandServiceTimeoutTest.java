package com.capsule.agentkey.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-07：命令超时与进程树终止。
 *
 * 用一个自编译的 Java 睡眠进程模拟 "mvn/npm 派生子进程" 的真实场景：
 * 父进程再拉起一个子进程，命令行超时后必须把两代进程一起杀掉，不能留孤儿。
 */
class CommandServiceTimeoutTest {

    @TempDir
    Path workDir;

    @Test
    void 超时后整棵进程树被终止() throws Exception {
        Files.writeString(workDir.resolve("Sleeper.java"), """
                import java.nio.file.Files;
                import java.nio.file.Path;

                public class Sleeper {
                    public static void main(String[] args) throws Exception {
                        if (args.length == 0) {
                            // 父进程：拉起一个同样长睡的子进程，并把它的 pid 记下来
                            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                            Process child = new ProcessBuilder(java, "-cp", ".", "Sleeper", "child")
                                    .redirectErrorStream(true)
                                    .start();
                            Files.writeString(Path.of("child.pid"), String.valueOf(child.pid()));
                        } else {
                            // 子进程：稍等后写入标记，证明它确实启动成功并活到了被杀之前
                            Thread.sleep(300);
                            Files.writeString(Path.of("child.started"), "ok");
                        }
                        Thread.sleep(120_000);
                    }
                }
                """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "该测试需要 JDK 运行（而不是 JRE）");
        assertEquals(0, compiler.run(null, null, null, workDir.resolve("Sleeper.java").toString()),
                "测试用 Sleeper 应能编译通过");

        CommandService service = newService();
        long start = System.currentTimeMillis();
        CommandService.CommandResult result = service.run(
                workDir.toString(), "java -cp . Sleeper", null, 3, new AtomicBoolean());
        long cost = System.currentTimeMillis() - start;

        assertTrue(result.timedOut(), "3 秒后应判定为超时");
        assertTrue(cost < 30_000, "超时后应及时返回，实际耗时 " + cost + "ms");
        assertTrue(Files.exists(workDir.resolve("child.started")),
                "子进程应真实启动过，否则该用例没有验证到进程树");

        long childPid = Long.parseLong(Files.readString(workDir.resolve("child.pid")).trim());
        assertTrue(waitUntilDead(childPid), "超时终止必须覆盖子进程，不能留下孤儿 pid=" + childPid);
    }

    private CommandService newService() throws Exception {
        WorkspaceFileService workspace = new WorkspaceFileService();
        CommandService service = new CommandService(workspace);
        set(service, "enabled", true);
        set(service, "allowedRaw", "java,javac");
        // 测试 JVM 一定有 java.home，显式指定避免依赖外部 JAVA_HOME/PATH
        set(service, "javaHomeRaw", System.getProperty("java.home"));
        set(service, "defaultTimeoutSeconds", 60);
        set(service, "maxTimeoutSeconds", 300);
        return service;
    }

    private static void set(CommandService target, String field, Object value) throws Exception {
        Field f = CommandService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static boolean waitUntilDead(long pid) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
