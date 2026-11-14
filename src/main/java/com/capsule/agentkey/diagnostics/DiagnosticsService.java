package com.capsule.agentkey.diagnostics;

import com.capsule.agentkey.config.AgentDiagnosticsProperties;
import com.capsule.agentkey.tool.FileChange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * P5-05：变更文件诊断编排。
 *
 * <p>负责选择 provider、过滤本批变更文件、限量截断与降级。任何异常都在这里吸收，
 * 保证诊断永远不会让工具调用失败或中断 ReAct。
 */
@Service
@Slf4j
public class DiagnosticsService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final AgentDiagnosticsProperties properties;
    private final Map<String, DiagnosticsProvider> providers;

    public DiagnosticsService(AgentDiagnosticsProperties properties, List<DiagnosticsProvider> providers) {
        this.properties = properties;
        this.providers = providers.stream()
                .collect(Collectors.toMap(DiagnosticsProvider::name, p -> p, (a, b) -> a, LinkedHashMap::new));
    }

    public boolean enabled() {
        return properties.isEnabled() && !"none".equalsIgnoreCase(resolveProviderName());
    }

    public String resolveProviderName() {
        String configured = properties.getProvider();
        if (configured == null || configured.isBlank()) {
            return "javac";
        }
        String name = configured.trim().toLowerCase();
        if (!providers.containsKey(name) && !"none".equals(name)) {
            log.warn("未知的诊断 provider [{}]，回落 javac", configured);
            return "javac";
        }
        return name;
    }

    /**
     * 采集本批变更文件的诊断。工作区为空、开关关闭、无受支持文件或 provider 不可用时
     * 返回 available=false 的空结果，不抛异常。
     */
    public DiagnosticsResult collect(Path workspaceRoot, List<FileChange> changes) {
        String providerName = resolveProviderName();
        if (!properties.isEnabled() || "none".equals(providerName)
                || workspaceRoot == null || changes == null || changes.isEmpty()) {
            return new DiagnosticsResult(providerName, false, 0, List.of(), 0, false);
        }
        DiagnosticsProvider provider = providers.get(providerName);
        if (provider == null) {
            return new DiagnosticsResult(providerName, false, 0, List.of(), 0, false);
        }

        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<Path> files = selectFiles(root, provider, changes);
        if (files.isEmpty()) {
            return new DiagnosticsResult(providerName, false, 0, List.of(), 0, false);
        }

        try {
            DiagnosticsProvider.ProviderResult result =
                    provider.diagnose(root, files, Math.max(500, properties.getTimeoutMs()));
            if (result == null || !result.available()) {
                return new DiagnosticsResult(providerName, false, files.size(), List.of(), 0, false);
            }
            return limit(providerName, files.size(), result.diagnostics());
        } catch (Exception e) {
            log.warn("诊断采集失败，降级为不可用: provider={}, error={}", providerName, e.getMessage());
            return new DiagnosticsResult(providerName, false, files.size(), List.of(), 0, false);
        }
    }

    /** 文件版本用于把诊断与具体内容关联：mtime + size 足够区分本轮改动 */
    public String fileVersion(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
        } catch (Exception e) {
            return "";
        }
    }

    /** 诊断指纹：与路径、行号、严重级和归一化消息相关；排序保证同一批诊断顺序无关 */
    public static String fingerprint(List<Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "";
        }
        return diagnostics.stream()
                .map(d -> d.file() + "|" + d.line() + "|" + d.severity() + "|"
                        + WHITESPACE.matcher(d.message() == null ? "" : d.message().trim()).replaceAll(" "))
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    private List<Path> selectFiles(Path root, DiagnosticsProvider provider, List<FileChange> changes) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        int maxFiles = Math.max(1, properties.getMaxFiles());
        for (FileChange change : changes) {
            if (change == null || change.path() == null || change.path().isBlank()) {
                continue;
            }
            if ("delete".equalsIgnoreCase(change.action())) {
                continue;
            }
            try {
                Path file = root.resolve(change.path()).normalize();
                if (!file.startsWith(root) || !provider.supports(file) || !Files.isRegularFile(file)) {
                    continue;
                }
                paths.add(file);
                if (paths.size() >= maxFiles) {
                    break;
                }
            } catch (Exception e) {
                log.debug("跳过无法解析的变更路径: {}", change.path());
            }
        }
        return new ArrayList<>(paths);
    }

    private DiagnosticsResult limit(String provider, int analyzedFiles, List<Diagnostic> all) {
        List<Diagnostic> source = all == null ? List.of() : all;
        int total = source.size();
        int maxItems = Math.max(1, properties.getMaxItems());
        int maxChars = Math.max(200, properties.getMaxChars());

        List<Diagnostic> kept = new ArrayList<>(source.subList(0, Math.min(total, maxItems)));
        boolean truncated = total > kept.size();

        DiagnosticsResult result = new DiagnosticsResult(provider, true, analyzedFiles, kept, total, truncated);
        while (!kept.isEmpty() && result.formatForPrompt().length() > maxChars) {
            kept.remove(kept.size() - 1);
            truncated = true;
            result = new DiagnosticsResult(provider, true, analyzedFiles, kept, total, truncated);
        }
        return result;
    }
}
