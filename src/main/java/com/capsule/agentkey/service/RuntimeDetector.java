package com.capsule.agentkey.service;

import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行时依赖检测（后端兜底）：解析 manifest.runtime 如 "node>=18"，
 * 跑本机可执行文件拿版本，校验是否满足。前端绕过安装时这里仍会拦截。
 */
@Service
public class RuntimeDetector {

    /** 支持的运行时 → 取版本号的命令。阶段 1：node / uvx / python */
    private static final Map<String, String[]> VERSION_COMMANDS = Map.of(
            "node", new String[]{"node", "--version"},
            "uvx", new String[]{"uvx", "--version"},
            "python", new String[]{"python", "--version"}
    );

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private static final Pattern SPEC_PATTERN =
            Pattern.compile("^([a-zA-Z0-9_.-]+)\\s*(>=|<=|==|>|<|=)?\\s*(.*)$");

    /** 解析后的运行时要求 */
    public record RuntimeRequirement(String name, String op, String version) {
    }

    /** 探测本机运行时版本号（如 "v20.10.0"）；不存在/超时/失败返回 null */
    public String detect(String name) {
        if (name == null) {
            return null;
        }
        String[] cmd = VERSION_COMMANDS.get(name);
        if (cmd == null) {
            return null;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || output.isEmpty()) {
                return null;
            }
            return output;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    /** 解析 "node>=18" → RuntimeRequirement("node", ">=", "18") */
    public RuntimeRequirement parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("runtime spec 不能为空");
        }
        Matcher m = SPEC_PATTERN.matcher(spec.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("无法解析运行时要求: " + spec);
        }
        String op = m.group(2);
        String version = m.group(3);
        return new RuntimeRequirement(
                m.group(1),
                op == null ? "" : op,
                version == null ? "" : version.trim());
    }

    /** 校验一条运行时要求；不满足抛 PARAM_INVALID（消息可直接展示给用户） */
    public void require(String spec) {
        RuntimeRequirement req = parse(spec);
        String detected = detect(req.name());
        if (detected == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "缺少运行时依赖：需要 " + req.name() + formatConstraint(req));
        }
        if (!satisfies(detected, req)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "运行时版本过低：需要 " + req.name() + formatConstraint(req)
                            + "，当前检测到 " + detected);
        }
    }

    /** 批量校验 */
    public void requireAll(List<String> specs) {
        if (specs == null || specs.isEmpty()) {
            return;
        }
        for (String spec : specs) {
            require(spec);
        }
    }

    private boolean satisfies(String detected, RuntimeRequirement req) {
        if (req.op().isEmpty() || req.version().isEmpty()) {
            return true; // 只要求存在
        }
        int cmp = compareSemver(detected, req.version());
        return switch (req.op()) {
            case ">=" -> cmp >= 0;
            case ">" -> cmp > 0;
            case "==", "=" -> cmp == 0;
            case "<=" -> cmp <= 0;
            case "<" -> cmp < 0;
            default -> true;
        };
    }

    private String formatConstraint(RuntimeRequirement req) {
        if (req.op().isEmpty() || req.version().isEmpty()) {
            return "";
        }
        return " " + req.op() + req.version();
    }

    /** 语义化版本比较：忽略 v 前缀，按点分段逐段比较整数，缺段补 0 */
    static int compareSemver(String a, String b) {
        int[] pa = parseParts(a);
        int[] pb = parseParts(b);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int x = i < pa.length ? pa[i] : 0;
            int y = i < pb.length ? pb[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int[] parseParts(String version) {
        if (version == null) {
            return new int[]{0};
        }
        String s = version.replaceAll("^[vV]", "");
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c) || c == '.') {
                sb.append(c);
            } else {
                break; // 遇到 -alpha / 空格等停止
            }
        }
        if (sb.length() == 0) {
            return new int[]{0};
        }
        String[] parts = sb.toString().split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }
}
