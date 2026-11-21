package com.capsule.agentkey.verify;

import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.CommandService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P5-06：项目验证回路。
 *
 * <p>读取工作区 `.lunargold/verify.json`，按顺序通过 {@link CommandService} 执行命令。
 * 所有异常都转换成 {@link VerifyResult.Status}，不向 ReAct 主循环抛出；只有显式预算耗尽
 * 才由调用方停止。
 */
@Service
@Slf4j
public class VerifyService {

    private final AgentVerifyProperties properties;
    private final CommandService commandService;
    private final ObjectMapper mapper = new ObjectMapper();

    public VerifyService(AgentVerifyProperties properties, CommandService commandService) {
        this.properties = properties;
        this.commandService = commandService;
    }

    /**
     * 执行一次验证。
     *
     * @param workspaceRoot 工作区根目录
     * @param attempt       当前 run 内的第几次验证（从 1 开始）
     * @param cancelled     用户显式停止标记（断流不会置位）
     */
    public VerifyResult verify(Path workspaceRoot, int attempt, AtomicBoolean cancelled) {
        int safeAttempt = Math.max(1, attempt);
        String configPath = properties.getConfigPath();
        if (!properties.isEnabled()) {
            return notConfigured(safeAttempt, 1, configPath, "验证已在服务端关闭");
        }
        if (workspaceRoot == null) {
            return unavailable(safeAttempt, 1, configPath, false, List.of(), "工作区为空，无法执行验证");
        }

        Path root;
        try {
            root = workspaceRoot.toAbsolutePath().normalize();
        } catch (Exception e) {
            return unavailable(safeAttempt, 1, configPath, false, List.of(), "工作区路径无效：" + e.getMessage());
        }

        VerifyConfig config;
        String autoSource = null;
        try {
            config = load(root);
        } catch (Exception e) {
            log.warn("验证配置无法解析: path={}, error={}", configPath, e.getMessage());
            return unavailable(safeAttempt, 1, configPath, false, List.of(),
                    "验证配置无法解析：" + e.getMessage());
        }
        if (config == null) {
            //R7c L5：没有显式配置时按构建文件自动探测，让完成判定仍能走工具验证
            VerifyCommandDetector.Detected detected = properties.isAutoDetect()
                    ? VerifyCommandDetector.detect(root, properties.getAutoDetectTimeoutSeconds())
                            .orElse(null)
                    : null;
            if (detected == null) {
                return notConfigured(safeAttempt, 1, configPath,
                        properties.isAutoDetect()
                                ? "项目未配置验证命令，也未探测到可识别的构建文件"
                                : "项目未配置验证命令");
            }
            config = new VerifyConfig(Boolean.TRUE, 0, List.of(detected.command()));
            autoSource = detected.source();
            log.info("验证命令自动探测: workspace={}, source={}, command={}",
                    root, autoSource, detected.command().command());
        }

        int maxAttempts = maxAttempts(config);
        if (!config.isEnabled()) {
            return notConfigured(safeAttempt, maxAttempts, configPath, "验证配置已关闭");
        }
        if (config.commands().isEmpty()) {
            return notConfigured(safeAttempt, maxAttempts, configPath, "验证配置没有命令");
        }
        for (int i = 0; i < config.commands().size(); i++) {
            VerifyConfig.VerifyCommand command = config.commands().get(i);
            if (command.command() == null || command.command().isBlank()) {
                return unavailable(safeAttempt, maxAttempts, configPath, false, List.of(),
                        "第 " + (i + 1) + " 条验证命令为空");
            }
        }

        List<VerifyResult.CommandOutcome> outcomes = new ArrayList<>();
        boolean requiredFailed = false;
        boolean optionalFailed = false;

        for (VerifyConfig.VerifyCommand command : config.commands()) {
            if (cancelled != null && cancelled.get()) {
                return unavailable(safeAttempt, maxAttempts, configPath, true, outcomes, "验证已取消");
            }
            String name = command.name().isBlank() ? firstToken(command.command()) : command.name();
            Integer timeout = command.timeoutSeconds() == null || command.timeoutSeconds() <= 0
                    ? properties.getDefaultTimeoutSeconds() : command.timeoutSeconds();
            try {
                CommandService.CommandResult result = commandService.run(
                        root.toString(), command.command(), command.cwd(), timeout, cancelled);
                String tail = tail(result.output());
                outcomes.add(new VerifyResult.CommandOutcome(
                        name, command.command(), command.cwd(), command.isRequired(),
                        result.exitCode(), result.timedOut(), result.durationMs(), false, tail));
                boolean failed = result.timedOut() || result.exitCode() != 0;
                if (failed) {
                    if (command.isRequired()) {
                        requiredFailed = true;
                    } else {
                        optionalFailed = true;
                    }
                }
                log.info("验证命令审计: config={}, name={}, command={}, required={}, exit={}, "
                                + "timedOut={}, cost={}ms",
                        configPath, name, command.command(), command.isRequired(),
                        result.exitCode(), result.timedOut(), result.durationMs());
                if (result.cancelled() || (cancelled != null && cancelled.get())) {
                    return unavailable(safeAttempt, maxAttempts, configPath, true, outcomes, "验证已取消");
                }
            } catch (BusinessException e) {
                // 禁用、白名单、找不到可执行程序、启动失败、目录不存在 → 环境不可用
                log.warn("验证命令无法执行: name={}, command={}, error={}",
                        name, command.command(), e.getMessage());
                return unavailable(safeAttempt, maxAttempts, configPath, true, outcomes, e.getMessage());
            } catch (Exception e) {
                log.warn("验证命令执行异常: name={}, command={}", name, command.command(), e);
                return unavailable(safeAttempt, maxAttempts, configPath, true, outcomes,
                        "验证命令执行异常：" + e.getMessage());
            }
        }

        if (requiredFailed) {
            long failedCount = outcomes.stream()
                    .filter(o -> !o.skipped() && o.required() && (o.timedOut() || o.exitCode() != 0))
                    .count();
            return new VerifyResult(VerifyResult.Status.FAILED, configPath, true,
                    safeAttempt, maxAttempts, outcomes, fingerprint(outcomes),
                    failedCount + " 条必跑验证命令失败" + autoNote(autoSource));
        }

        String message = optionalFailed
                ? "必跑验证命令通过，但存在可选命令失败" + autoNote(autoSource)
                : "验证命令全部通过（" + outcomes.size() + " 条）" + autoNote(autoSource);
        return new VerifyResult(VerifyResult.Status.PASSED, configPath, true,
                safeAttempt, maxAttempts, outcomes, "", message);
    }

