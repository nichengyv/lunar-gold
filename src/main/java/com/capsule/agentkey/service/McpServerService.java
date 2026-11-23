package com.capsule.agentkey.service;

import com.capsule.agentkey.dto.McpServerRequest;
import com.capsule.agentkey.dto.McpServerResponse;
import com.capsule.agentkey.entity.McpServer;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.mcp.McpServerRuntime;
import com.capsule.agentkey.repository.McpServerRepository;
import com.capsule.agentkey.util.AesUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * M1：用户自助 MCP server 的配置管理。
 *
 * <p>职责边界：只做校验、持久化与调用运行时；进程启动/工具注册在 {@link McpServerRuntime}。
 * 环境变量视为可能含令牌的敏感数据，落库前 AES-GCM 加密，接口只返回变量名。
 */
@Service
@Slf4j
public class McpServerService {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,39}");
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> RESERVED_NAMES = Set.of("filesystem", "git", "unknown");
    private static final int MAX_ARGS = 32;
    private static final int MAX_ARG_CHARS = 1_000;
    private static final int MAX_ENV_ENTRIES = 32;
    private static final int MAX_ENV_VALUE_CHARS = 4_000;
    private static final int MAX_ENV_JSON_CHARS = 16_000;

    private final McpServerRepository repository;
    private final McpServerRuntime runtime;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;

    public McpServerService(McpServerRepository repository, McpServerRuntime runtime,
                            AesUtil aesUtil, ObjectMapper objectMapper) {
        this.repository = repository;
        this.runtime = runtime;
        this.aesUtil = aesUtil;
        this.objectMapper = objectMapper;
    }

    public List<McpServerResponse> list(Long userId) {
        List<McpServerResponse> result = new ArrayList<>();
        for (McpServer server : repository.findByUserIdOrderByIdAsc(userId)) {
            result.add(toResponse(server));
        }
        return result;
    }

    public McpServerResponse create(Long userId, McpServerRequest request) {
        return createWithPlugin(userId, request, null, null);
    }

    /** 从市场安装：额外写入 pluginId/pluginVersion 标记来源；手填路径传 null。 */
    public McpServerResponse createWithPlugin(Long userId, McpServerRequest request,
                                              String pluginId, String pluginVersion) {
        Validated validated = validate(request, userId, null, null);
        McpServer server = new McpServer();
        server.setUserId(userId);
        server.setPluginId(pluginId);
        server.setPluginVersion(pluginVersion);
        apply(server, validated);
        McpServer saved = repository.save(server);
        runtime.reload(saved);
        return toResponse(saved);
    }

    public McpServerResponse update(Long userId, Long id, McpServerRequest request) {
        McpServer server = owned(userId, id);
        Validated validated = validate(request, userId, id, server.isEnabled());
        String oldName = server.getName();
        apply(server, validated);
        McpServer saved = repository.save(server);
        if (!oldName.equals(saved.getName())) {
            runtime.remove(id, oldName);
        }
        runtime.reload(saved);
        return toResponse(saved);
    }

    public McpServerResponse setEnabled(Long userId, Long id, boolean enabled) {
        McpServer server = owned(userId, id);
        server.setEnabled(enabled);
        McpServer saved = repository.save(server);
        // 开启时建立连接；关闭时 reload 会先断开再跳过启动
        runtime.reload(saved);
        return toResponse(saved);
    }

    public void delete(Long userId, Long id) {
        McpServer server = owned(userId, id);
        runtime.remove(id, server.getName());
        repository.delete(server);
    }

    public McpServerResponse reload(Long userId, Long id) {
        McpServer server = owned(userId, id);
        runtime.reload(server);
        return toResponse(server);
    }

    private McpServer owned(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "MCP server 不存在"));
    }

    /** 校验并归一化；env 为 null 表示更新时保持原值，enabled 为 null 表示沿用当前值 */
    private Validated validate(McpServerRequest request, Long userId, Long id,
                               Boolean currentEnabled) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (!NAME.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "名称只能包含字母、数字、下划线和连字符，长度 1-40");
        }
        if (RESERVED_NAMES.contains(name.toLowerCase())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "名称 " + name + " 为系统保留名");
        }
        boolean duplicate = id == null
                ? repository.existsByUserIdAndName(userId, name)
                : repository.existsByUserIdAndNameAndIdNot(userId, name, id);
        if (duplicate) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "名称已存在: " + name);
        }

        String command = request.command() == null ? "" : request.command().trim();
        if (command.isBlank() || command.length() > 512) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "命令不能为空且不超过 512 字符");
        }
        if (command.chars().anyMatch(ch -> ch < 0x20)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "命令不能包含控制字符");
        }

        List<String> args = request.args() == null ? List.of() : request.args();
        if (args.size() > MAX_ARGS) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "启动参数不能超过 " + MAX_ARGS + " 个");
        }
        List<String> normalizedArgs = new ArrayList<>();
        for (String arg : args) {
            if (arg == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "启动参数不能为 null");
            }
            if (arg.length() > MAX_ARG_CHARS) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "单个启动参数不能超过 " + MAX_ARG_CHARS + " 字符");
            }
            normalizedArgs.add(arg);
        }

        Map<String, String> env = request.env();
        if (env != null) {
            if (env.size() > MAX_ENV_ENTRIES) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "环境变量不能超过 " + MAX_ENV_ENTRIES + " 个");
            }
            int total = 0;
            for (Map.Entry<String, String> entry : env.entrySet()) {
                if (entry.getKey() == null || !ENV_KEY.matcher(entry.getKey()).matches()) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "环境变量名不合法: " + entry.getKey());
                }
                String value = entry.getValue() == null ? "" : entry.getValue();
                if (value.length() > MAX_ENV_VALUE_CHARS) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "环境变量 " + entry.getKey() + " 的值过长");
                }
                total += entry.getKey().length() + value.length();
            }
            if (total > MAX_ENV_JSON_CHARS) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "环境变量总大小超过限制");
            }
        }

        boolean enabled = request.enabled() != null
                ? request.enabled()
                : currentEnabled == null || currentEnabled;
        return new Validated(name, command, normalizedArgs, env, enabled);
    }

    private void apply(McpServer server, Validated validated) {
        server.setName(validated.name());
        server.setCommand(validated.command());
        server.setEnabled(validated.enabled());
        try {
            server.setArgsJson(objectMapper.writeValueAsString(validated.args()));
            if (validated.env() != null) {
                server.setEnvEncrypted(validated.env().isEmpty() ? null
                        : aesUtil.encrypt(objectMapper.writeValueAsString(validated.env())));
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "保存 MCP 配置失败: " + e.getMessage());
        }
    }

    private McpServerResponse toResponse(McpServer server) {
        List<String> args;
        try {
            args = server.getArgsJson() == null || server.getArgsJson().isBlank()
                    ? List.of()
                    : objectMapper.readValue(server.getArgsJson(), new TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            args = List.of();
        }
        List<String> envKeys = new ArrayList<>();
        if (server.getEnvEncrypted() != null && !server.getEnvEncrypted().isBlank()) {
            try {
                Map<String, String> env = objectMapper.readValue(
                        aesUtil.decrypt(server.getEnvEncrypted()),
                        new TypeReference<Map<String, String>>() {
                        });
                envKeys = new ArrayList<>(new TreeSet<>(env.keySet()));
            } catch (Exception e) {
                log.warn("MCP server [{}] 环境变量解密失败: {}", server.getName(), e.getMessage());
            }
        }
        McpServerRuntime.Status status = runtime.status(server.getId());
        return new McpServerResponse(server.getId(), server.getName(), server.getCommand(),
                args, envKeys, server.isEnabled(), status.connected(), status.toolCount(),
                status.lastError(), server.getPluginId(), server.getPluginVersion());
    }

    private record Validated(String name, String command, List<String> args,
                             Map<String, String> env, boolean enabled) {
    }
}
