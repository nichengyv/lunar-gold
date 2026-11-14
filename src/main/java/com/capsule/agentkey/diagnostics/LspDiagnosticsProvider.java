package com.capsule.agentkey.diagnostics;

import com.capsule.agentkey.config.AgentDiagnosticsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P5-05：可选 LSP 诊断，通过 JSON-RPC stdio 接入外部 language server。
 *
 * <p>只实现诊断所需的最小流程：initialize → initialized → didOpen →
 * 等待 publishDiagnostics → didClose → shutdown/exit。命令为空、启动失败、
 * 初始化失败或超时都降级为 {@link ProviderResult#unavailable()}，不抛异常。
 */
@Component
@Slf4j
public class LspDiagnosticsProvider implements DiagnosticsProvider {

    private static final String NAME = "lsp";
    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java", ".ts", ".tsx", ".js", ".jsx", ".vue", ".py", ".go", ".rs");

    private final AgentDiagnosticsProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public LspDiagnosticsProvider(AgentDiagnosticsProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    @Override
    public ProviderResult diagnose(Path workspaceRoot, List<Path> files, long timeoutMs) {
        String command = properties.getLsp().getCommand();
        if (command == null || command.isBlank() || workspaceRoot == null || files == null || files.isEmpty()) {
            return ProviderResult.unavailable();
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<Path> targets = files.stream()
                .filter(this::supports)
                .map(p -> p.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .distinct()
                .toList();
        if (targets.isEmpty()) {
            return ProviderResult.of(List.of());
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "lsp-diagnostics");
            t.setDaemon(true);
            return t;
        });
        AtomicReference<Process> processRef = new AtomicReference<>();
        Future<ProviderResult> future = executor.submit(() -> runSession(root, targets, processRef));
        try {
            return future.get(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.warn("LSP 诊断超时（{}ms），降级为不可用", timeoutMs);
            return ProviderResult.unavailable();
        } catch (Exception e) {
            log.warn("LSP 诊断失败，降级为不可用: {}", e.getMessage());
            return ProviderResult.unavailable();
        } finally {
            // 超时取消后线程可能阻塞在 stdio 读取上，必须由外层强制结束子进程
            Process process = processRef.get();
            if (process != null) {
                process.destroyForcibly();
            }
            executor.shutdownNow();
        }
    }

    private ProviderResult runSession(Path root, List<Path> targets,
                                      AtomicReference<Process> processRef) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(properties.getLsp().getCommand().trim());
        String args = properties.getLsp().getArgs();
        if (args != null && !args.isBlank()) {
            command.addAll(Arrays.asList(args.trim().split("\\s+")));
        }

        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(root.toFile()).start();
            processRef.set(process);
            try (OutputStream out = process.getOutputStream();
                 InputStream in = process.getInputStream()) {
                ObjectNode initialize = request(1, "initialize");
                ObjectNode params = initialize.putObject("params");
                params.put("processId", ProcessHandle.current().pid());
                params.put("rootUri", root.toUri().toString());
                params.putObject("capabilities").putObject("textDocument").putObject("publishDiagnostics");
                send(out, initialize);
                if (awaitResponse(in, 1) == null) {
                    throw new IOException("LSP initialize 无响应");
                }

                ObjectNode initialized = notification("initialized");
                initialized.putObject("params");
                send(out, initialized);

                Map<String, List<Diagnostic>> collected = new LinkedHashMap<>();
                Set<String> openedUris = new HashSet<>();
                for (Path file : targets) {
                    if (Files.size(file) > MAX_FILE_BYTES) {
                        continue;
                    }
                    String uri = file.toUri().toString();
                    ObjectNode didOpen = notification("textDocument/didOpen");
                    ObjectNode textDocument = didOpen.putObject("params").putObject("textDocument");
                    textDocument.put("uri", uri);
                    textDocument.put("languageId", languageId(file));
                    textDocument.put("version", 1);
                    textDocument.put("text", Files.readString(file, StandardCharsets.UTF_8));
                    send(out, didOpen);
                    openedUris.add(uri);
                }

                while (collected.size() < openedUris.size()) {
                    JsonNode message = readMessage(in);
                    if (message == null) {
                        break;
                    }
                    if ("textDocument/publishDiagnostics".equals(message.path("method").asText())) {
                        JsonNode diagParams = message.path("params");
                        String uri = diagParams.path("uri").asText();
                        if (openedUris.contains(uri)) {
                            collected.put(uri, parseDiagnostics(diagParams, root, NAME));
                        }
                    }
                }

                for (String uri : openedUris) {
                    ObjectNode didClose = notification("textDocument/didClose");
                    didClose.putObject("params").putObject("textDocument").put("uri", uri);
                    send(out, didClose);
                }
                send(out, request(2, "shutdown"));
                send(out, notification("exit"));

                return ProviderResult.of(collected.values().stream()
                        .flatMap(List::stream)
                        .toList());
            }
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /** 解析 publishDiagnostics.params；包内可见便于单测，不依赖真实语言服务器 */
    List<Diagnostic> parseDiagnostics(JsonNode params, Path root, String source) {
        List<Diagnostic> result = new ArrayList<>();
        JsonNode items = params.path("diagnostics");
        if (!items.isArray()) {
            return result;
        }
        for (JsonNode item : items) {
            JsonNode start = item.path("range").path("start");
            int line = Math.max(1, start.path("line").asInt(0) + 1);
            int column = Math.max(1, start.path("character").asInt(0) + 1);
            int severityCode = item.path("severity").asInt(3);
            Diagnostic.Severity severity = switch (severityCode) {
                case 1 -> Diagnostic.Severity.ERROR;
                case 2 -> Diagnostic.Severity.WARNING;
                default -> Diagnostic.Severity.INFO;
            };
            result.add(new Diagnostic(severity, relativize(root, params.path("uri").asText()),
                    line, column, item.path("message").asText(""), source));
        }
        return result;
    }

    private String languageId(Path file) {
        String configured = properties.getLsp().getLanguageId();
        if (configured != null && !configured.isBlank() && !"java".equalsIgnoreCase(configured)) {
            return configured;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ts")) return "typescript";
        if (name.endsWith(".tsx")) return "typescriptreact";
        if (name.endsWith(".js")) return "javascript";
        if (name.endsWith(".jsx")) return "javascriptreact";
        if (name.endsWith(".vue")) return "vue";
        if (name.endsWith(".py")) return "python";
        if (name.endsWith(".go")) return "go";
        if (name.endsWith(".rs")) return "rust";
        return "java";
    }

    private String relativize(Path root, String rawUri) {
        try {
            URI uri = URI.create(rawUri);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Path path = Paths.get(uri).toAbsolutePath().normalize();
                return root.relativize(path).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // 非 file URI 或无法解析时原样返回
        }
        return rawUri;
    }

    private ObjectNode request(int id, String method) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", id);
        node.put("method", method);
        return node;
    }

    private ObjectNode notification(String method) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        return node;
    }

    private void send(OutputStream out, JsonNode message) throws IOException {
        byte[] body = mapper.writeValueAsBytes(message);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private JsonNode awaitResponse(InputStream in, int id) throws IOException {
        while (true) {
            JsonNode message = readMessage(in);
            if (message == null) {
                return null;
            }
            if (message.path("id").asInt(-1) == id) {
                return message;
            }
        }
    }

    private JsonNode readMessage(InputStream in) throws IOException {
        int contentLength = -1;
        String line;
        while ((line = readAsciiLine(in)) != null) {
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0 && "Content-Length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        if (contentLength < 0) {
            return null;
        }
        byte[] body = in.readNBytes(contentLength);
        if (body.length < contentLength) {
            return null;
        }
        return mapper.readTree(body);
    }

    private String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        if (b == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }
}
