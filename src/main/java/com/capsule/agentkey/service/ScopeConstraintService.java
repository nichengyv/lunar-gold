package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * D1 批次：用户消息里声明文件范围约束（scope），工具执行前拦截。
 *
 * 支持两种语法：
 *   1. 显式标记：#scope: src/components, utils, test.html
 *      （#scope 后面的逗号/空白分隔路径列表）
 *   2. 自然语言："只改 src/components 目录" / "范围: utils" / "仅 src/components 和 src/utils"
 *
 * 路径匹配规则：
 *   - scope 项是目录（不以扩展名结尾）→ 前缀匹配：写文件路径以该项开头即可
 *   - scope 项是文件（有扩展名）→ 精确匹配
 *   - scope 列表为空 → 不限制（默认）
 *
 * 只拦截接受文件路径的工具：write_file / edit_file / read_file / list_dir / search_files
 * run_command 不拦截（命令行里路径太灵活，沙箱/白名单已经有约束）
 */
@Service
@Slf4j
public class ScopeConstraintService {

    /** 接受文件路径参数的工具——scope 只拦这些 */
    public static final Set<String> FILE_TOOLS = Set.of(
            "write_file", "edit_file", "read_file", "list_dir", "search_files", "delete_file");

    // —— 正则 ——

    /** #scope: path1, path2 显式声明 */
    private static final Pattern EXPLICIT_SCOPE = Pattern.compile(
            "(?i)#scope[:：]\\s*([^\\n#]+)");

    /** 自然语言："只改 X 目录" / "范围: X" / "仅 X 和 Y" / "限定在 X" */
    private static final Pattern NL_PATH_LIST = Pattern.compile(
            "(?:只改|仅限|仅|限定在|范围[:：]|scope[:：]?)\\s*([\\w/.\\-\\s,，、]+?)(?:目录|文件夹|文件|下|内|中|$)");

    private static final Pattern NL_SINGLE_PATH = Pattern.compile(
            "(?:只改|仅限|限定在|只允许)\\s+([\\w/.\\-]+?)\\s*(?:目录|文件夹|文件|下|里|之内|以上|以下|$)");

    // —— 公共入口 ——

    /**
     * 从用户消息里解析 scope，设置进 WorkspaceContext。
     * 每条用户消息都可以声明 scope；同会话中后声明的会覆盖之前的。
     *
     * @return 解析出的 scope 列表；空表示"不限制"（用户没声明）
     */
    public List<String> parseAndApply(String userPrompt) {
        WorkspaceContext ctx = WorkspaceContext.get();
        if (ctx == null || userPrompt == null || userPrompt.isBlank()) {
            return List.of();
        }

        List<String> scope = parse(userPrompt);
        ctx.setScope(scope);

        if (!scope.isEmpty()) {
            log.info("D1 scope 约束已应用: [{}]，后续文件类工具将被拦截",
                    String.join(", ", scope));
        } else {
            // 用户这轮没声明 scope → 保持上一轮的（如果有）
            scope = ctx.scope();
        }
        return scope;
    }

