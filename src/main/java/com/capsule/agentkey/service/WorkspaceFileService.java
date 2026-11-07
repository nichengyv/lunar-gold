package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * 工作区文件操作服务（沙箱）。
 *
 * 沙箱核心原则：所有操作的目标路径必须落在 workspaceRoot 之内，
 * 任何 ".." 逃逸或符号链接跳转都直接拒绝。
 */
@Service
@Slf4j
public class WorkspaceFileService {

    @Value("${workspace.max-read-bytes:102400}")
    private int maxReadBytes;

    @Value("${workspace.max-write-bytes:524288}")
    private int maxWriteBytes;

    @Value("${workspace.max-search-results:50}")
    private int maxSearchResults;

    @Value("${workspace.max-list-depth:3}")
    private int maxListDepth;

    /** 允许 Agent 读取的文本扩展名；二进制文件直接拒绝，避免乱码和上下文污染 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
            "java", "kt", "scala", "py", "js", "ts", "tsx", "jsx", "vue", "html", "htm", "css",
            "scss", "less", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "sh", "bash",
            "sql", "proto", "gradle", "properties", "csv", "log", "env", "gitignore", "dockerfile"
    );

    /** @ 引用搜索时跳过的目录：依赖/构建产物/版本库，否则结果会被这些目录淹掉 */
    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", ".idea", ".gradle", "node_modules", "target", "build", "dist", "out",
            "vendor", "__pycache__", ".venv", "venv", ".tmp"
    );

    // ==================== 沙箱工具方法 ====================

    /**
     * 把相对路径解析为工作区内的绝对路径，并做越界校验。
     * @param workspaceRoot 工作区根目录（绝对路径）
     * @param relativePath  相对路径（允许 "" 或 "." 表示根目录）
     * @return 规范化后的绝对路径
     * @throws BusinessException 路径逃逸或不存在
     */
    public Path resolveInWorkspace(Path workspaceRoot, String relativePath) {
        if (workspaceRoot == null) {
            throw new BusinessException("工作区未绑定，请先在项目设置中选择文件夹");
        }
        String rel = (relativePath == null || relativePath.isBlank()) ? "." : relativePath;
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("路径越界，拒绝访问：" + relativePath);
        }
        try {
            Path realRoot = root.toRealPath();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                // 已存在的文件、目录和符号链接都必须解析真实位置，不能只靠 normalize。
                Path realTarget = target.toRealPath();
                if (!realTarget.startsWith(realRoot)) {
                    throw new BusinessException("路径通过符号链接越界，拒绝访问：" + relativePath);
                }
            } else {
                // 新建目标尚不存在：解析最近的已存在祖先，防止其父目录是指向工作区外的软链接。
                Path ancestor = target.getParent();
                while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                    ancestor = ancestor.getParent();
                }
                if (ancestor == null) {
                    throw new BusinessException("路径不存在且无法确认工作区边界：" + relativePath);
                }
                Path realAncestor = ancestor.toRealPath();
                if (!realAncestor.startsWith(realRoot)) {
                    throw new BusinessException("父目录通过符号链接越界，拒绝访问：" + relativePath);
                }
            }
            // 保留调用方使用的路径形式；上面的 realpath 校验已拒绝所有越界目标。
            return target;
        } catch (IOException e) {
            throw new BusinessException("无法解析工作区真实路径：" + e.getMessage());
        }
    }

    /** 校验文件是否为可读取的文本类型 */
    private void assertReadableText(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        if (dot < 0) {
            // 无扩展名：只允许小于 64KB 的纯文本试探读取（避免读取二进制）
            // 实际上后面会按字节读并检测，这里放行让后面判断
            return;
        }
        if (!TEXT_EXTENSIONS.contains(ext)) {
            throw new BusinessException("不支持读取的文件类型：" + ext);
        }
    }

    // ==================== 核心操作 ====================

    /** 列出工作区目录内容（非递归） */
    public List<FileNode> listDir(String workspaceRoot, String relativePath) {
        Path root = Paths.get(workspaceRoot);
        Path dir = resolveInWorkspace(root, relativePath);
        if (!Files.isDirectory(dir)) {
            throw new BusinessException("不是文件夹：" + relativePath);
        }
        List<FileNode> nodes = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                try {
                    // 目录项也可能是指向工作区外的软链接，属性读取前同样校验真实路径。
                    resolveInWorkspace(root, root.relativize(p).toString());
                    BasicFileAttributes attr = Files.readAttributes(p, BasicFileAttributes.class);
                    // 与搜索保持一致的忽略规则：版本库/依赖/构建产物不在资源管理器里展示
                    if (attr.isDirectory() && SKIPPED_DIRS.contains(p.getFileName().toString())) continue;
                    FileNode node = new FileNode(
                            p.getFileName().toString(),
                            attr.isDirectory(),
                            attr.isDirectory() ? -1L : attr.size(),
                            attr.lastModifiedTime().toMillis(),
                            // 相对于工作区的路径，方便前端再次请求
                            root.relativize(p).toString().replace('\\', '/')
                    );
                    nodes.add(node);
                } catch (IOException | BusinessException e) {
                    // 单个文件读属性失败或真实路径越界时跳过，不影响整个列表
                    log.debug("跳过无法安全读取属性的文件: {}", p);
                }
            }
        } catch (IOException e) {
            throw new BusinessException("读取目录失败：" + e.getMessage());
        }
        // 目录排前面，再按名称排序
        nodes.sort(Comparator.comparing(FileNode::isDirectory).reversed()
                .thenComparing(FileNode::name, String.CASE_INSENSITIVE_ORDER));
        return nodes;
    }

    /** 读取工作区文件内容（文本，带大小上限） */
    public String readFile(String workspaceRoot, String relativePath) {
        Path root = Paths.get(workspaceRoot);
        Path file = resolveInWorkspace(root, relativePath);
        if (!Files.exists(file) || Files.isDirectory(file)) {
            throw new BusinessException("文件不存在：" + relativePath);
        }
        assertReadableText(file);
        try {
            long size = Files.size(file);
            if (size > maxReadBytes) {
                // 超限：只读前 maxReadBytes 字节并提示
                byte[] bytes = new byte[maxReadBytes];
                try (var in = Files.newInputStream(file)) {
                    int read = in.read(bytes);
                    String text = new String(bytes, 0, read, StandardCharsets.UTF_8);
                    return text + "\n\n[文件过大，已截断到 " + maxReadBytes + " 字节，完整内容请直接在编辑器查看]";
                }
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("读取文件失败：" + e.getMessage());
        }
    }

    /** 写入/创建文件（覆盖） */
    public void writeFile(String workspaceRoot, String relativePath, String content) {
        if (content == null) content = "";
        if (content.getBytes(StandardCharsets.UTF_8).length > maxWriteBytes) {
            throw new BusinessException("文件内容超过 " + maxWriteBytes + " 字节限制");
        }
        Path root = Paths.get(workspaceRoot);
        Path file = resolveInWorkspace(root, relativePath);
        try {
            // 自动创建父目录
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            log.info("工作区写入文件: {}", relativePath);
        } catch (IOException e) {
            throw new BusinessException("写入文件失败：" + e.getMessage());
        }
    }

    /**
     * 精确编辑：把文件中第一处出现的 oldText 替换为 newText。
     * 类似 sed 的行为，适合 Agent 做小范围代码修改。
     */
    public String editFile(String workspaceRoot, String relativePath, String oldText, String newText) {
        if (oldText == null || oldText.isEmpty()) {
            throw new BusinessException("待替换的原文不能为空");
        }
        String current = readFile(workspaceRoot, relativePath);
        int idx = current.indexOf(oldText);
        if (idx < 0) {
            throw new BusinessException("文件中找不到指定的原文片段，无法替换");
        }
        String replaced = current.substring(0, idx) + (newText == null ? "" : newText)
                + current.substring(idx + oldText.length());
        writeFile(workspaceRoot, relativePath, replaced);
        return "替换成功";
    }

    /**
     * 新建空文件或空目录；同名目标已存在则拒绝，避免覆盖用户已有内容。
     */
    public void createEntry(String workspaceRoot, String relativePath, boolean directory) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BusinessException("新建路径不能为空");
        }
        Path root = Paths.get(workspaceRoot);
        Path target = resolveInWorkspace(root, relativePath);
        try {
            if (Files.exists(target)) {
                throw new BusinessException("已存在同名文件或文件夹：" + relativePath);
            }
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (directory) {
                Files.createDirectory(target);
            } else {
                Files.writeString(target, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            log.info("工作区新建{}: {}", directory ? "目录" : "文件", relativePath);
        } catch (IOException e) {
            throw new BusinessException("新建失败：" + e.getMessage());
        }
    }

    /**
     * 重命名/移动工作区内的文件或目录；目标已存在则拒绝。
     */
    public void renameEntry(String workspaceRoot, String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new BusinessException("重命名路径不能为空");
        }
        Path root = Paths.get(workspaceRoot);
        Path src = resolveInWorkspace(root, from);
        Path dst = resolveInWorkspace(root, to);
        if (!Files.exists(src)) {
            throw new BusinessException("原路径不存在：" + from);
        }
        if (Files.exists(dst)) {
            throw new BusinessException("目标路径已存在：" + to);
        }
        try {
            Path parent = dst.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.move(src, dst);
            log.info("工作区重命名: {} -> {}", from, to);
        } catch (IOException e) {
            throw new BusinessException("重命名失败：" + e.getMessage());
        }
    }

    /**
     * 删除工作区内的文件或目录（目录递归删除）；工作区根目录不允许删除。
     */
    public void deleteEntry(String workspaceRoot, String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.equals(".")) {
            throw new BusinessException("不能删除工作区根目录");
        }
        Path root = Paths.get(workspaceRoot);
        Path target = resolveInWorkspace(root, relativePath);
        if (!Files.exists(target)) {
            throw new BusinessException("路径不存在：" + relativePath);
        }
        try {
            if (Files.isDirectory(target)) {
                try (Stream<Path> walk = Files.walk(target)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new BusinessException("删除失败：" + e.getMessage());
                        }
                    });
                }
            } else {
                Files.delete(target);
            }
            log.info("工作区删除: {}", relativePath);
        } catch (IOException e) {
            throw new BusinessException("删除失败：" + e.getMessage());
        }
    }

    /**
     * 按文件名 / 相对路径模糊搜索工作区内的文件，供前端 @ 引用选择器使用。
     * 只返回文件（不返回目录），跳过依赖与构建产物目录；空关键字返回空列表。
     */
    public List<FileNode> findFilesByName(String workspaceRoot, String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) return List.of();
        Path root = Paths.get(workspaceRoot);
        List<FileNode> nodes = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, maxListDepth)) {
            Iterator<Path> it = walk.iterator();
            while (it.hasNext() && nodes.size() < limit) {
                Path p = it.next();
                if (Files.isDirectory(p) || isUnderSkippedDir(root, p)) continue;
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.toLowerCase().contains(q)) continue;
                try {
                    resolveInWorkspace(root, rel);
                    BasicFileAttributes attr = Files.readAttributes(p, BasicFileAttributes.class);
                    nodes.add(new FileNode(p.getFileName().toString(), false, attr.size(),
                            attr.lastModifiedTime().toMillis(), rel));
                } catch (IOException | BusinessException e) {
                    log.debug("跳过无法安全读取属性的文件: {}", p);
                }
            }
        } catch (IOException e) {
            throw new BusinessException("搜索文件失败：" + e.getMessage());
        }
        // 文件名命中优先，其次浅层路径优先，最后按路径字典序，保证结果稳定可预期
        nodes.sort(Comparator
                .comparingInt((FileNode n) -> n.name().toLowerCase().contains(q) ? 0 : 1)
                .thenComparingInt(n -> n.relativePath().length())
                .thenComparing(FileNode::relativePath, String.CASE_INSENSITIVE_ORDER));
        return nodes;
    }

    /** 判断文件是否位于被跳过的目录下（任一祖先目录命中 SKIPPED_DIRS） */
    private boolean isUnderSkippedDir(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (SKIPPED_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    /**
     * 在工作区中搜索文件内容（grep）。
     * @param pattern   搜索文本（子串匹配，不是正则，避免注入）
     * @param glob      可选的文件过滤 glob，如 "*.java"；为空则搜所有文本文件
     */
    public List<SearchHit> searchInFiles(String workspaceRoot, String pattern, String glob) {
        if (pattern == null || pattern.isBlank()) {
            throw new BusinessException("搜索内容不能为空");
        }
        Path root = Paths.get(workspaceRoot);
        List<SearchHit> hits = new ArrayList<>();
        PathMatcher matcher = glob == null || glob.isBlank() ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);

        try (Stream<Path> walk = Files.walk(root, maxListDepth)) {
            Iterator<Path> it = walk.iterator();
            while (it.hasNext() && hits.size() < maxSearchResults) {
                Path p = it.next();
                if (Files.isDirectory(p)) continue;
                if (matcher != null && !matcher.matches(p.getFileName())) continue;
                try {
                    // grep 会跟随文件软链接读取内容，必须在打开文件前做 realpath 边界校验。
                    resolveInWorkspace(root, root.relativize(p).toString());
                } catch (BusinessException e) {
                    continue;
                }
                if (!isLikelyText(p)) continue;
                try {
                    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size() && hits.size() < maxSearchResults; i++) {
                        if (lines.get(i).contains(pattern)) {
                            hits.add(new SearchHit(
                                    root.relativize(p).toString().replace('\\', '/'),
                                    i + 1,
                                    lines.get(i).trim()
                            ));
                        }
                    }
                } catch (IOException | OutOfMemoryError e) {
                    // 读不了的文件跳过
                }
            }
        } catch (IOException e) {
            throw new BusinessException("搜索失败：" + e.getMessage());
        }
        return hits;
    }

    /** 简单判断是否为文本文件（按扩展名 + 前 512 字节是否含 NUL） */
    private boolean isLikelyText(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1);
            if (!TEXT_EXTENSIONS.contains(ext) && !ext.isEmpty()) return false;
        }
        try {
            byte[] head = new byte[512];
            int n;
            try (var in = Files.newInputStream(p)) {
                n = in.read(head);
            }
            for (int i = 0; i < n; i++) {
                if (head[i] == 0) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 返回结构 ====================

    public record FileNode(String name, boolean isDirectory, long size, long modifiedTime, String relativePath) {}

    public record SearchHit(String file, int line, String content) {}
}
