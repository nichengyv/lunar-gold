package com.capsule.agentkey.diagnostics;

import com.capsule.agentkey.config.AgentDiagnosticsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * P5-05：LSP provider 的降级与消息解析；不依赖真实语言服务器。
 */
class LspDiagnosticsProviderTest {

    @TempDir
    Path root;

    @Test
    void blankCommandIsUnavailable() throws Exception {
        Path file = write("src/A.ts", "const a: number = 1;\n");
        LspDiagnosticsProvider provider = new LspDiagnosticsProvider(new AgentDiagnosticsProperties());

        DiagnosticsProvider.ProviderResult result = provider.diagnose(root, List.of(file), 1_000);

        assertFalse(result.available());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void missingCommandBinaryIsUnavailable() throws Exception {
        Path file = write("src/A.ts", "const a: number = 1;\n");
        AgentDiagnosticsProperties properties = new AgentDiagnosticsProperties();
        properties.getLsp().setCommand("definitely-not-a-real-language-server-xyz");
        LspDiagnosticsProvider provider = new LspDiagnosticsProvider(properties);

        DiagnosticsProvider.ProviderResult result = provider.diagnose(root, List.of(file), 1_500);

        assertFalse(result.available());
    }

    @Test
    void parsesPublishDiagnosticsPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path file = root.resolve("src/A.ts");
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", file.toUri().toString());
        ArrayNode items = params.putArray("diagnostics");
        ObjectNode error = items.addObject();
        ObjectNode start = error.putObject("range").putObject("start");
        start.put("line", 4);
        start.put("character", 2);
        error.put("severity", 1);
        error.put("message", "cannot find name 'a'");
        ObjectNode warning = items.addObject();
        warning.putObject("range").putObject("start").put("line", 0).put("character", 0);
        warning.put("severity", 2);
        warning.put("message", "unused variable");

        LspDiagnosticsProvider provider = new LspDiagnosticsProvider(new AgentDiagnosticsProperties());
        List<Diagnostic> diagnostics = provider.parseDiagnostics(params, root, "lsp");

        assertEquals(2, diagnostics.size());
        assertEquals(Diagnostic.Severity.ERROR, diagnostics.get(0).severity());
        assertEquals("src/A.ts", diagnostics.get(0).file());
        assertEquals(5, diagnostics.get(0).line());
        assertEquals(3, diagnostics.get(0).column());
        assertEquals(Diagnostic.Severity.WARNING, diagnostics.get(1).severity());
    }

    private Path write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