    /** 解析 scope（独立于 WorkspaceContext，方便测试） */
    List<String> parse(String prompt) {
        Set<String> raw = new LinkedHashSet<>();

        // 1. 显式标记 #scope: ...
        Matcher m = EXPLICIT_SCOPE.matcher(prompt);
        while (m.find()) {
            for (String p : splitPaths(m.group(1))) raw.add(p);
        }

        // 2. 自然语言
        m = NL_PATH_LIST.matcher(prompt);
        while (m.find()) {
            for (String p : splitPaths(m.group(1))) raw.add(p);
        }

        // 3. 单路径形式（更宽松的正则，可能有误匹配，放最后兜底）
        if (raw.isEmpty()) {
            m = NL_SINGLE_PATH.matcher(prompt);
            while (m.find()) {
                for (String p : splitPaths(m.group(1))) raw.add(p);
            }
        }

        // 清理：去重、trim、去前导斜杠
        return raw.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("^[\\\\/]+", ""))
                .collect(Collectors.toList());
    }

    /**
     * D1 拦截器：工具执行前检查 scope。
     * scope 空 → 放行；scope 非空 + 工具是文件类 → 校验路径。
     *
     * @throws BusinessException 路径不在 scope 内时抛出，让 Agent 收到 tool_error 换方案
     */
    public void checkToolCall(String toolName, JsonNode arguments, Path workDir) {
        WorkspaceContext ctx = WorkspaceContext.get();
        if (ctx == null) return;
        List<String> scope = ctx.scope();
        if (scope.isEmpty()) return;          // 默认不限制

        if (!FILE_TOOLS.contains(toolName)) return; // run_command 等不拦

        String filePath = extractFilePath(toolName, arguments);
        if (filePath == null) return;         // 没传路径参数？放行（让工具自己校验）

        // 规范化：相对路径就直接用；绝对路径 → 转相对 workDir
        String normalized;
        try {
            Path p = Paths.get(filePath);
            if (p.isAbsolute()) {
                if (workDir != null) {
                    normalized = workDir.relativize(p).toString().replace('\\', '/');
                } else {
                    normalized = filePath.replace('\\', '/');
                }
            } else {
                normalized = filePath.replace('\\', '/');
            }
        } catch (Exception e) {
            normalized = filePath.replace('\\', '/');
        }

        if (!isWithinScope(normalized, scope)) {
            throw new BusinessException(
                    "D1 scope 约束：该路径 '" + normalized + "' 不在用户声明的范围内 ["
                            + String.join(", ", scope) + "]。"
                            + "请只在 scope 内的目录/文件上操作，或让用户取消 scope 声明。");
        }
    }

    // —— 工具 ——

    /** 不同工具的路径参数名不同 */
    private String extractFilePath(String toolName, JsonNode args) {
        if (args == null) return null;
        return switch (toolName) {
            case "write_file", "edit_file", "read_file", "delete_file" -> textOrNull(args, "filePath", "path", "file_path");
            case "list_dir" -> textOrNull(args, "dirPath", "path", "directory");
            case "search_files" -> textOrNull(args, "dirPath", "rootPath", "path");
            default -> null;
        };
    }

    private String textOrNull(JsonNode args, String... keys) {
        for (String k : keys) {
            JsonNode v = args.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    /** 路径是否在 scope 列表内 */
    static boolean isWithinScope(String path, List<String> scope) {
        if (path == null || scope == null || scope.isEmpty()) return true;
        String p = path.replace('\\', '/');

        for (String s : scope) {
            String scopeItem = s.replace('\\', '/').trim();
            if (scopeItem.isEmpty()) continue;

            // 文件（有扩展名）→ 精确匹配 或 以 /scopeItem 结尾
            if (scopeItem.contains(".")) {
                if (p.equals(scopeItem) || p.endsWith("/" + scopeItem)) return true;
            }
            // 目录 → 前缀匹配
            else {
                if (p.equals(scopeItem)
                        || p.startsWith(scopeItem + "/")
                        || p.startsWith(scopeItem + "\\")) return true;
            }
        }
        return false;
    }

    /** scope 项只匹配合法路径字符，避免把后面的中文描述也带进去 */
    private static final Pattern PATH_TOKEN = Pattern.compile("[A-Za-z0-9_./\\-\\\\]+");

    private List<String> splitPaths(String raw) {
        // 只提取合法路径字符序列，忽略中文/标点等非路径内容
        Set<String> result = new LinkedHashSet<>();
        Matcher m = PATH_TOKEN.matcher(raw);
        while (m.find()) {
            String token = m.group().trim();
            // 去掉末尾的标点/纯数字（如版本号）
            if (!token.isEmpty() && !token.matches("^[.\\-/]+$")) {
                result.add(token);
            }
        }
        return new ArrayList<>(result);
    }
}
