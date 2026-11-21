package com.capsule.agentkey.verify;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * R7c L5：没有显式 {@code verify.json}、也没有可识别构建文件时，对纯 Java 源码做有界全量编译。
 *
 * <p>背景：L3 真实模型冒烟里，裸 Java 工作区只能得到 {@code VERIFY_NOT_CONFIGURED}，
 * 完成判定退化为「模型自报 + 单文件诊断」。这里复用进程内 javac 做一次全量编译，
 * 让 L5「可信完成」在没有任何构建配置时也有工具证据。
 *
 * <p>诚实边界（任一不满足都不宣称通过）：
 * <ul>
 *   <li>只支持纯 Java；出现 Kotlin/Python/JS/TS/Go/Rust/C/C++ 等源码时要求显式配置；</li>
 *   <li>扫描有硬上限（深度/文件数/总字节），超限即降级，不能截取子集冒充全量；</li>
 *   <li>不跟随符号链接，不进入依赖与生成目录；</li>
 *   <li>编译只写独立临时目录，不污染工作区。</li>
 * </ul>
 */
final class JavaSourceVerifier {

    /** 结论里标注的自动来源，与构建文件探测的 {@code pom.xml} 等区分 */
    static final String SOURCE = "java-sources";

    /** 与构建命令同名风格的审计条目名 */
    static final String COMMAND_NAME = "javac-source-compile";

