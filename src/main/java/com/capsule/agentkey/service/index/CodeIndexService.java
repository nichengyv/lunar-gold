package com.capsule.agentkey.service.index;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * P5-04：工作区代码索引。
 *
 * <p>扫描工作区并抽取文件符号，按工作区维护内存增量缓存：文件大小与修改时间未变
 * 时直接复用上次结果，只重扫新增/变更文件，删除的文件从缓存移除。统计信息
 * （扫描数 / 复用数 / 移除数 / 符号数）用于量化增量收益。</p>
 *
 * <p>不建持久索引：桌面端 {@code code_index} 表保持可选，当前收益主要来自
 * 同一会话内反复构建 repo map 与符号查询时的开销下降。</p>
 */
@Service
@Slf4j
public class CodeIndexService {

    /** 依赖、构建产物与版本库目录；与 @ 引用搜索的跳过规则保持一致 */
    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", ".idea", ".gradle", ".mvn", ".vscode", "node_modules", "target", "build",
            "dist", "out", "release", "logs", "coverage", ".next", "bin", "obj", "vendor",
            "__pycache__", ".venv", "venv", ".tmp");

    /** 值得索引的文本文件；二进制、压缩包和媒体文件直接忽略 */
    private static final Set<String> SCANNED_EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "groovy", "py", "js", "jsx", "ts", "tsx", "vue",
            "html", "htm", "css", "scss", "less", "go", "rs", "c", "h", "cpp", "hpp", "cc",
            "cs", "php", "rb", "swift", "m", "mm", "sh", "ps1", "sql", "proto", "gradle",
            "properties", "yaml", "yml", "json", "xml", "md", "markdown", "txt", "toml",
            "ini", "conf", "cfg", "env");

    private static final Set<String> SPECIAL_FILE_NAMES = Set.of(
            "dockerfile", "makefile", "jenkinsfile", "procfile");

    /** 默认只读取 256KB 以内的源码；更大的生成文件只索引路径 */
    private static final long DEFAULT_MAX_READ_BYTES = 256 * 1024;

    private final CodeIndexer codeIndexer;

    /** 工作区根路径 -> 文件相对路径 -> 上次索引结果 */
    private final Map<String, Map<String, CachedSymbols>> caches = new ConcurrentHashMap<>();

    /** 同一工作区的扫描串行化，避免并发请求互相覆盖缓存 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public CodeIndexService(CodeIndexer codeIndexer) {
        this.codeIndexer = codeIndexer;
    }

    /** 扫描参数；repo map 的深度/文件数预算由调用方决定 */
    public record ScanOptions(int maxDepth, int maxFiles, long maxFileBytes) {

        public static ScanOptions defaults() {
            return new ScanOptions(8, 500, DEFAULT_MAX_READ_BYTES);
        }

        public static ScanOptions of(int maxDepth, int maxFiles) {
            return new ScanOptions(maxDepth, maxFiles, DEFAULT_MAX_READ_BYTES);
        }
    }

    /** 带符号的文件索引项，path 为相对工作区根目录的 POSIX 风格路径 */
    public record IndexedFile(String path, List<CodeSymbol> symbols) {}

    /**
     * 一次扫描的统计。
     *
     * @param scannedFiles 本次重新读取并解析的文件数
     * @param reusedFiles  命中缓存直接复用的文件数
     * @param removedFiles 已从工作区删除、本次清理出缓存的文件数
     * @param symbolCount  本次索引包含的符号总数
     */
    public record IndexStats(int scannedFiles, int reusedFiles, int removedFiles, int symbolCount) {}

    /** 一次工作区快照；文件按路径排序，保证输出确定 */
    public record WorkspaceIndex(List<IndexedFile> files, IndexStats stats, boolean limited) {

        public static WorkspaceIndex empty() {
            return new WorkspaceIndex(List.of(), new IndexStats(0, 0, 0, 0), false);
        }
    }

    /** 符号查询命中项 */
    public record SymbolMatch(String path, CodeSymbol symbol) {}

    /**
     * 构建（或增量更新）工作区快照。
     *
     * @param workspaceRoot 工作区绝对路径；为空或不可读时返回空快照
     */
    public WorkspaceIndex snapshot(String workspaceRoot, ScanOptions options) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            return WorkspaceIndex.empty();
        }
        Path root;
        try {
            root = Path.of(workspaceRoot).toAbsolutePath().normalize();
        } catch (Exception e) {
            log.warn("代码索引路径无效: {}", e.getMessage());
            return WorkspaceIndex.empty();
        }
        if (!Files.isDirectory(root)) {
            return WorkspaceIndex.empty();
        }

        ScanOptions effective = options == null ? ScanOptions.defaults() : options;
        int maxDepth = Math.max(1, effective.maxDepth());
        int maxFiles = Math.max(1, effective.maxFiles());
        long maxFileBytes = Math.max(0, effective.maxFileBytes());

        String key = root.toString();
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            Map<String, CachedSymbols> cache = caches.computeIfAbsent(key, k -> new HashMap<>());
            List<IndexedFile> files = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean[] limited = {false};
            int[] scanned = {0};
            int[] reused = {0};

            Files.walkFileTree(root, Set.of(), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".") || SKIPPED_DIRS.contains(name.toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString();
                    if (name.startsWith(".") || !isScannedFile(name)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (files.size() >= maxFiles) {
                        limited[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    seen.add(relative);

                    long size = attrs.size();
                    FileTime modified = attrs.lastModifiedTime();
                    CachedSymbols cached = cache.get(relative);
                    List<CodeSymbol> symbols;
                    if (cached != null && cached.matches(size, modified, maxFileBytes)) {
                        symbols = cached.symbols();
                        reused[0]++;
                    } else {
                        symbols = extractSymbols(file, name, size, maxFileBytes);
                        cache.put(relative, new CachedSymbols(size, modified, maxFileBytes, symbols));
                        scanned[0]++;
                    }
                    files.add(new IndexedFile(relative, symbols));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 权限不足或文件被占用不能中断整次索引
                    return FileVisitResult.CONTINUE;
                }
            });

            int removed = purgeMissing(cache, seen);
            files.sort(Comparator.comparing(IndexedFile::path));
            int symbolCount = files.stream().mapToInt(file -> file.symbols().size()).sum();
            return new WorkspaceIndex(
                    List.copyOf(files),
                    new IndexStats(scanned[0], reused[0], removed, symbolCount),
                    limited[0]);
        } catch (Exception e) {
            log.warn("代码索引扫描失败: {}", e.getMessage());
            return WorkspaceIndex.empty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按符号名做大小写不敏感的子串查询，供后续检索类工具复用。
     *
     * @param limit 最大命中数；小于 1 时返回空列表
     */
    public List<SymbolMatch> findSymbols(String workspaceRoot, String query, int limit) {
        if (query == null || query.isBlank() || limit < 1) {
            return List.of();
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return snapshot(workspaceRoot, ScanOptions.defaults()).files().stream()
                .flatMap(file -> file.symbols().stream()
                        .map(symbol -> new SymbolMatch(file.path(), symbol)))
                .filter(match -> match.symbol().name().toLowerCase(Locale.ROOT).contains(needle))
                .limit(limit)
                .toList();
    }

    private List<CodeSymbol> extractSymbols(Path file, String name, long size, long maxFileBytes) {
        if (!codeIndexer.supports(extension(name)) || size > maxFileBytes) {
            return List.of();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return List.copyOf(codeIndexer.index(name, content));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static int purgeMissing(Map<String, CachedSymbols> cache, Set<String> seen) {
        int removed = 0;
        Iterator<String> iterator = cache.keySet().iterator();
        while (iterator.hasNext()) {
            if (!seen.contains(iterator.next())) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private static boolean isScannedFile(String name) {
        String ext = extension(name);
        if (!ext.isEmpty() && SCANNED_EXTENSIONS.contains(ext)) {
            return true;
        }
        return SPECIAL_FILE_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private record CachedSymbols(long size, FileTime modified, long maxFileBytes, List<CodeSymbol> symbols) {

        private boolean matches(long size, FileTime modified, long maxFileBytes) {
            return this.size == size
                    && this.maxFileBytes == maxFileBytes
                    && this.modified.equals(modified);
        }
    }
}
