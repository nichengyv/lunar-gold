package com.capsule.agentkey.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R7c L5：没有显式 `.lunargold/verify.json` 时，按工作区构建文件自动探测验证命令。
 *
 * <p>背景：L3 真实模型冒烟中，所有 turn 的完成原因都是 {@code UNVERIFIED:VERIFY_NOT_CONFIGURED}，
 * 模型只能凭「变更文件诊断无错误」自称完成。多数真实工作区本身带有 pom.xml / package.json 等
 * 构建文件，不配置 verify.json 也能做工具级验证。
 *
 * <p>约束：
 * <ul>
 *   <li>只产出白名单内的固定命令，命令文本不含任何工作区内容，杜绝注入；</li>
 *   <li>只探测工作区根目录，不递归、不读脚本内容，避免把恶意文件当验证命令；</li>
 *   <li>按固定优先级收集全部可识别构建文件，最多 {@value #MAX_COMMANDS} 条，保证验证耗时有界；</li>
 *   <li>同一命令文本只执行一次（例如 build.gradle.kts 与 build.gradle 并存）；</li>
 *   <li>不使用 {@code ./mvnw} 这类工作区相对脚本（CommandService 明确禁止执行工作区内脚本）。</li>
 * </ul>
 */
final class VerifyCommandDetector {

    /** 单次自动验证最多执行的命令数；覆盖常见「后端 + 前端」同仓库结构 */
    static final int MAX_COMMANDS = 3;

    /** 探测结果：来源文件 + 单条待执行命令 */
    record Detected(String source, VerifyConfig.VerifyCommand command) {
    }

    private VerifyCommandDetector() {
    }

    /**
     * 探测验证命令；没有可识别的构建文件时返回空列表。
     *
     * @param root           工作区根目录（已规范化）
     * @param timeoutSeconds 自动探测命令的超时秒数
     */
    static List<Detected> detect(Path root, int timeoutSeconds) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        Integer timeout = timeoutSeconds > 0 ? timeoutSeconds : null;
        Map<String, Detected> detected = new LinkedHashMap<>();

        addIfPresent(detected, root, "pom.xml", "mvn-compile",
                "mvn -B -q -DskipTests compile", timeout);
        addIfPresent(detected, root, "build.gradle.kts", "gradle-compileJava",
                "gradle -q compileJava", timeout);
        addIfPresent(detected, root, "build.gradle", "gradle-compileJava",
                "gradle -q compileJava", timeout);
        detectNpm(root, timeout).ifPresent(npm ->
                detected.putIfAbsent(npm.command().command(), npm));
        addIfPresent(detected, root, "go.mod", "go-build", "go build ./...", timeout);
        addIfPresent(detected, root, "Cargo.toml", "cargo-check", "cargo check --quiet", timeout);
        addIfPresent(detected, root, "tsconfig.json", "tsc-typecheck", "tsc --noEmit", timeout);

        return detected.values().stream().limit(MAX_COMMANDS).toList();
    }

    private static void addIfPresent(Map<String, Detected> detected, Path root, String file,
                                     String name, String command, Integer timeout) {
        if (Files.isRegularFile(root.resolve(file))) {
            Detected value = command(file, name, command, timeout);
            detected.putIfAbsent(value.command().command(), value);
        }
    }

    /**
     * package.json：只有声明了可用脚本才探测，优先最严格的类型检查，其次构建，最后测试。
     * 解析失败按「无 package.json」处理，不影响后续规则。
     */
    private static Optional<Detected> detectNpm(Path root, Integer timeout) {
        Path file = root.resolve("package.json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        JsonNode scripts;
        try {
            JsonNode json = new ObjectMapper().readTree(file.toFile());
            scripts = json == null ? null : json.get("scripts");
        } catch (Exception e) {
            return Optional.empty();
        }
        if (scripts == null || !scripts.isObject()) {
            return Optional.empty();
        }
        for (String script : List.of("typecheck", "build", "test")) {
            JsonNode value = scripts.get(script);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return Optional.of(command("package.json", "npm-" + script,
                        "npm run " + script, timeout));
            }
        }
        return Optional.empty();
    }

    private static Detected command(String source, String name, String command, Integer timeout) {
        return new Detected(source,
                new VerifyConfig.VerifyCommand(name, command, "", timeout, Boolean.TRUE));
    }
}
