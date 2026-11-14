package com.capsule.agentkey.service;

import com.capsule.agentkey.config.AgentRepoMapProperties;
import com.capsule.agentkey.service.index.CodeIndexService;
import com.capsule.agentkey.service.index.LightweightCodeIndexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P5-04 前置：repo map 的扫描范围、符号摘要与预算截断。 */
class RepoMapServiceTest {

    @TempDir
    Path workspace;

    @Test
    void buildsDirectoryTreeWithJavaSymbolsAndSkipsIgnoredDirs() throws IOException {
        write("src/main/java/com/demo/OrderService.java", """
                package com.demo;

                public class OrderService {
                    public String createOrder(String id) {
                        return id;
                    }

                    private void cancel(long id) {
                    }
                }
                """);
        write("node_modules/pkg/index.js", "export const ignored = 1;\n");
        write("README.md", "# demo\n");

        String map = service(new AgentRepoMapProperties()).build(workspace.toString());

        assertTrue(map.contains("src/"), map);
        assertTrue(map.contains("OrderService.java"), map);
        assertTrue(map.contains("package com.demo"), map);
        assertTrue(map.contains("createOrder()"), map);
        assertTrue(map.contains("cancel()"), map);
        assertTrue(map.contains("README.md"), map);
        assertFalse(map.contains("node_modules"), map);
        assertFalse(map.contains("ignored"), map);
    }

    @Test
    void extractsVueComponentNameAsFallback() throws IOException {
        write("frontend/src/components/ChatMessage.vue", """
                <script setup lang="ts">
                const title = 'x';
                </script>
                <template><div>{{ title }}</div></template>
                """);

        String map = service(new AgentRepoMapProperties()).build(workspace.toString());

        assertTrue(map.contains("ChatMessage.vue"), map);
        assertTrue(map.contains("component ChatMessage"), map);
    }

    @Test
    void stopsAtCharacterBudgetAndExplainsTruncation() throws IOException {
        for (int i = 0; i < 20; i++) {
            write("src/file-" + i + ".java", "public class File" + i + " {\n}\n");
        }
        AgentRepoMapProperties properties = new AgentRepoMapProperties();
        properties.setMaxChars(160);

        String map = service(properties).build(workspace.toString());

        assertFalse(map.isBlank());
        assertTrue(map.length() <= 160, map);
        assertTrue(map.contains("repo map 已截断"), map);
    }

    @Test
    void respectsMaxDepth() throws IOException {
        write("src/main/java/com/demo/Deep.java", "public class Deep {\n}\n");
        AgentRepoMapProperties properties = new AgentRepoMapProperties();
        properties.setMaxDepth(2);

        String map = service(properties).build(workspace.toString());

        assertFalse(map.contains("Deep.java"), map);
    }

    @Test
    void capsSymbolsPerFile() throws IOException {
        StringBuilder source = new StringBuilder("public class Many {\n");
        for (int i = 0; i < 10; i++) {
            source.append("    public void method").append(i).append("() {\n    }\n");
        }
        source.append("}\n");
        write("Many.java", source.toString());
        AgentRepoMapProperties properties = new AgentRepoMapProperties();
        properties.setMaxSymbolsPerFile(3);

        String map = service(properties).build(workspace.toString());

        assertTrue(map.contains("method0()"), map);
        assertFalse(map.contains("method9()"), map);
    }

    @Test
    void returnsEmptyWhenDisabledOrWorkspaceMissing() {
        AgentRepoMapProperties disabled = new AgentRepoMapProperties();
        disabled.setEnabled(false);
        assertEquals("", service(disabled).build(workspace.toString()));

        assertEquals("", service(new AgentRepoMapProperties()).build(""));
        assertEquals("", service(new AgentRepoMapProperties()).build(workspace.resolve("missing").toString()));
    }

    @Test
    void outputIsDeterministic() throws IOException {
        write("b/B.java", "public class B {\n}\n");
        write("a/A.java", "public class A {\n}\n");
        RepoMapService service = service(new AgentRepoMapProperties());

        assertEquals(service.build(workspace.toString()), service.build(workspace.toString()));
    }

    private RepoMapService service(AgentRepoMapProperties properties) {
        return new RepoMapService(properties,
                new CodeIndexService(new LightweightCodeIndexer()));
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = workspace.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
