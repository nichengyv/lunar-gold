package com.capsule.agentkey.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5-05：javac 诊断的离线行为。
 */
class JavacDiagnosticsProviderTest {

    @TempDir
    Path root;

    private final JavacDiagnosticsProvider provider = new JavacDiagnosticsProvider();

    @Test
    void validJavaHasNoErrors() throws Exception {
        Path file = write("src/main/java/demo/Ok.java",
                "package demo;\npublic class Ok { public static void main(String[] args) {} }\n");

        DiagnosticsProvider.ProviderResult result = provider.diagnose(root, List.of(file), 15_000);

        assertTrue(result.available());
        assertFalse(result.diagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR));
    }

    @Test
    void brokenJavaReturnsErrorWithLocation() throws Exception {
        Path file = write("src/main/java/demo/Bad.java",
                "package demo;\npublic class Bad {\n  void x() { int a = ; }\n}\n");

        DiagnosticsProvider.ProviderResult result = provider.diagnose(root, List.of(file), 15_000);

        assertTrue(result.available());
        Diagnostic error = result.diagnostics().stream()
                .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
                .findFirst()
                .orElseThrow();
        assertEquals("src/main/java/demo/Bad.java", error.file());
        assertTrue(error.line() >= 2);
        assertEquals("javac", error.source());
    }

    @Test
    void nonJavaFileIsSkipped() throws Exception {
        Path file = write("notes.txt", "not java");

        DiagnosticsProvider.ProviderResult result = provider.diagnose(root, List.of(file), 1_000);

        assertTrue(result.available());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void missingFileIsSkipped() {
        DiagnosticsProvider.ProviderResult result =
                provider.diagnose(root, List.of(root.resolve("src/main/java/Missing.java")), 1_000);

        assertTrue(result.available());
        assertTrue(result.diagnostics().isEmpty());
    }

    private Path write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
