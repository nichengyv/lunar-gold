package com.capsule.agentkey.verify;

import com.capsule.agentkey.config.AgentVerifyProperties;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.CommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5-06：验证配置解析、状态映射、预算与失败指纹。
 */
class VerifyServiceTest {

    @TempDir
    Path workspace;

    private CommandService commandService;
    private AgentVerifyProperties properties;
    private VerifyService service;

    @BeforeEach
    void setUp() {
        commandService = mock(CommandService.class);
        properties = new AgentVerifyProperties();
        service = new VerifyService(properties, commandService);
    }

    @Test
    void missingConfigIsNotConfiguredAndDoesNotRunCommands() {
        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.NOT_CONFIGURED, result.status());
        assertFalse(result.configured());
        assertEquals(1, result.maxAttempts());
        verify(commandService, never()).run(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void invalidJsonIsUnavailableInsteadOfThrowing() throws Exception {
        writeConfig("{ not-json");

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.UNAVAILABLE, result.status());
        assertTrue(result.message().contains("无法解析"));
        verify(commandService, never()).run(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void allRequiredCommandsPass() throws Exception {
        writeConfig("""
                {"commands":[{"name":"compile","command":"mvn -q compile","required":true}]}
                """);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(success("build ok"));

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.PASSED, result.status());
        assertTrue(result.configured());
        assertEquals(1, result.commands().size());
        assertEquals(".lunargold/verify.json", result.configPath());
        assertEquals(1, result.attempt());
        assertEquals(3, result.maxAttempts());
        assertEquals("", result.failureFingerprint());
        assertTrue(result.formatForPrompt().contains("验证命令全部通过"));
    }

    @Test
    void requiredCommandFailureIsFailedAndFedBack() throws Exception {
        writeConfig("""
                {"commands":[{"name":"compile","command":"mvn -q compile","required":true}]}
                """);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(failure(1, "Foo.java:12: cannot find symbol"));

        VerifyResult result = service.verify(workspace, 2, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.FAILED, result.status());
        assertFalse(result.failureFingerprint().isBlank());
        assertEquals(2, result.attempt());
        String prompt = result.formatForPrompt();
        assertTrue(prompt.contains("验证失败"));
        assertTrue(prompt.contains("cannot find symbol"));
        assertTrue(prompt.contains("请修复"));
    }

    @Test
    void optionalCommandFailureDoesNotFailVerification() throws Exception {
        writeConfig("""
                {"commands":[
                  {"name":"compile","command":"mvn -q compile","required":true},
                  {"name":"lint","command":"npm run lint","required":false}
                ]}
                """);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(success("ok"), failure(2, "lint warning"));

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.PASSED, result.status());
        assertTrue(result.message().contains("可选"));
        assertEquals(2, result.commands().size());
    }

