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

    private void writeConfig(String json) throws Exception {
        Path dir = workspace.resolve(".lunargold");
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
