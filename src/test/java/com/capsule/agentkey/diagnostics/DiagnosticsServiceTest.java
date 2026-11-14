package com.capsule.agentkey.diagnostics;

import com.capsule.agentkey.config.AgentDiagnosticsProperties;
import com.capsule.agentkey.tool.FileChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5-05：诊断编排的过滤、限量与降级。
 */
class DiagnosticsServiceTest {

    @TempDir
    Path root;

    @Test
    void truncatesByItemCountAndCharacters() throws Exception {
        Path file = write("src/main/java/Demo.java", "public class Demo {}\n");
        AgentDiagnosticsProperties properties = new AgentDiagnosticsProperties();
        properties.setMaxItems(20);
        properties.setMaxChars(600);

        List<Diagnostic> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add(new Diagnostic(Diagnostic.Severity.ERROR, "src/main/java/Demo.java",
                    i + 1, 1, "第 " + i + " 条错误 " + "很长的诊断信息".repeat(8), "fake"));
        }
        DiagnosticsService service = new DiagnosticsService(properties,
                List.of(new FakeProvider("javac", true, false, many)));

        DiagnosticsResult result = service.collect(root, changes("src/main/java/Demo.java"));

        assertTrue(result.available());
        assertTrue(result.diagnostics().size() <= 20);
        assertTrue(result.formatForPrompt().length() <= 600);
        assertTrue(result.truncated());
        assertEquals(21, result.totalCount());
    }

    @Test
    void unavailableProviderDegradesWithoutThrowing() throws Exception {
        write("src/main/java/Demo.java", "public class Demo {}\n");
        DiagnosticsService service = new DiagnosticsService(new AgentDiagnosticsProperties(),
                List.of(new FakeProvider("javac", false, true, List.of())));

        DiagnosticsResult result = service.collect(root, changes("src/main/java/Demo.java"));

        assertFalse(result.available());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(result.analyzedFiles() > 0);
        assertTrue(result.formatForPrompt().contains("诊断不可用"));
    }

    @Test
    void disabledSwitchSkipsCollection() throws Exception {
        write("src/main/java/Demo.java", "public class Demo {}\n");
        AgentDiagnosticsProperties properties = new AgentDiagnosticsProperties();
        properties.setEnabled(false);
        DiagnosticsService service = new DiagnosticsService(properties,
                List.of(new FakeProvider("javac", true, false, List.of())));

        DiagnosticsResult result = service.collect(root, changes("src/main/java/Demo.java"));

        assertFalse(result.available());
        assertEquals(0, result.analyzedFiles());
    }

    @Test
    void unsupportedFilesProduceEmptyResult() throws Exception {
        write("readme.md", "# hi\n");
        DiagnosticsService service = new DiagnosticsService(new AgentDiagnosticsProperties(),
                List.of(new FakeProvider("javac", true, false, List.of())));

        DiagnosticsResult result = service.collect(root, changes("readme.md"));

        assertFalse(result.available());
        assertEquals(0, result.analyzedFiles());
    }

    @Test
    void fingerprintIsStableAcrossOrder() {
        Diagnostic a = new Diagnostic(Diagnostic.Severity.ERROR, "A.java", 1, 1, "bad   code", "javac");
        Diagnostic b = new Diagnostic(Diagnostic.Severity.WARNING, "B.java", 2, 1, "warn", "javac");

        assertEquals(DiagnosticsService.fingerprint(List.of(a, b)),
                DiagnosticsService.fingerprint(List.of(b, a)));
    }

    private List<FileChange> changes(String path) {
        return List.of(new FileChange(path, "modify", "write_file", 1, 0, null));
    }

    private Path write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private record FakeProvider(String name, boolean available, boolean fail,
                                List<Diagnostic> diagnostics) implements DiagnosticsProvider {

        @Override
        public boolean supports(Path file) {
            return file.getFileName().toString().endsWith(".java");
        }

        @Override
        public ProviderResult diagnose(Path workspaceRoot, List<Path> files, long timeoutMs) {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return available ? ProviderResult.of(diagnostics) : ProviderResult.unavailable();
        }
    }
}
