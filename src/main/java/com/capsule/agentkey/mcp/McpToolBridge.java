package com.capsule.agentkey.mcp;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.ToolApprovalRegistry;
import com.capsule.agentkey.service.WorkspaceFileService;
import com.capsule.agentkey.tool.FileChange;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 第五十一批：MCP 工具桥接。第五十二批加熔断 + 健康监控 + 工具去重。
 *
 * 启动后把已配置 MCP server 的工具注册进 {@link ToolRegistry}，标记 source=mcp；
 * 工具执行仍走 ToolRegistry.dispatch（权限、参数校验、截断、指标统一在这里）。
 *
 * 第五十二批新增：
 * - 健康监控：registerServer 时通知 McpHealthMonitor 纳入监控范围
 * - 熔断：invoke 前先查 McpHealthMonitor.checkBeforeInvoke()，DOWN 的 server 直接拒绝
 * - 成功/失败计数：invoke 后调 recordSuccess / recordFailure
 * - 工具去重：buildAdapter 时跳过已被内置工具覆盖的 MCP 工具
 * - 增强失败提示：ToolResult.fail() 里加 server 名 + 排查建议
 */
@Component
@Slf4j
public class McpToolBridge implements ApplicationRunner {

    private static final String TOOL_PREFIX = "mcp_";
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 1024;

    /**
     * 第五十九批：与内置工具功能等价的 MCP 工具名 → 内置主工具名映射。
     * 不再跳过注册，而是注册为 fallback=true（模型 prompt 不展示），
     * dispatch 层内置主工具失败时自动 try 它们。
     *
     * MCP 独有工具（不在此 Map 里）正常注册，模型能看到和调用。
     */
    private static final Map<String, String> EQUIVALENT_MAP = Map.ofEntries(
            Map.entry("read_file", "read_file"),
            Map.entry("read_text_file", "read_file"),
            Map.entry("write_file", "write_file"),
            Map.entry("edit_file", "edit_file"),
            Map.entry("list_directory", "list_dir"),
            Map.entry("list_directory_with_sizes", "list_dir"),
            Map.entry("directory_tree", "list_dir"),
            Map.entry("search_files", "search_files"),
            Map.entry("glob", "search_files")
    );

    private final ObjectProvider<List<McpSyncClient>> clientsProvider;
    private final ObjectProvider<McpClientCommonProperties> commonPropertiesProvider;
    private final ToolRegistry toolRegistry;
    private final McpHealthMonitor healthMonitor;
    private final McpGlobalSwitch globalSwitch;
    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 空 = 放行全部已配置 server；填写则只注册这些连接名（如 filesystem） */
    private final Set<String> allowedServers;
    /** 单个工具返回文本上限，防止 MCP 大输出把上下文撑爆 */
    private final int maxResultChars;

    public McpToolBridge(ObjectProvider<List<McpSyncClient>> clientsProvider,
                         ObjectProvider<McpClientCommonProperties> commonPropertiesProvider,
                         ToolRegistry toolRegistry,
                         McpHealthMonitor healthMonitor,
                         McpGlobalSwitch globalSwitch,
                         ProjectService projectService,
                         WorkspaceFileService workspaceFileService,
                         @Value("${agent.mcp.tools.allowed-servers:}") String allowedServers,
                         @Value("${agent.mcp.tools.max-result-chars:8000}") int maxResultChars) {
        this.clientsProvider = clientsProvider;
        this.commonPropertiesProvider = commonPropertiesProvider;
        this.toolRegistry = toolRegistry;
        this.healthMonitor = healthMonitor;
        this.globalSwitch = globalSwitch;
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
        this.allowedServers = Arrays.stream(allowedServers.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.maxResultChars = maxResultChars;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<McpSyncClient> clients = clientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) {
            log.info("MCP 未启用或没有配置 server，跳过 MCP 工具注册");
            return;
        }
        McpClientCommonProperties commonProperties = commonPropertiesProvider.getIfAvailable();
        int registered = 0;
        int fallbackCount = 0;
        for (McpSyncClient client : clients) {
            int[] result = registerServer(client, commonProperties);
            registered += result[0];
            fallbackCount += result[1];
        }
        int total = toolRegistry.getAllTools().size();
        log.info("MCP 工具注册完成：新增 {} 个（其中 {} 个 fallback）；系统总工具数 {}（= {} 内置 + {} MCP）。",
                registered, fallbackCount, total, total - registered, registered);
    }