    /** 自动探测时在结论里显式标注来源，避免用户误以为是自己配置的命令 */
    private String autoNote(String autoSource) {
        return autoSource == null ? "" : "（自动探测自 " + autoSource + "）";
    }

    /**
     * 读取项目验证配置；文件不存在返回 null，格式错误抛出异常由调用方转为 UNAVAILABLE。
     * 包内可见，便于测试直接断言默认值与路径校验。
     */
    VerifyConfig load(Path workspaceRoot) throws IOException {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path file = root.resolve(properties.getConfigPath()).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("验证配置路径越界：" + properties.getConfigPath());
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return mapper.readValue(file.toFile(), VerifyConfig.class);
    }

    private int maxAttempts(VerifyConfig config) {
        int global = Math.max(0, Math.min(2, properties.getMaxRetries()));
        return 1 + Math.min(config.maxRetriesValue(), global);
    }

    private VerifyResult notConfigured(int attempt, int maxAttempts, String configPath, String reason) {
        return new VerifyResult(VerifyResult.Status.NOT_CONFIGURED, configPath, false,
                attempt, maxAttempts, List.of(), "",
                reason + "（" + configPath + "）");
    }

    private VerifyResult unavailable(int attempt, int maxAttempts, String configPath,
                                     boolean configured, List<VerifyResult.CommandOutcome> outcomes,
                                     String reason) {
        return new VerifyResult(VerifyResult.Status.UNAVAILABLE, configPath, configured,
                attempt, maxAttempts, outcomes, "", reason);
    }

    /** 输出尾部：构建错误通常在末尾，保留尾部比头部更有用 */
    private String tail(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        int limit = Math.max(200, properties.getMaxOutputChars());
        String normalized = output.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.length() <= limit
                ? normalized
                : normalized.substring(normalized.length() - limit);
    }

    private String firstToken(String command) {
        String trimmed = command == null ? "" : command.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    /** 同一失败内容产生稳定指纹；只有失败命令参与计算，顺序与命令配置一致 */
    static String fingerprint(List<VerifyResult.CommandOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (VerifyResult.CommandOutcome outcome : outcomes) {
            if (outcome.skipped() || (outcome.exitCode() == 0 && !outcome.timedOut())) {
                continue;
            }
            sb.append(outcome.name()).append('|')
                    .append(outcome.command()).append('|')
                    .append(outcome.exitCode()).append('|')
                    .append(outcome.timedOut()).append('|')
                    .append(outcome.outputTail() == null ? ""
                            : outcome.outputTail().trim().replaceAll("\\s+", " "))
                    .append('\n');
        }
        if (sb.length() == 0) {
            return "";
        }
        return sha256(sb.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 32);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