    @Test
    void executableFailureIsUnavailableNotFailed() throws Exception {
        writeConfig("""
                {"commands":[{"name":"compile","command":"mvn -q compile","required":true}]}
                """);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new BusinessException("找不到可执行程序：mvn"));

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.UNAVAILABLE, result.status());
        assertTrue(result.configured());
        assertTrue(result.message().contains("找不到可执行程序"));
        assertTrue(result.formatForPrompt().contains("不要声称验证已通过"));
    }

    @Test
    void retryCountIsCappedAtTwoEvenIfProjectAsksForMore() throws Exception {
        writeConfig("""
                {"maxRetries":5,"commands":[{"name":"compile","command":"mvn -q compile"}]}
                """);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(success("ok"));

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(3, result.maxAttempts());
    }

    @Test
    void failureFingerprintIsStableAndContentSensitive() {
        VerifyResult.CommandOutcome first = new VerifyResult.CommandOutcome(
                "compile", "mvn compile", "", true, 1, false, 10, false, "error A");
        VerifyResult.CommandOutcome same = new VerifyResult.CommandOutcome(
                "compile", "mvn compile", "", true, 1, false, 99, false, "error A");
        VerifyResult.CommandOutcome different = new VerifyResult.CommandOutcome(
                "compile", "mvn compile", "", true, 1, false, 10, false, "error B");

        String fingerprint = VerifyService.fingerprint(List.of(first));
        assertNotNull(fingerprint);
        assertEquals(fingerprint, VerifyService.fingerprint(List.of(same)));
        assertFalse(fingerprint.equals(VerifyService.fingerprint(List.of(different))));
    }

    @Test
    void outputTailIsTrimmedToConfiguredLimit() throws Exception {
        properties.setMaxOutputChars(250);
        writeConfig("""
                {"commands":[{"name":"compile","command":"mvn compile"}]}
                """);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(failure(1, "x".repeat(600) + "END"));

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        String tail = result.commands().get(0).outputTail();
        assertEquals(250, tail.length());
        assertTrue(tail.endsWith("END"));
    }

    @Test
    void 缺少配置时按pom自动探测验证命令() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(success("compile ok"));

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.PASSED, result.status());
        assertTrue(result.configured());
        assertEquals("mvn -B -q -DskipTests compile", result.commands().get(0).command());
        assertTrue(result.message().contains("自动探测自 pom.xml"),
                "结论必须标注命令来源，不能冒充用户配置：" + result.message());
        verify(commandService).run(anyString(),
                org.mockito.ArgumentMatchers.eq("mvn -B -q -DskipTests compile"),
                anyString(), any(), any());
    }

    @Test
    void 后端与前端同仓库时按顺序验证两条命令() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Files.writeString(workspace.resolve("package.json"),
                "{\"scripts\":{\"build\":\"vite build\"}}");
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(success("ok"));

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.PASSED, result.status());
        assertEquals(2, result.commands().size());
        assertEquals("mvn -B -q -DskipTests compile", result.commands().get(0).command());
        assertEquals("npm run build", result.commands().get(1).command());
        assertTrue(result.message().contains("自动探测自 pom.xml+package.json"),
                "结论必须列出全部自动来源：" + result.message());
    }

    @Test
    void 自动探测可关闭且关闭后仍报未配置() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        properties.setAutoDetect(false);

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.NOT_CONFIGURED, result.status());
        verify(commandService, never()).run(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void 显式配置优先于自动探测() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        writeConfig("""
                {"commands":[{"name":"custom","command":"npm run typecheck"}]}
                """);
        when(commandService.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(success("ok"));

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals("npm run typecheck", result.commands().get(0).command());
        assertFalse(result.message().contains("自动探测"),
                "用户显式配置时不应标成自动探测");
    }

    @Test
    void 无构建文件时按纯Java源码全量编译通过() throws Exception {
        Path src = workspace.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "package demo; public class App { }");

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.PASSED, result.status());
        assertTrue(result.configured());
        assertEquals(JavaSourceVerifier.COMMAND_NAME, result.commands().get(0).name());
        assertTrue(result.message().contains("自动探测自 java-sources"), result.message());
        verify(commandService, never()).run(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void 纯Java源码编译失败时映射为失败并带指纹() throws Exception {
        Path src = workspace.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Broken.java"), """
                package demo;
                public class Broken {
                    void m() { int value = ; }
                }
                """);

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.FAILED, result.status());
        assertTrue(result.configured());
        assertFalse(result.failureFingerprint().isBlank());
        assertTrue(result.formatForPrompt().contains("请修复"), result.formatForPrompt());
        verify(commandService, never()).run(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void 混合语言源码降级为不可用并要求显式配置() throws Exception {
        Path src = workspace.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "package demo; public class App { }");
        Files.writeString(workspace.resolve("tool.py"), "print('hi')");

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.UNAVAILABLE, result.status());
        assertTrue(result.message().contains("tool.py"), result.message());
        assertTrue(result.message().contains("verify.json"), result.message());
        assertTrue(result.commands().isEmpty());
    }

    @Test
    void 自动探测关闭时即使有Java源码也保持未配置() throws Exception {
        properties.setAutoDetect(false);
        Path src = workspace.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "package demo; public class App { }");

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.NOT_CONFIGURED, result.status());
        assertTrue(result.commands().isEmpty());
    }

    @Test
    void 源码数量超限时不误报通过() throws Exception {
        properties.setSourceScanMaxFiles(1);
        Path src = workspace.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "public class A { }");
        Files.writeString(src.resolve("B.java"), "public class B { }");

        VerifyResult result = service.verify(workspace, 1, new AtomicBoolean(false));

        assertEquals(VerifyResult.Status.UNAVAILABLE, result.status());
        assertTrue(result.message().contains("文件数"), result.message());
        assertTrue(result.commands().isEmpty());
    }

    private void writeConfig(String json) throws Exception {        Path dir = workspace.resolve(".lunargold");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("verify.json"), json);
    }

    private static CommandService.CommandResult success(String output) {
        return new CommandService.CommandResult(0, output, false, false, false, 12);
    }

    private static CommandService.CommandResult failure(int exitCode, String output) {
        return new CommandService.CommandResult(exitCode, output, false, false, false, 12);
    }
}
