package com.capsule.agentkey.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        List<VerifyCommandDetector.Detected> detected = VerifyCommandDetector.detect(workspace, 300);

        assertEquals(1, detected.size());
        assertEquals("pom.xml", detected.get(0).source());
        assertEquals("mvn -B -q -DskipTests compile", detected.get(0).command().command());
        assertFalse(detected.get(0).command().command().startsWith("./"),
                "CommandService 禁止执行工作区相对脚本");
        assertTrue(detected.get(0).command().isRequired());
    }

    @Test
    void gradle与go与cargo与tsc按各自构建文件探测() throws Exception {
        Files.writeString(workspace.resolve("build.gradle.kts"), "");
        assertEquals("gradle -q compileJava",
                VerifyCommandDetector.detect(workspace, 300).get(0).command().command());

        Files.delete(workspace.resolve("build.gradle.kts"));
        Files.writeString(workspace.resolve("go.mod"), "module demo");
        assertEquals("go build ./...",
                VerifyCommandDetector.detect(workspace, 300).get(0).command().command());

        Files.delete(workspace.resolve("go.mod"));
        Files.writeString(workspace.resolve("Cargo.toml"), "[package]");
        assertEquals("cargo check --quiet",
                VerifyCommandDetector.detect(workspace, 300).get(0).command().command());

        Files.delete(workspace.resolve("Cargo.toml"));
        Files.writeString(workspace.resolve("tsconfig.json"), "{}");
        assertEquals("tsc --noEmit",
                VerifyCommandDetector.detect(workspace, 300).get(0).command().command());
    }

    @Test
    void npm脚本按typecheck_build_test优先级选择() throws Exception {
        Files.writeString(workspace.resolve("package.json"), """
                {"scripts":{"test":"vitest run","build":"vite build","typecheck":"vue-tsc --noEmit"}}
                """);
        assertEquals("npm run typecheck",
                VerifyCommandDetector.detect(workspace, 300).get(0).command().command());

        Files.writeString(workspace.resolve("package.json"),
                "{\"scripts\":{\"test\":\"vitest run\",\"build\":\"vite build\"}}");
        assertEquals("npm run build",
                VerifyCommandDetector.detect(workspace, 300).get(0).command().command());

        Files.writeString(workspace.resolve("package.json"), "{\"scripts\":{}}");
        assertTrue(VerifyCommandDetector.detect(workspace, 300).isEmpty());
    }

    @Test
    void packageJson损坏时继续尝试后续规则() throws Exception {
        Files.writeString(workspace.resolve("package.json"), "{ not-json");
        Files.writeString(workspace.resolve("go.mod"), "module demo");

        assertEquals("go build ./...",
                VerifyCommandDetector.detect(workspace, 300).get(0).command().command());
    }

    @Test
    void 探测命令带上自动探测超时() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");

        assertEquals(300, VerifyCommandDetector.detect(workspace, 300)
                .get(0).command().timeoutSeconds());
        assertTrue(VerifyCommandDetector.detect(workspace, 0).get(0)
                .command().timeoutSeconds() == null, "非法超时应交给服务端默认值");
    }

    @Test
    void 后端与前端同仓库时全部探测且按固定顺序执行() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Files.writeString(workspace.resolve("package.json"),
                "{\"scripts\":{\"build\":\"vite build\"}}");

        List<VerifyCommandDetector.Detected> detected = VerifyCommandDetector.detect(workspace, 300);

        assertEquals(2, detected.size());
        assertEquals("pom.xml", detected.get(0).source());
        assertEquals("mvn -B -q -DskipTests compile", detected.get(0).command().command());
        assertEquals("package.json", detected.get(1).source());
        assertEquals("npm run build", detected.get(1).command().command());
        assertTrue(detected.stream().allMatch(d -> d.command().isRequired()));
    }

    @Test
    void 探测命令数量受上限约束() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Files.writeString(workspace.resolve("package.json"),
                "{\"scripts\":{\"build\":\"vite build\"}}");
        Files.writeString(workspace.resolve("go.mod"), "module demo");
        Files.writeString(workspace.resolve("Cargo.toml"), "[package]");
        Files.writeString(workspace.resolve("tsconfig.json"), "{}");

        List<VerifyCommandDetector.Detected> detected = VerifyCommandDetector.detect(workspace, 300);

        assertEquals(VerifyCommandDetector.MAX_COMMANDS, detected.size());
        assertEquals(List.of("pom.xml", "package.json", "go.mod"),
                detected.stream().map(VerifyCommandDetector.Detected::source).toList());
    }

    @Test
    void gradle两种文件名只执行一次相同命令() throws Exception {
        Files.writeString(workspace.resolve("build.gradle.kts"), "");
        Files.writeString(workspace.resolve("build.gradle"), "");

        List<VerifyCommandDetector.Detected> detected = VerifyCommandDetector.detect(workspace, 300);

        assertEquals(1, detected.size());
        assertEquals("build.gradle.kts", detected.get(0).source());
    }
}