    /**
     * 注册单个 server 的全部工具。第五十九批：不再 DEDUP 跳过，
     * 等价工具注册为 fallback=true + 建立映射，独有工具正常注册。
     *
     * @return int[2]: [成功注册数, fallback 数]
     */
    private int[] registerServer(McpSyncClient client, McpClientCommonProperties commonProperties) {
        String serverName = resolveServerName(client, commonProperties);
        if (!allowedServers.isEmpty() && !allowedServers.contains(serverName)) {
            log.info("MCP server [{}] 不在白名单内，跳过", serverName);
            return new int[]{0, 0};
        }
        // 纳入健康监控
        healthMonitor.registerServer(serverName);
        try {
            if (!client.isInitialized()) {
                client.initialize();
            }
            McpSchema.ListToolsResult result = client.listTools();
            if (result == null || result.tools() == null || result.tools().isEmpty()) {
                log.warn("MCP server [{}] 没有提供任何工具", serverName);
                return new int[]{0, 0};
            }
            int registered = 0;
            int fallbackCount = 0;
            for (McpSchema.Tool tool : result.tools()) {
                // 第五十九批：判断是等价（fallback）还是独有（正常注册）
                String mainToolName = EQUIVALENT_MAP.get(tool.name());
                boolean isFallback = mainToolName != null;
                McpToolAdapter adapter = buildAdapter(client, serverName, tool, isFallback);
                if (!toolRegistry.register(adapter)) {
                    continue;
                }
                // 建立 fallback 映射：主工具 → 这个 fallback 工具名
                if (isFallback) {
                    String fullMcpName = toolName(serverName, tool.name());
                    toolRegistry.addFallback(mainToolName, fullMcpName);
                    fallbackCount++;
                    log.debug("MCP [{}/{}] 注册为 fallback → 主工具 [{}]",
                            serverName, tool.name(), mainToolName);
                }
                registered++;
            }
            log.info("MCP server [{}] 注册 {} 个工具（其中 {} 个 fallback）", serverName, registered, fallbackCount);
            return new int[]{registered, fallbackCount};
        } catch (Exception e) {
            log.warn("MCP server [{}] 初始化或读取工具列表失败，已跳过: {}", serverName, e.getMessage());
            healthMonitor.recordFailure(serverName, "初始化失败: " + e.getMessage());
            return new int[]{0, 0};
        }
    }

    private McpToolAdapter buildAdapter(McpSyncClient client, String serverName, McpSchema.Tool tool, boolean isFallback) {
        String toolName = toolName(serverName, tool.name());
        String description = tool.description() == null ? "" : tool.description();
        description = "【外部·" + serverName + "】" + description;
        if (isFallback) {
            description = "（备选：内置主工具失败时自动尝试）" + description;
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            description = description.substring(0, MAX_DESCRIPTION_LENGTH) + "...";
        }
        Map<String, Object> schema = tool.inputSchema() == null
                ? Map.of("type", "object", "properties", Map.of())
                : tool.inputSchema();
        String remoteName = tool.name();
        Capability requiredCapability = ToolApprovalRegistry.riskOf(toolName) == null
                ? Capability.MCP_READ
                : Capability.MCP_WRITE;
        return new McpToolAdapter(toolName, description, schema,
                arguments -> invoke(client, serverName, remoteName, arguments), isFallback, requiredCapability);
    }

