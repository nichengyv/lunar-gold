package com.capsule.agentkey.verify;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7c L5：裸 Java 源码有界全量编译的行为与诚实边界。
 */
class JavaSourceVerifierTest {

    private static final JavaSourceVerifier.Limits LIMITS =
            new JavaSourceVerifier.Limits(12, 2000, 10L * 1024 * 1024);

    @TempDir
    Path workspace;

    @Test
    void 多包多文件相互引用且含中文内容时编译通过() throws Exception {
        Path src = workspace.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), """
                package demo;
                // 中文注释不应影响编译
                public class App {
                    public static void main(String[] args) {
                        System.out.println(Helper.msg());
                    }
                }
                """);
        Files.writeString(src.resolve("Helper.java"), """
                package demo;
                public final class Helper {
                    public static String msg() { return "中文内容"; }
                }
                """);

        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(workspace, LIMITS, new AtomicBoolean(false));
        assertEquals(2, scan.javaFiles().size());
        assertFalse(scan.truncated());

        JavaSourceVerifier.Result result = JavaSourceVerifier.compile(
                workspace, scan, 120, 4000, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.PASSED, result.status());
        assertTrue(result.configured());
        assertEquals(1, result.outcomes().size());
        assertEquals(JavaSourceVerifier.COMMAND_NAME, result.outcomes().get(0).name());
        assertEquals(0, result.outcomes().get(0).exitCode());
        assertTrue(result.message().contains("2 个文件"), result.message());
        try (Stream<Path> walk = Files.walk(workspace)) {
            assertTrue(walk.noneMatch(p -> p.toString().endsWith(".class")),
                    "编译产物只能写临时目录，不能污染工作区");
        }
    }

    @Test
    void 任一文件编译错误时整体失败() throws Exception {
        Path src = workspace.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Good.java"), """
                package demo;
                public class Good { }
                """);
        Files.writeString(src.resolve("Broken.java"), """
                package demo;
                public class Broken {
                    void m() { int value = ; }
                }
                """);

        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(workspace, LIMITS, new AtomicBoolean(false));
        JavaSourceVerifier.Result result = JavaSourceVerifier.compile(
                workspace, scan, 120, 4000, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.FAILED, result.status());
        assertTrue(result.configured());
        VerifyResult.CommandOutcome outcome = result.outcomes().get(0);
        assertEquals(1, outcome.exitCode());
        assertFalse(outcome.timedOut());
        assertTrue(outcome.outputTail().contains("error"), outcome.outputTail());
        assertTrue(result.message().contains("处错误"), result.message());
    }

    @Test
    void 混合语言工作区被识别为不可全量验证() throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/demo"));
        Files.writeString(workspace.resolve("src/main/java/demo/App.java"),
                "package demo; public class App { }");
        Files.writeString(workspace.resolve("tool.py"), "print('hi')");

        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(workspace, LIMITS, new AtomicBoolean(false));

        assertEquals(1, scan.javaFiles().size());
        assertEquals(1, scan.foreignCount());
        assertTrue(scan.foreignSamples().contains("tool.py"), scan.foreignSamples().toString());
    }

    @Test
    void 依赖与生成目录不参与扫描() throws Exception {
        Path generated = workspace.resolve("node_modules/lib");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("Vendor.java"), "public class Vendor { }");
        Path target = workspace.resolve("target/classes");
        Files.createDirectories(target);
        Files.writeString(target.resolve("Stale.java"), "public class Stale { }");
        Path own = workspace.resolve("src/main/java/demo");
        Files.createDirectories(own);
        Files.writeString(own.resolve("App.java"), "package demo; public class App { }");

        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(workspace, LIMITS, new AtomicBoolean(false));

        assertEquals(1, scan.javaFiles().size());
        assertTrue(scan.javaFiles().get(0).toString().replace('\\', '/').endsWith("src/main/java/demo/App.java"));
    }

    @Test
    void 超过文件数上限时标记扫描不完整() throws Exception {
        Path src = workspace.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "public class A { }");
        Files.writeString(src.resolve("B.java"), "public class B { }");

        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(
                workspace, new JavaSourceVerifier.Limits(12, 1, 1024 * 1024), new AtomicBoolean(false));

        assertTrue(scan.truncated());
        assertTrue(scan.truncationReason().contains("文件数"), scan.truncationReason());
    }

    @Test
    void 超过字节上限时标记扫描不完整() throws Exception {
        Path src = workspace.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Big.java"),
                "public class Big { String s = \"" + "x".repeat(400) + "\"; }");

        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(
                workspace, new JavaSourceVerifier.Limits(12, 100, 100), new AtomicBoolean(false));

        assertTrue(scan.truncated());
        assertTrue(scan.truncationReason().contains("字节"), scan.truncationReason());
    }

    @Test
    void 超过深度上限且仍有源码时标记扫描不完整() throws Exception {
        Path deep = workspace.resolve("a/b/c/d/e");
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("Deep.java"), "public class Deep { }");

        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(
                workspace, new JavaSourceVerifier.Limits(3, 100, 1024 * 1024), new AtomicBoolean(false));

        assertTrue(scan.truncated());
        assertTrue(scan.truncationReason().contains("深度"), scan.truncationReason());
        assertTrue(scan.javaFiles().isEmpty());
    }

    @Test
    void 不跟随符号链接目录() throws Exception {
        Path outside = Files.createTempDirectory("lunargold-outside-");
        try {
            Files.writeString(outside.resolve("Outside.java"), "public class Outside { }");
            Path link = workspace.resolve("linked");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (Exception e) {
                Assumptions.assumeTrue(false, "当前环境不支持创建符号链接：" + e.getMessage());
            }

            JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(workspace, LIMITS, new AtomicBoolean(false));

            assertTrue(scan.javaFiles().isEmpty(), "符号链接目录不能被当作工作区源码扫描");
        } finally {
            try (Stream<Path> walk = Files.walk(outside)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 清理失败不影响断言
                    }
                });
            }
        }
    }

    @Test
    void 没有系统编译器时降级为不可用() throws Exception {
        Path src = workspace.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "public class App { }");
        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(workspace, LIMITS, new AtomicBoolean(false));

        JavaSourceVerifier.Result result = JavaSourceVerifier.compile(
                workspace, scan, 60, 4000, new AtomicBoolean(false), null);

        assertEquals(VerifyResult.Status.UNAVAILABLE, result.status());
        assertTrue(result.message().contains("javac"), result.message());
        assertTrue(result.outcomes().isEmpty());
    }

    @Test
    void 取消标记使编译直接降级为不可用() throws Exception {
        Path src = workspace.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "public class App { }");
        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(workspace, LIMITS, new AtomicBoolean(false));

        JavaSourceVerifier.Result result = JavaSourceVerifier.compile(
                workspace, scan, 60, 4000, new AtomicBoolean(true));

        assertEquals(VerifyResult.Status.UNAVAILABLE, result.status());
        assertTrue(result.message().contains("取消"), result.message());
    }

    @Test
    void 输出只截断展示但编译文件集合不裁剪() throws Exception {
        Path src = workspace.resolve("src");
        Files.createDirectories(src);
        StringBuilder broken = new StringBuilder("public class Broken {\n");
        for (int i = 0; i < 40; i++) {
            broken.append("    void m").append(i).append("() { int value").append(i).append(" = ; }\n");
        }
        broken.append("}\n");
        Files.writeString(src.resolve("Broken.java"), broken.toString());
        Files.writeString(src.resolve("Fine.java"), "public class Fine { }");

        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(workspace, LIMITS, new AtomicBoolean(false));
        assertEquals(2, scan.javaFiles().size());

        JavaSourceVerifier.Result result = JavaSourceVerifier.compile(
                workspace, scan, 120, 250, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.FAILED, result.status());
        String output = result.outcomes().get(0).outputTail();
        assertTrue(output.length() <= 250, "展示输出必须截断到配置上限");
        assertTrue(output.endsWith("（输出已截断）"), output);
        assertTrue(result.message().contains("2 个文件"), result.message());
    }

    @Test
    void 非源码文件不触发混合语言降级() throws Exception {
        Path src = workspace.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "public class App { }");
        Files.writeString(workspace.resolve("README.md"), "# 文档");
        Files.writeString(workspace.resolve("config.json"), "{}");

        JavaSourceVerifier.Scan scan = JavaSourceVerifier.scan(workspace, LIMITS, new AtomicBoolean(false));

        assertEquals(1, scan.javaFiles().size());
        assertEquals(0, scan.foreignCount());
    }
}
