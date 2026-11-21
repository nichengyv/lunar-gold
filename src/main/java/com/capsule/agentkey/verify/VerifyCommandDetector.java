package com.capsule.agentkey.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * R7c L5：没有显式 `.lunargold/verify.json` 时，按工作区构建文件自动探测一条验证命令。
 *
 * <p>背景：L3 真实模型冒烟中，所有 turn 的完成原因都是 {@code UNVERIFIED:VERIFY_NOT_CONFIGURED}，
 * 模型只能凭「变更文件诊断无错误」自称完成。多数真实工作区本身带有 pom.xml / package.json 等
 * 构建文件，不配置 verify.json 也能做工具级验证。
 *
 * <p>约束：
 * <ul>
 *   <li>只产出白名单内的固定命令，命令文本不含任何工作区内容，杜绝注入；</li>
 *   <li>只探测工作区根目录，不递归、不读脚本内容，避免把恶意文件当验证命令；</li>
 *   <li>命中第一条即停，最多一条命令，保证验证耗时可控；</li>
 *   <li>不使用 {@code ./mvnw} 这类工作区相对脚本（CommandService 明确禁止执行工作区内脚本）。</li>
 * </ul>
 */
final class VerifyCommandDetector {

    /** 探测结果：来源文件 + 单条待执行命令 */
    record Detected(String source, VerifyConfig.VerifyCommand command) {
    }

    private VerifyCommandDetector() {
    }

    /**
     * 探测验证命令；没有可识别的构建文件时返回空。
     *
     * @param root           工作区根目录（已规范化）
     * @param timeoutSeconds 自动探测命令的超时秒数
     */
    static Optional<Detected> detect(Path root, int timeoutSeconds) {
        if (root == null || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        Integer timeout = timeoutSeconds > 0 ? timeoutSeconds : null;

        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return Optional.of(command("pom.xml", "mvn-compile",
                    "mvn -B -q -DskipTests compile", timeout));
        }
        if (Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            return Optional.of(command("build.gradle.kts", "gradle-compileJava",
                    "gradle -q compileJava", timeout));
        }
        if (Files.isRegularFile(root.resolve("build.gradle"))) {
            return Optional.of(command("build.gradle", "gradle-compileJava",
                    "gradle -q compileJava", timeout));
        }
        Optional<Detected> npm = detectNpm(root, timeout);
        if (npm.isPresent()) {
            return npm;
        }
        if (Files.isRegularFile(root.resolve("go.mod"))) {
            return Optional.of(command("go.mod", "go-build", "go build ./...", timeout));
        }
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) {
            return Optional.of(command("Cargo.toml", "cargo-check", "cargo check --quiet", timeout));
        }
        if (Files.isRegularFile(root.resolve("tsconfig.json"))) {
            return Optional.of(command("tsconfig.json", "tsc-typecheck", "tsc --noEmit", timeout));
        }
        return Optional.empty();
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
