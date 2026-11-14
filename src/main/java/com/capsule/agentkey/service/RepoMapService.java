package com.capsule.agentkey.service;

import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.service.index.CodeIndexService;
import com.capsule.agentkey.service.index.CodeSymbol;
import com.capsule.agentkey.service.index.CodeIndexService.IndexedFile;
import com.capsule.agentkey.service.index.CodeIndexService.ScanOptions;
import com.capsule.agentkey.service.index.CodeIndexService.WorkspaceIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P5-04：把原来的单层目录树升级为 repo map。
 *
 * <p>只负责渲染：目录结构 + 关键符号摘要由 {@link CodeIndexService} 提供，
 * 本类按 {@code agent.repo-map.max-chars} 预算输出，并提示模型在截断后改用
 * {@code list_dir} / {@code search_files}。不建持久索引，也不做语义检索；
 * 目的是减少模型为了解项目结构而反复 list_dir/read_file 的探索成本。</p>
 */
@Service
@Slf4j
public class RepoMapService {

    private static final String TRUNCATED_NOTE =
            "…（repo map 已截断，可用 list_dir / search_files 查看其余文件）";

    private final AgentRepoMapProperties properties;
    private final CodeIndexService codeIndexService;

    public RepoMapService(AgentRepoMapProperties properties, CodeIndexService codeIndexService) {
        this.properties = properties;
        this.codeIndexService = codeIndexService;
    }

    /**
     * 生成 repo map 文本。
     *
     * @param workspaceRoot 工作区绝对路径；为空、不可读或关闭开关时返回空串
     */
    public String build(String workspaceRoot) {
        if (!properties.isEnabled() || workspaceRoot == null || workspaceRoot.isBlank()) {
            return "";
        }
        WorkspaceIndex index = codeIndexService.snapshot(workspaceRoot,
                ScanOptions.of(properties.getMaxDepth(), properties.getMaxFiles()));
        if (index.files().isEmpty()) {
            return "";
        }
        List<FileEntry> entries = new ArrayList<>(index.files().size());
        for (IndexedFile file : index.files()) {
            entries.add(new FileEntry(file.path(), displaySymbols(file.symbols())));
        }
        return render(entries, index.limited());
    }

    private List<String> displaySymbols(List<CodeSymbol> symbols) {
        int max = Math.max(0, properties.getMaxSymbolsPerFile());
        List<String> displayed = new ArrayList<>(Math.min(max, symbols.size()));
        for (int i = 0; i < symbols.size() && i < max; i++) {
            displayed.add(symbols.get(i).display());
        }
        return displayed;
    }

    private String render(List<FileEntry> entries, boolean scanLimited) {
        DirNode root = new DirNode("");
        for (FileEntry entry : entries) {
            String[] parts = entry.path().split("/");
            DirNode dir = root;
            for (int i = 0; i < parts.length - 1; i++) {
                dir = dir.dirs.computeIfAbsent(parts[i], DirNode::new);
            }
            dir.files.put(parts[parts.length - 1], entry.symbols());
        }

        StringBuilder sb = new StringBuilder();
        int budget = Math.max(0, properties.getMaxChars() - TRUNCATED_NOTE.length() - 1);
        boolean truncated = renderDir(root, 0, sb, budget);
        if (truncated || scanLimited) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(TRUNCATED_NOTE);
        }
        return sb.toString();
    }

    /** @return true 表示预算不足，调用方应追加截断提示并停止 */
    private static boolean renderDir(DirNode dir, int depth, StringBuilder sb, int budget) {
        for (DirNode child : dir.dirs.values()) {
            if (!appendLine(sb, indent(depth) + child.name + "/", budget)) {
                return true;
            }
            if (renderDir(child, depth + 1, sb, budget)) {
                return true;
            }
        }
        for (Map.Entry<String, List<String>> file : dir.files.entrySet()) {
            StringBuilder line = new StringBuilder(indent(depth)).append(file.getKey());
            if (!file.getValue().isEmpty()) {
                line.append("：").append(String.join(" · ", file.getValue()));
            }
            if (!appendLine(sb, line.toString(), budget)) {
                return true;
            }
        }
        return false;
    }

    private static boolean appendLine(StringBuilder sb, String line, int budget) {
        if (sb.length() + line.length() + 1 > budget) {
            return false;
        }
        sb.append(line).append('\n');
        return true;
    }

    private static String indent(int depth) {
        return "  ".repeat(Math.max(0, depth));
    }

    private record FileEntry(String path, List<String> symbols) {}

    private static final class DirNode {
        private final String name;
        private final Map<String, DirNode> dirs = new TreeMap<>();
        private final Map<String, List<String>> files = new TreeMap<>();

        private DirNode(String name) {
            this.name = name;
        }
    }
}
