package com.capsule.agentkey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P5-01：提示词资源目录。
 *
 * <p>所有 Agent 提示词放在 classpath:prompts/ 下，由 prompts/index.json 登记版本与 SHA-256；
 * 运行前校验文件完整性和必备段落，「改文案不改代码」。场景使用哪个版本由配置
 * {@code agent.prompt.scenario.<scenario>.version} 决定，找不到就启动失败，避免静默跑旧版。</p>
 */
@Component
@Slf4j
public class PromptCatalog {

    /** 一条提示词登记：id + version 唯一定位一个文件 */
    public record PromptEntry(String id, String version, String scenario, String path,
                              String sha256, boolean isDefault) {
        public String ref() {
            return id + "@" + version;
        }
    }

    /** 各主提示词场景必须包含的段落，缺一段就拒绝启动；工具附注不套用主提示词规则。 */
    private static final Map<String, List<String>> REQUIRED_MARKERS = Map.of(
            "agent", List.of("【核心原则】", "【文件修改规则】", "【任务计划】", "【通用对话要求】"),
            "coding", List.of("【核心原则】", "【代码理解】", "【文件修改规则】", "【任务计划】", "【决策询问】", "【完成与验证】"));

    private static final String DEFAULT_INDEX = "prompts/index.json";
    private static final String VERSION_PROPERTY_PREFIX = "agent.prompt.scenario.";

    private final Environment environment;
    private final String indexPath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** key: id@version */
    private final Map<String, PromptEntry> entries = new LinkedHashMap<>();
    private final Map<String, String> contents = new LinkedHashMap<>();
    /** scenario -> 当前生效版本 */
    private final Map<String, String> activeVersions = new LinkedHashMap<>();

    @Autowired
    public PromptCatalog(Environment environment) {
        this(environment, DEFAULT_INDEX);
    }

    /** 供单测指定测试索引，生产只用默认索引 */
    PromptCatalog(Environment environment, String indexPath) {
        this.environment = environment;
        this.indexPath = indexPath;
        reload();
    }

    /**
     * 重新读取索引与文件。先构建完整的新快照，全部校验通过后再原子替换；
     * 任一文件缺失/哈希不符/版本不存在都抛异常且保留旧内容（启动时异常即启动失败）。
     */
    public synchronized void reload() {
        loadFrom(indexPath);
    }

    /** 供单测验证「重载失败保留旧快照」 */
    synchronized void reloadFrom(String index) {
        loadFrom(index);
    }

    private void loadFrom(String index) {
        Map<String, PromptEntry> nextEntries = new LinkedHashMap<>();
        Map<String, String> nextContents = new LinkedHashMap<>();
        Map<String, String> nextActive = new LinkedHashMap<>();
        Map<String, List<PromptEntry>> byScenario = new LinkedHashMap<>();

        JsonNode root = readIndex(index);
        JsonNode prompts = root.get("prompts");
        if (prompts == null || !prompts.isArray() || prompts.isEmpty()) {
            throw new IllegalStateException("提示词索引为空: " + index);
        }

        for (JsonNode node : prompts) {
            String id = text(node, "id");
            String version = text(node, "version");
            String scenario = text(node, "scenario");
            String path = text(node, "path");
            String expectedSha = text(node, "sha256");
            boolean isDefault = node.path("default").asBoolean(false);

            String content = readPromptFile(path);
            String actualSha = sha256(content);
            if (!actualSha.equalsIgnoreCase(expectedSha)) {
                throw new IllegalStateException("提示词哈希不匹配: " + path
                        + " 期望 " + expectedSha + " 实际 " + actualSha);
            }
            for (String marker : REQUIRED_MARKERS.getOrDefault(scenario, List.of())) {
                if (!content.contains(marker)) {
                    throw new IllegalStateException("提示词缺少必备段落 " + marker + ": " + path);
                }
            }

            PromptEntry entry = new PromptEntry(id, version, scenario, path, expectedSha, isDefault);
            String key = entry.ref();
            if (nextEntries.putIfAbsent(key, entry) != null) {
                throw new IllegalStateException("提示词重复登记: " + key);
            }
            nextContents.put(key, content);
            byScenario.computeIfAbsent(scenario, k -> new ArrayList<>()).add(entry);
        }

        // 按场景敲定生效版本：配置优先，否则用 default=true 的条目
        byScenario.forEach((scenario, list) -> {
            String configured = environment.getProperty(VERSION_PROPERTY_PREFIX + scenario + ".version");
            PromptEntry chosen;
            if (configured != null && !configured.isBlank()) {
                chosen = list.stream()
                        .filter(e -> e.version().equals(configured.trim()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("未知提示词版本: " + scenario
                                + "=" + configured + "（已登记 " + list.stream().map(PromptEntry::version).toList() + "）"));
            } else {
                chosen = list.stream()
                        .filter(PromptEntry::isDefault)
                        .findFirst()
                        .orElseGet(() -> list.get(0));
            }
            nextActive.put(scenario, chosen.version());
        });

        entries.clear();
        entries.putAll(nextEntries);
        contents.clear();
        contents.putAll(nextContents);
        activeVersions.clear();
        activeVersions.putAll(nextActive);
        log.info("提示词已加载: {}", describe());
    }

    /** 取场景当前生效的提示词正文 */
    public String text(String scenario) {
        PromptEntry entry = active(scenario);
        return contents.get(entry.ref());
    }

    /** 取场景当前生效的登记信息（用于写入 run 事件） */
    public PromptEntry active(String scenario) {
        String version = activeVersions.get(scenario);
        if (version == null) {
            throw new IllegalStateException("未知提示词场景: " + scenario);
        }
        return entries.values().stream()
                .filter(e -> e.scenario().equals(scenario) && e.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("提示词未加载: " + scenario + "@" + version));
    }

    /** 当前生效版本摘要：scenario -> id@version */
    public Map<String, String> describe() {
        Map<String, String> result = new LinkedHashMap<>();
        activeVersions.forEach((scenario, version) -> result.put(scenario, active(scenario).ref()));
        return result;
    }

    private JsonNode readIndex(String index) {
        try (InputStream in = new ClassPathResource(index).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词索引失败: " + index, e);
        }
    }

    private String readPromptFile(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词文件失败: " + path, e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalStateException("提示词索引字段缺失: " + field);
        }
        return value.asText();
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算提示词哈希失败", e);
        }
    }
}
