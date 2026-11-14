package com.capsule.agentkey.diagnostics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * P5-05：进程内 javac 诊断，默认 provider，离线可用。
 *
 * <p>只编译本批变更的 Java 文件；源码路径与工作区已构建的 class 目录一并加入，
 * 尽量避免把“依赖类还没编译”误报成变更文件的错误。编译超时或编译器缺失时降级，
 * 不阻塞 ReAct 主循环。
 */
@Component
@Slf4j
public class JavacDiagnosticsProvider implements DiagnosticsProvider {

    private static final String NAME = "javac";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(Path file) {
        return file != null && file.getFileName() != null
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
    }

    @Override
    public ProviderResult diagnose(Path workspaceRoot, List<Path> files, long timeoutMs) {
        if (workspaceRoot == null || files == null || files.isEmpty()) {
            return ProviderResult.of(List.of());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            log.warn("当前运行环境没有 javac，诊断降级为不可用");
            return ProviderResult.unavailable();
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<Path> targets = files.stream()
                .filter(this::supports)
                .map(p -> p.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .distinct()
                .toList();
        if (targets.isEmpty()) {
            return ProviderResult.of(List.of());
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "javac-diagnostics");
            t.setDaemon(true);
            return t;
        });
        Future<ProviderResult> future = executor.submit(() -> compile(compiler, root, targets));
        try {
            return future.get(Math.max(500, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.warn("javac 诊断超时（{}ms），降级为不可用", timeoutMs);
            return ProviderResult.unavailable();
        } catch (Exception e) {
            log.warn("javac 诊断失败，降级为不可用: {}", e.getMessage());
            return ProviderResult.unavailable();
        } finally {
            executor.shutdownNow();
        }
    }

    private ProviderResult compile(JavaCompiler compiler, Path root, List<Path> targets) throws Exception {
        Path out = Files.createTempDirectory("lunargold-javac-");
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(targets);
            List<String> options = new ArrayList<>();
            options.add("-proc:none");
            options.add("-d");
            options.add(out.toString());

            String sourcePath = buildSourcePath(root, targets);
            if (!sourcePath.isBlank()) {
                options.add("-sourcepath");
                options.add(sourcePath);
            }
            String classPath = buildClassPath(root);
            if (!classPath.isBlank()) {
                options.add("-classpath");
                options.add(classPath);
            }

            StringWriter output = new StringWriter();
            Boolean ok = compiler.getTask(output, fileManager, collector, options, null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                log.debug("javac 返回编译失败，诊断条数: {}", collector.getDiagnostics().size());
            }
            return ProviderResult.of(mapDiagnostics(root, targets, collector.getDiagnostics()));
        } finally {
            deleteQuietly(out);
        }
    }

    private List<Diagnostic> mapDiagnostics(Path root, List<Path> targets,
                                            List<javax.tools.Diagnostic<? extends JavaFileObject>> raw) {
        Set<String> targetPaths = targets.stream()
                .map(p -> p.toString().replace('\\', '/'))
                .collect(Collectors.toCollection(HashSet::new));
        List<Diagnostic> result = new ArrayList<>();
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : raw) {
            if (d.getSource() == null) {
                continue;
            }
            String sourceName = Paths.get(d.getSource().getName()).toAbsolutePath().normalize()
                    .toString().replace('\\', '/');
            if (!targetPaths.contains(sourceName)) {
                // 依赖源码的错误不算本次变更；避免把无关错误回灌给模型
                continue;
            }
            Diagnostic.Severity severity = switch (d.getKind()) {
                case ERROR -> Diagnostic.Severity.ERROR;
                case WARNING, MANDATORY_WARNING -> Diagnostic.Severity.WARNING;
                default -> Diagnostic.Severity.INFO;
            };
            String file = relativize(root, Paths.get(sourceName));
            result.add(new Diagnostic(severity, file,
                    (int) Math.max(1, d.getLineNumber()),
                    (int) Math.max(1, d.getColumnNumber()),
                    d.getMessage(Locale.ROOT), NAME));
        }
        return result;
    }

    private String buildSourcePath(Path root, List<Path> targets) {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        dirs.add(root.resolve("src/main/java"));
        dirs.add(root.resolve("src/test/java"));
        for (Path file : targets) {
            Path parent = file.getParent();
            for (int i = 0; i < 8 && parent != null; i++, parent = parent.getParent()) {
                Path grand = parent.getParent();
                String name = parent.getFileName() == null ? "" : parent.getFileName().toString();
                String grandName = grand == null || grand.getFileName() == null
                        ? "" : grand.getFileName().toString();
                if ("java".equals(name) && ("main".equals(grandName) || "test".equals(grandName))) {
                    dirs.add(parent);
                    break;
                }
            }
        }
        return dirs.stream()
                .filter(Files::isDirectory)
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private String buildClassPath(Path root) {
        List<Path> entries = new ArrayList<>();
        entries.add(root.resolve("target/classes"));
        entries.add(root.resolve("target/test-classes"));
        entries.add(root.resolve("build/classes/java/main"));
        entries.add(root.resolve("build/classes/java/test"));
        Path lib = root.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (var stream = Files.list(lib)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .forEach(entries::add);
            } catch (Exception e) {
                log.debug("读取工作区 lib 失败，忽略: {}", e.getMessage());
            }
        }
        return entries.stream()
                .filter(Files::exists)
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private String relativize(Path root, Path absolute) {
        try {
            return root.relativize(absolute).toString().replace('\\', '/');
        } catch (Exception e) {
            return absolute.getFileName() == null ? absolute.toString() : absolute.getFileName().toString();
        }
    }

    private void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 临时目录清理失败不影响诊断结果
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
