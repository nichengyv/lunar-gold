package com.capsule.agentkey.service.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P5-04：符号索引、增量缓存与统计。 */
class CodeIndexServiceTest {

    @TempDir
    Path workspace;

    private final CodeIndexService service = new CodeIndexService(new LightweightCodeIndexer());

    @Test
    void indexesSymbolsAndFindsThemByName() throws IOException {
        write("src/main/java/com/demo/OrderService.java", """
                package com.demo;

                public class OrderService {
                    public String createOrder(String id) {
                        return id;
                    }
                }
                """);

        CodeIndexService.WorkspaceIndex index = service.snapshot(workspace.toString(),
                CodeIndexService.ScanOptions.defaults());

        assertEquals(1, index.files().size());
        List<String> names = index.files().get(0).symbols().stream().map(CodeSymbol::name).toList();
        assertTrue(names.contains("com.demo"), names.toString());
        assertTrue(names.contains("OrderService"), names.toString());
        assertTrue(names.contains("createOrder"), names.toString());
        assertEquals(names.size(), index.stats().symbolCount());

        List<CodeIndexService.SymbolMatch> matches =
                service.findSymbols(workspace.toString(), "orderservice", 10);
        assertEquals(1, matches.size());
        assertEquals("src/main/java/com/demo/OrderService.java", matches.get(0).path());
        assertEquals("OrderService", matches.get(0).symbol().name());
    }

    @Test
    void rescansOnlyChangedFiles() throws IOException {
        write("A.java", "public class A {\n}\n");
        write("B.java", "public class B {\n}\n");

        CodeIndexService.WorkspaceIndex first = service.snapshot(workspace.toString(),
                CodeIndexService.ScanOptions.defaults());
        assertEquals(2, first.stats().scannedFiles());
        assertEquals(0, first.stats().reusedFiles());

        CodeIndexService.WorkspaceIndex second = service.snapshot(workspace.toString(),
                CodeIndexService.ScanOptions.defaults());
        assertEquals(0, second.stats().scannedFiles());
        assertEquals(2, second.stats().reusedFiles());

        write("A.java", "public class A {\n    public void changed() {\n    }\n}\n");
        Files.setLastModifiedTime(workspace.resolve("A.java"),
                FileTime.from(Instant.now().plusSeconds(5)));

        CodeIndexService.WorkspaceIndex third = service.snapshot(workspace.toString(),
                CodeIndexService.ScanOptions.defaults());
        assertEquals(1, third.stats().scannedFiles());
        assertEquals(1, third.stats().reusedFiles());
        List<String> names = third.files().stream()
                .flatMap(file -> file.symbols().stream())
                .map(CodeSymbol::name)
                .toList();
        assertTrue(names.contains("changed"), names.toString());
    }

    @Test
    void purgesDeletedFilesFromCache() throws IOException {
        write("A.java", "public class A {\n}\n");
        write("B.java", "public class B {\n}\n");
        service.snapshot(workspace.toString(), CodeIndexService.ScanOptions.defaults());

        Files.delete(workspace.resolve("B.java"));
        CodeIndexService.WorkspaceIndex index = service.snapshot(workspace.toString(),
                CodeIndexService.ScanOptions.defaults());

        assertEquals(1, index.files().size());
        assertEquals(1, index.stats().removedFiles());
        assertEquals(0, index.stats().scannedFiles());
        assertEquals(1, index.stats().reusedFiles());
    }

    @Test
    void skipsIgnoredDirectories() throws IOException {
        write("src/App.java", "public class App {\n}\n");
        write("node_modules/pkg/index.js", "export const hidden = 1;\n");

        CodeIndexService.WorkspaceIndex index = service.snapshot(workspace.toString(),
                CodeIndexService.ScanOptions.defaults());

        assertEquals(1, index.files().size());
        assertEquals("src/App.java", index.files().get(0).path());
        assertTrue(service.findSymbols(workspace.toString(), "hidden", 10).isEmpty());
    }

    @Test
    void skipsSymbolExtractionForOversizedFiles() throws IOException {
        write("Big.java", "public class Big {\n    public void method() {\n    }\n}\n");

        CodeIndexService.WorkspaceIndex index = service.snapshot(workspace.toString(),
                new CodeIndexService.ScanOptions(8, 500, 10));

        assertEquals(1, index.files().size());
        assertTrue(index.files().get(0).symbols().isEmpty());
        assertEquals(0, index.stats().symbolCount());
    }

    @Test
    void blankQueryReturnsNothing() throws IOException {
        write("A.java", "public class A {\n}\n");

        assertTrue(service.findSymbols(workspace.toString(), "  ", 10).isEmpty());
        assertTrue(service.findSymbols(workspace.toString(), "A", 0).isEmpty());
        assertFalse(service.findSymbols(workspace.toString(), "A", 10).isEmpty());
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = workspace.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
