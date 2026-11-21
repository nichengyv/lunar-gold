package com.capsule.agentkey.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7c L5：无 verify.json 时的构建文件探测规则。
 */
class VerifyCommandDetectorTest {

    @TempDir
    Path workspace;

    @Test
    void 空工作区不探测任何命令() {
        assertTrue(VerifyCommandDetector.detect(workspace, 300).isEmpty());
    }

    @Test
    void maven项目优先探测且不执行工作区脚本() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");

        Optional<VerifyCommandDetector.Detected> detected = VerifyCommandDetector.detect(workspace, 300);

        assertTrue(detected.isPresent());
        assertEquals("pom.xml", detected.get().source());
        assertEquals("mvn -B -q -DskipTests compile", detected.get().command().command());
        assertFalse(detected.get().command().command().startsWith("./"),
                "CommandService 禁止执行工作区相对脚本");
        assertTrue(detected.get().command().isRequired());
    }

    @Test
    void gradle与go与cargo与tsc按各自构建文件探测() throws Exception {
        Files.writeString(workspace.resolve("build.gradle.kts"), "");
        assertEquals("gradle -q compileJava",
                VerifyCommandDetector.detect(workspace, 300).orElseThrow().command().command());

        Files.delete(workspace.resolve("build.gradle.kts"));
        Files.writeString(workspace.resolve("go.mod"), "module demo");
        assertEquals("go build ./...",
                VerifyCommandDetector.detect(workspace, 300).orElseThrow().command().command());

        Files.delete(workspace.resolve("go.mod"));
        Files.writeString(workspace.resolve("Cargo.toml"), "[package]");
        assertEquals("cargo check --quiet",
                VerifyCommandDetector.detect(workspace, 300).orElseThrow().command().command());

        Files.delete(workspace.resolve("Cargo.toml"));
        Files.writeString(workspace.resolve("tsconfig.json"), "{}");
        assertEquals("tsc --noEmit",
                VerifyCommandDetector.detect(workspace, 300).orElseThrow().command().command());
    }

    @Test
    void npm脚本按typecheck_build_test优先级选择() throws Exception {
        Files.writeString(workspace.resolve("package.json"), """
                {"scripts":{"test":"vitest run","build":"vite build","typecheck":"vue-tsc --noEmit"}}
                """);
        assertEquals("npm run typecheck",
                VerifyCommandDetector.detect(workspace, 300).orElseThrow().command().command());

        Files.writeString(workspace.resolve("package.json"),
                "{\"scripts\":{\"test\":\"vitest run\",\"build\":\"vite build\"}}");
        assertEquals("npm run build",
                VerifyCommandDetector.detect(workspace, 300).orElseThrow().command().command());

        Files.writeString(workspace.resolve("package.json"), "{\"scripts\":{}}");
        assertTrue(VerifyCommandDetector.detect(workspace, 300).isEmpty());
    }

    @Test
    void packageJson损坏时继续尝试后续规则() throws Exception {
        Files.writeString(workspace.resolve("package.json"), "{ not-json");
        Files.writeString(workspace.resolve("go.mod"), "module demo");

        assertEquals("go build ./...",
                VerifyCommandDetector.detect(workspace, 300).orElseThrow().command().command());
    }

    @Test
    void 探测命令带上自动探测超时() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");

        assertEquals(300, VerifyCommandDetector.detect(workspace, 300)
                .orElseThrow().command().timeoutSeconds());
        assertTrue(VerifyCommandDetector.detect(workspace, 0).orElseThrow()
                .command().timeoutSeconds() == null, "非法超时应交给服务端默认值");
    }
}