    /** 依赖、版本控制与生成目录：不扫描，避免把第三方源码当成工作区源码 */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", ".hg", ".svn", ".idea", ".gradle", ".mvn", ".lunargold", ".vscode",
            ".settings", ".tox", ".cache", ".next", ".nuxt", ".venv", "venv", "__pycache__",
            "target", "build", "out", "dist", "node_modules", "vendor", "coverage",
            "obj", "bin", "generated");

    /** 出现即视为混合语言工作区：裸 Java 编译无法覆盖这些源码 */
    private static final Set<String> FOREIGN_EXTENSIONS = Set.of(
            "kt", "kts", "py", "js", "jsx", "mjs", "cjs", "ts", "tsx",
            "go", "rs", "c", "cc", "cpp", "cxx", "h", "hpp", "cs",
            "rb", "php", "swift", "scala", "groovy", "dart", "m", "mm");

    /** 混合语言结论里最多列出的样例文件数，避免消息过长 */
    private static final int MAX_FOREIGN_SAMPLES = 5;

    private JavaSourceVerifier() {
    }

    /** 扫描上限；非正值回落到默认值，调用方无需要自己兜底 */
    record Limits(int maxDepth, int maxFiles, long maxBytes) {

        Limits {
            maxDepth = maxDepth > 0 ? maxDepth : 12;
            maxFiles = maxFiles > 0 ? maxFiles : 2000;
            maxBytes = maxBytes > 0 ? maxBytes : 10L * 1024 * 1024;
        }
    }

    /**
     * 扫描结果。
     *
     * @param javaFiles        全部 *.java（已规范化、排序）；为空表示没有可验证的 Java 源码
     * @param foreignSamples   其他语言源码样例（相对路径，最多 {@value #MAX_FOREIGN_SAMPLES} 条）
     * @param foreignCount     其他语言源码总数
     * @param truncated        是否因深度/文件数/字节超限而未完成全量扫描
     * @param truncationReason 超限原因；{@code truncated=false} 时为空
     */
    record Scan(List<Path> javaFiles, List<String> foreignSamples, int foreignCount,
                boolean truncated, String truncationReason) {
    }

    /**
     * 编译结果。
     *
     * @param status     映射后的验证状态
     * @param outcomes   审计条目；编译真正执行时才非空
     * @param message    面向用户的结论（不含自动来源标注）
     * @param configured 是否已定位到可验证的 Java 源码
     */
    record Result(VerifyResult.Status status, List<VerifyResult.CommandOutcome> outcomes,
                  String message, boolean configured) {
    }

    /** 有界扫描工作区；不跟随符号链接，异常向上抛由调用方降级为 UNAVAILABLE */
    static Scan scan(Path root, Limits limits, AtomicBoolean cancelled) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return new Scan(List.of(), List.of(), 0, false, null);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<Path> javaFiles = new ArrayList<>();
        Set<String> foreign = new LinkedHashSet<>();
        int[] foreignCount = {0};
        long[] totalBytes = {0};
        String[] truncation = {null};

        Files.walkFileTree(normalizedRoot, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (isCancelled(cancelled) || truncation[0] != null) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (dir.equals(normalizedRoot)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (attrs.isSymbolicLink()) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (EXCLUDED_DIRS.contains(fileName(dir))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        int depth = normalizedRoot.relativize(dir).getNameCount();
                        if (depth >= limits.maxDepth()) {
                            if (hasSourceContent(dir)) {
                                truncation[0] = "工作区源码目录深度超过上限 " + limits.maxDepth()
                                        + "：" + relativize(normalizedRoot, dir);
                            }
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (isCancelled(cancelled) || truncation[0] != null) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        String extension = extension(file);
                        if ("java".equals(extension)) {
                            if (javaFiles.size() >= limits.maxFiles()) {
                                truncation[0] = "Java 源码文件数超过上限 " + limits.maxFiles();
                                return FileVisitResult.TERMINATE;
                            }
                            totalBytes[0] += Math.max(0, attrs.size());
                            if (totalBytes[0] > limits.maxBytes()) {
                                truncation[0] = "Java 源码总字节数超过上限 " + limits.maxBytes();
                                return FileVisitResult.TERMINATE;
                            }
                            javaFiles.add(file.toAbsolutePath().normalize());
                        } else if (FOREIGN_EXTENSIONS.contains(extension)) {
                            foreignCount[0]++;
                            if (foreign.size() < MAX_FOREIGN_SAMPLES) {
                                foreign.add(relativize(normalizedRoot, file));
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // 单个文件无权限等不中断整体扫描；真正缺证据时后续编译会失败
                        return FileVisitResult.CONTINUE;
                    }
                });

        return new Scan(javaFiles.stream().sorted().toList(), List.copyOf(foreign),
                foreignCount[0], truncation[0] != null, truncation[0]);
    }

    /** 使用系统 javac 编译 {@link Scan#javaFiles()} 的全部文件 */
    static Result compile(Path root, Scan scan, int timeoutSeconds, int maxOutputChars,
                          AtomicBoolean cancelled) {
        return compile(root, scan, timeoutSeconds, maxOutputChars, cancelled,
                ToolProvider.getSystemJavaCompiler());
    }

    /** 编译器可注入，仅用于测试无 JDK 的降级路径 */
    static Result compile(Path root, Scan scan, int timeoutSeconds, int maxOutputChars,
                          AtomicBoolean cancelled, JavaCompiler compiler) {
        if (scan == null || scan.javaFiles().isEmpty()) {
            return new Result(VerifyResult.Status.NOT_CONFIGURED, List.of(), "未发现 Java 源码", false);
        }
        if (compiler == null) {
            return new Result(VerifyResult.Status.UNAVAILABLE, List.of(),
                    "当前运行环境没有 javac，无法编译纯 Java 源码", true);
        }
        if (isCancelled(cancelled)) {
            return new Result(VerifyResult.Status.UNAVAILABLE, List.of(), "验证已取消", true);
        }

        List<Path> files = scan.javaFiles();
        String command = "javac " + files.size() + " 个源文件";
        long timeoutMs = Math.max(500L, Math.max(1, timeoutSeconds) * 1000L);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "javac-source-verify");
            thread.setDaemon(true);
            return thread;
        });
        long startedAt = System.currentTimeMillis();
        Future<CompileOutcome> future = executor.submit(() -> doCompile(files, compiler));
        try {
            CompileOutcome outcome = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (isCancelled(cancelled)) {
                return new Result(VerifyResult.Status.UNAVAILABLE, List.of(), "验证已取消", true);
            }
            VerifyResult.CommandOutcome entry = new VerifyResult.CommandOutcome(
                    COMMAND_NAME, command, "", true, outcome.ok() ? 0 : 1, false,
                    System.currentTimeMillis() - startedAt, false,
                    truncate(outcome.output(), maxOutputChars));
            if (outcome.ok()) {
                return new Result(VerifyResult.Status.PASSED, List.of(entry),
                        "纯 Java 源码全量编译通过（" + files.size() + " 个文件）", true);
            }
            return new Result(VerifyResult.Status.FAILED, List.of(entry),
                    "纯 Java 源码编译失败（" + files.size() + " 个文件，"
                            + outcome.errorCount() + " 处错误）", true);
        } catch (TimeoutException e) {
            future.cancel(true);
            VerifyResult.CommandOutcome entry = new VerifyResult.CommandOutcome(
                    COMMAND_NAME, command, "", true, -1, true,
                    System.currentTimeMillis() - startedAt, false,
                    "裸 Java 源码编译超时（" + timeoutMs + "ms）");
            return new Result(VerifyResult.Status.UNAVAILABLE, List.of(entry),
                    "纯 Java 源码编译超时（" + timeoutMs + "ms）", true);
        } catch (Exception e) {
            future.cancel(true);
            return new Result(VerifyResult.Status.UNAVAILABLE, List.of(),
                    "纯 Java 源码编译无法完成：" + e.getMessage(), true);
        } finally {
            executor.shutdownNow();
        }
    }

    private static CompileOutcome doCompile(List<Path> files, JavaCompiler compiler) throws Exception {
        Path out = Files.createTempDirectory("lunargold-java-source-");
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(files);
            // 显式传入全部文件，不设置 -sourcepath：避免隐式再发现未纳入扫描的源码
            List<String> options = List.of(
                    "-proc:none", "-encoding", "UTF-8", "-d", out.toString());
            StringWriter writer = new StringWriter();
            Boolean ok = compiler.getTask(writer, fileManager, collector, options, null, units).call();

            StringBuilder output = new StringBuilder(writer.toString());
            int errors = 0;
            for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errors++;
                }
                output.append(format(diagnostic)).append('\n');
            }
            boolean passed = Boolean.TRUE.equals(ok) && errors == 0;
            return new CompileOutcome(passed, output.toString().trim(), errors);
        } finally {
            deleteQuietly(out);
        }
    }

    private static String format(Diagnostic<? extends JavaFileObject> diagnostic) {
        String source = diagnostic.getSource() == null ? "" : diagnostic.getSource().getName();
        String message = diagnostic.getMessage(Locale.ROOT);
        return source + ":" + Math.max(1, diagnostic.getLineNumber()) + ": "
                + diagnostic.getKind().toString().toLowerCase(Locale.ROOT) + ": "
                + (message == null ? "" : message.replace('\n', ' ').replace('\r', ' '));
    }

    /** 输出只做展示截断，不影响参与编译的文件集合 */
    private static String truncate(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int limit = Math.max(200, maxChars);
        if (text.length() <= limit) {
            return text;
        }
        String marker = "\n…（输出已截断）";
        return text.substring(0, Math.max(0, limit - marker.length())) + marker;
    }

    /** 深度边界上的目录是否还有源码；有则说明扫描不完整，必须降级而不是宣称通过 */
    private static boolean hasSourceContent(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.anyMatch(child -> {
                if (Files.isSymbolicLink(child)) {
                    return false;
                }
                if (Files.isDirectory(child)) {
                    return !EXCLUDED_DIRS.contains(fileName(child));
                }
                String extension = extension(child);
                return "java".equals(extension) || FOREIGN_EXTENSIONS.contains(extension);
            });
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static String extension(Path file) {
        String name = fileName(file);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String relativize(Path root, Path path) {
        try {
            return root.relativize(path).toString().replace('\\', '/');
        } catch (Exception e) {
            return fileName(path);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 临时目录清理失败不影响结论
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }

    private record CompileOutcome(boolean ok, String output, int errorCount) {
    }
}