    /**
     * 真正调用外部工具。第五十二批：先查健康监控熔断、成功/失败更新计数、增强失败提示。
     * 第五十三批：filesystem server 的写入类工具（create_directory / move_file）也走 diff/快照链路，
     * 让前端变更面板能看到 MCP 产生的文件变更。
     */
    private ToolResult invoke(McpSyncClient client, String serverName, String remoteName, JsonNode arguments) {
        // 第六十七批：全局开关关闭时直接拒绝——MCP server 可能还在跑，但逻辑上禁止暴露给模型
        if (!globalSwitch.isEnabled()) {
            log.warn("MCP 全局开关已禁用，拒绝 [{}/{}]", serverName, remoteName);
            return ToolResult.fail("MCP 外部工具已被禁用。请到设置页「MCP 工具」重新启用。");
        }
        // 第五十二批：熔断检查——DOWN 的 server 直接拒绝，不浪费超时等待
        String blockReason = healthMonitor.checkBeforeInvoke(serverName);
        if (blockReason != null) {
            log.warn("MCP 工具 [{}/{}] 被健康监控拒绝: {}", serverName, remoteName, blockReason);
            return ToolResult.fail("外部工具暂不可用。" + blockReason);
        }
        try {
            Map<String, Object> argumentsMap = arguments == null || arguments.isNull() || arguments.isMissingNode()
                    ? Map.of()
                    : mapper.convertValue(arguments, new TypeReference<Map<String, Object>>() {});
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(remoteName, argumentsMap));
            String text = flatten(result);
            if (Boolean.TRUE.equals(result.isError())) {
                healthMonitor.recordFailure(serverName, "MCP server 返回 error: " + text);
                return ToolResult.fail(buildFailMsg(serverName, remoteName,
                        "server 返回错误（isError=true）: " + (text.isEmpty() ? "无详情" : text)));
            }
            // 成功：重置连续失败计数
            healthMonitor.recordSuccess(serverName);
            // 第五十三批：filesystem server 的写入类工具走 diff 链路
            recordMcpFileChange(serverName, remoteName, argumentsMap);
            return ToolResult.ok(truncate(text.isEmpty() ? "(该工具没有返回文本内容)" : text));
        } catch (Exception e) {
            String errMsg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("MCP 工具 [{}/{}] 调用失败: {}", serverName, remoteName, errMsg);
            healthMonitor.recordFailure(serverName, errMsg);
            return ToolResult.fail(buildFailMsg(serverName, remoteName, errMsg));
        }
    }

    /**
     * 第五十三批：filesystem server 的写入类工具执行后，登记文件变更到 WorkspaceContext。
     * create_directory / move_file 不会改变文件文本内容，所以 diff 为 0 行加减，
     * 变更说明里只写一段描述性文字（"新建目录" / "从 X 移动到 Y"）。
     *
     * 为什么只覆盖 filesystem server 的这两个工具？
     * - write_file / edit_file 已被去重跳过（内置覆盖）
     * - git server 只有只读工具（log / diff / blame / show），不写文件
     * - 其他 server 暂未知，等接入时再扩展
     *
     * 失败不影响主链路：diff 记录只是向前端展示用，拿不到文件就跳过。
     */
    private void recordMcpFileChange(String serverName, String remoteName, Map<String, Object> args) {
        if (!"filesystem".equals(serverName)) return; // 目前只处理 filesystem
        WorkspaceContext ctx = WorkspaceContext.get();
        if (ctx == null) return; // 没有上下文（单元测试等），静默跳过
        try {
            Project p = projectService.getOwned(ctx.userId(), ctx.projectId());
            if (p.getWorkspacePath() == null) return;
            Path root = Paths.get(p.getWorkspacePath());
            switch (remoteName) {
                case "create_directory" -> {
                    String path = (String) args.get("path");
                    if (path == null) return;
                    Path dir = workspaceFileService.resolveInWorkspace(root, path);
                    if (!Files.isDirectory(dir)) return; // 调用没生效，不记录
                    String relative = root.relativize(dir).toString().replace('\\', '/');
                    WorkspaceContext.recordFileChange(new FileChange(
                            relative, "create", "mcp_filesystem_create_directory",
                            0, 0, "（MCP 新建目录）"));
                }
                case "move_file" -> {
                    String source = (String) args.get("source");
                    String dest = (String) args.get("destination");
                    if (source == null || dest == null) return;
                    Path srcPath = workspaceFileService.resolveInWorkspace(root, source);
                    Path destPath = workspaceFileService.resolveInWorkspace(root, dest);
                    // move 生效条件：源路径消失 + 目标路径出现
                    if (Files.exists(srcPath) || !Files.exists(destPath)) return;
                    String srcRel = root.relativize(srcPath).toString().replace('\\', '/');
                    String destRel = root.relativize(destPath).toString().replace('\\', '/');
                    WorkspaceContext.recordFileChange(new FileChange(
                            destRel, "create", "mcp_filesystem_move_file",
                            0, 0, "（从 " + srcRel + " 移动）"));
                    WorkspaceContext.recordFileChange(new FileChange(
                            srcRel, "command", "mcp_filesystem_move_file",
                            0, 0, "（已移动到 " + destRel + "）"));
                }
                default -> { /* 其他 filesystem 工具只读，不记录 */ }
            }
        } catch (Exception e) {
            log.debug("MCP 文件变更记录失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 给模型的失败提示：除了错误本身，还告诉它这是哪个 server 的、是否可以换内置工具试试 */
    private String buildFailMsg(String serverName, String remoteName, String error) {
        String suggestion = switch (remoteName) {
            case "write_file", "edit_file" -> "（提示：写入操作可改用内置 write_file / edit_file 工具）";
            case "list_directory", "list_directory_with_sizes", "directory_tree" -> "（提示：列目录可改用内置 list_dir 工具）";
            case "search_files" -> "（提示：搜文件可改用内置 search_files 工具）";
            default -> "";
        };
        return String.format("外部 MCP 工具 [%s/%s] 调用失败: %s%s",
                serverName, remoteName, truncate(error, 500), suggestion);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    // ========== 以下方法与第五十一批相同（未改） ==========

    private String flatten(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content == null) continue;
            if (!sb.isEmpty()) sb.append('\n');
            if (content instanceof McpSchema.TextContent textContent) {
                sb.append(textContent.text());
            } else {
                sb.append('[').append(content.type()).append(" 内容已省略]");
            }
        }
        return sb.toString();
    }

    private String resolveServerName(McpSyncClient client, McpClientCommonProperties commonProperties) {
        String clientName = client.getClientInfo() == null ? null : client.getClientInfo().name();
        if (clientName == null || clientName.isBlank()) return "unknown";
        String prefix = commonProperties == null ? null : commonProperties.getName();
        if (prefix != null && !prefix.isBlank()) {
            if (clientName.startsWith(prefix + " - ")) {
                String n = clientName.substring(prefix.length() + 3).trim();
                if (!n.isBlank()) return n;
            }
            if (clientName.startsWith(prefix + "-")) {
                String n = clientName.substring(prefix.length() + 1).trim();
                if (!n.isBlank()) return n;
            }
        }
        return clientName;
    }

    private String toolName(String serverName, String remoteName) {
        String raw = TOOL_PREFIX + sanitize(serverName) + "_" + sanitize(remoteName);
        return raw.length() > MAX_TOOL_NAME_LENGTH ? raw.substring(0, MAX_TOOL_NAME_LENGTH) : raw;
    }

    private String sanitize(String value) {
        if (value == null || value.isEmpty()) return "tool";
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.isEmpty() ? "tool" : cleaned;
    }

    private String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= maxResultChars) return text;
        return text.substring(0, maxResultChars) + "\n...（MCP 输出超过 " + maxResultChars + " 字符，已截断）";
    }

    public void bindWorkspacePath(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) return;
        List<McpSyncClient> clients = clientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) return;
        String uri = pathToUri(workspacePath);
        String rootName = "workspace-" + workspacePath.hashCode();
        int bound = 0;
        for (McpSyncClient client : clients) {
            try {
                client.addRoot(new McpSchema.Root(uri, rootName));
                bound++;
            } catch (Exception e) {
                log.warn("MCP client [{}] 拒绝 root {}: {}",
                        client.getClientInfo() == null ? "unknown" : client.getClientInfo().name(),
                        workspacePath, e.getMessage());
            }
        }
        if (bound > 0) {
            log.info("已把工作区 {} 注入 {} 个 MCP client 的 Root 白名单", workspacePath, bound);
        }
    }

    private String pathToUri(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/")) return "file://" + normalized;
        return "file:///" + normalized;
    }
}
