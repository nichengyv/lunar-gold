package com.capsule.agentkey.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 长会话上下文压缩器（R5 / P7-09）。
 *
 * <p>三层依次执行，任何一层失败都只降级、不抛异常：
 * <ol>
 *   <li><b>语义裁剪</b>：同一个文件的多次读取只保留最近一次，旧窗口折叠成一行占位；</li>
 *   <li><b>LLM 摘要</b>：字符占用达到阈值时，把 system 与最近消息之间的历史摘要成一条 user 消息；</li>
 *   <li><b>滑窗兜底</b>：仍超预算时从最早的消息 FIFO 淘汰，保留首条 system，
 *       淘汰 assistant(tool_calls) 时连带丢弃整组 tool 响应，不留下孤儿 tool 消息。</li>
 * </ol>
 *
 * <p>只处理传给模型的副本；{@code agent_run_event}、数据库聊天历史与前端事件均不受影响。
 * 摘要按 run 缓存（{@link #cachedSummary}），同一段历史不会被重复摘要。
 */
@Slf4j
public final class ContextCondenser {

    /** 可折叠的“读取型”工具：写/编辑/命令的结果不折叠，避免误丢关键改动证据 */
    private static final Set<String> FOLDABLE_TOOLS = Set.of("read_file", "list_dir", "search_files");
    /** 小于该长度的读取结果不值得折叠（占位文字本身也有成本） */
    private static final int MIN_FOLD_CHARS = 200;
    /** 摘要输入上限：避免摘要请求本身把上下文再次撑爆 */
    private static final int MAX_TRANSCRIPT_CHARS = 40_000;
    /** 单条消息进入摘要时的最大保留长度 */
    private static final int MAX_MESSAGE_CHARS = 4_000;
    /** 字符数到 token 的粗略折算，仅用于日志与阈值展示 */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * 压缩参数。
     *
     * @param windowChars        滑窗硬上限（字符）
     * @param summarizeAtChars   触发摘要的字符阈值，建议取 windowChars 的 70%
     * @param keepRecentMessages 末尾保留的消息条数（工具调用组会向前扩展）
     */
    public record Settings(int windowChars, int summarizeAtChars, int keepRecentMessages) {

        public Settings {
            if (windowChars <= 0) {
                throw new IllegalArgumentException("windowChars 必须为正数");
            }
            if (summarizeAtChars <= 0 || summarizeAtChars > windowChars) {
                // 摘要阈值高于硬上限时永远不会触发，直接钳到上限
                summarizeAtChars = windowChars;
            }
            if (keepRecentMessages <= 0) {
                keepRecentMessages = 6;
            }
        }

        public static Settings defaults() {
            return new Settings(120_000, 84_000, 6);
        }
    }

    /** 摘要器；实现方可以调用模型，也可以返回固定文本用于测试 */
    @FunctionalInterface
    public interface Summarizer {
        String summarize(String transcript);
    }

    private record ToolCallRef(String name, String key) {
    }

    private final Settings settings;
    private final Summarizer summarizer;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 本 run 已生成的摘要，跨轮复用，避免重复压缩 */
    private String cachedSummary;
    /** cachedSummary 覆盖的原始消息下标终点（不含） */
    private int summaryEnd = 1;

    public ContextCondenser(Settings settings, Summarizer summarizer) {
        this.settings = settings == null ? Settings.defaults() : settings;
        this.summarizer = summarizer;
    }

    /**
     * 压缩消息副本。
     *
     * @param messages 权威消息列表（不会被修改）
     * @return 压缩后的新列表；无需压缩时返回浅拷贝
     */
    public List<ObjectNode> condense(List<ObjectNode> messages) {
        List<ObjectNode> working = new ArrayList<>(messages == null ? List.of() : messages);
        if (working.isEmpty()) {
            return working;
        }
        int beforeChars = chars(working);
        int folded = foldSupersededToolResults(working);
        applySummaryIfNeeded(working);
        int evicted = applyWindow(working);
        int afterChars = chars(working);
        if (beforeChars != afterChars) {
            log.info("上下文压缩：{} → {} 字符（约 {} → {} tokens，折叠 {} 条，淘汰 {} 条）",
                    beforeChars, afterChars, beforeChars / CHARS_PER_TOKEN, afterChars / CHARS_PER_TOKEN,
                    folded, evicted);
        }
        return working;
    }

    // ------------------------------------------------------------------
    // 第一层：语义裁剪
    // ------------------------------------------------------------------

    /**
     * 同一 (工具, 路径/关键词) 的多次读取结果只保留最后一次；
     * 旧结果替换为占位文本，tool_call_id 与消息位置保持不变，协议不受影响。
     */
    private int foldSupersededToolResults(List<ObjectNode> working) {
        Map<String, ToolCallRef> refs = collectToolCalls(working);
        if (refs.isEmpty()) {
            return 0;
        }
        Map<String, Integer> lastIndexByKey = new HashMap<>();
        Map<Integer, String> keyByIndex = new HashMap<>();
        for (int i = 0; i < working.size(); i++) {
            ObjectNode message = working.get(i);
            if (!"tool".equals(role(message))) {
                continue;
            }
            ToolCallRef ref = refs.get(message.path("tool_call_id").asText(""));
            if (ref == null || !FOLDABLE_TOOLS.contains(ref.name())) {
                continue;
            }
            String key = ref.name() + "|" + ref.key();
            lastIndexByKey.put(key, i);
            keyByIndex.put(i, key);
        }
        int folded = 0;
        for (int i = 0; i < working.size(); i++) {
            String key = keyByIndex.get(i);
            if (key == null || lastIndexByKey.get(key) == i) {
                continue;
            }
            ObjectNode original = working.get(i);
            String content = original.path("content").asText("");
            if (content.length() < MIN_FOLD_CHARS) {
                continue;
            }
            ObjectNode copy = original.deepCopy();
            copy.put("content", "【历史读取窗口已折叠】早前读取 " + key.replace('|', ' ')
                    + " 的结果（" + content.length() + " 字符）已被后续读取覆盖；"
                    + "如需细节请重新调用对应工具。");
            working.set(i, copy);
            folded++;
        }
        return folded;
    }

    /** 从 assistant 的 tool_calls 建立 id → (工具名, 去重键) 映射 */
    private Map<String, ToolCallRef> collectToolCalls(List<ObjectNode> messages) {
        Map<String, ToolCallRef> refs = new HashMap<>();
        for (ObjectNode message : messages) {
            if (!"assistant".equals(role(message))) {
                continue;
            }
            JsonNode calls = message.get("tool_calls");
            if (calls == null || !calls.isArray()) {
                continue;
            }
            for (JsonNode call : calls) {
                String id = call.path("id").asText("");
                String name = call.path("function").path("name").asText("");
                String arguments = call.path("function").path("arguments").asText("");
                if (!id.isEmpty() && !name.isEmpty()) {
                    refs.put(id, new ToolCallRef(name, dedupeKey(arguments)));
                }
            }
        }
        return refs;
    }

    /** 用 path/pattern 等代表性参数做去重键；解析失败退化为参数原文 */
    private String dedupeKey(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            JsonNode args = mapper.readTree(arguments);
            for (String field : List.of("path", "file_path", "pattern", "query", "dir")) {
                String value = args.path(field).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // 非法 JSON 不是压缩该处理的问题，直接用原文做键
        }
        return arguments;
    }

    // ------------------------------------------------------------------
    // 第二层：LLM 摘要
    // ------------------------------------------------------------------

    private void applySummaryIfNeeded(List<ObjectNode> working) {
        if (summarizer == null || working.size() < 3) {
            return;
        }
        int protectedStart = protectedStart(working);
        if (protectedStart <= 1) {
            return;
        }

        if (cachedSummary != null && summaryEnd <= protectedStart) {
            int newMiddleChars = chars(working.subList(summaryEnd, protectedStart));
            boolean needResummarize = cachedSummary.length() + newMiddleChars > settings.summarizeAtChars();
            if (!needResummarize) {
                // 复用已有摘要：只替换它覆盖的那段历史，新增消息原样保留
                replaceRange(working, 1, summaryEnd,
                        summaryMessage(cachedSummary, summaryEnd - 1));
                return;
            }
        } else if (chars(working) <= settings.summarizeAtChars()) {
            return;
        }

        // 二次摘要只取上次覆盖点之后的新增历史，避免与已有摘要重复计入
        int transcriptStart = cachedSummary == null ? 1 : Math.max(1, summaryEnd);
        String transcript = transcript(working, transcriptStart, protectedStart);
        String input = cachedSummary == null ? transcript : cachedSummary + "\n" + transcript;
        try {
            String summary = summarizer.summarize(input);
            if (summary == null || summary.isBlank()) {
                return;
            }
            cachedSummary = summary;
            summaryEnd = protectedStart;
            replaceRange(working, 1, protectedStart, summaryMessage(summary, protectedStart - 1));
            log.info("上下文摘要已生成：覆盖 {} 条历史消息", protectedStart - 1);
        } catch (Exception e) {
            // 摘要失败继续走滑窗兜底，不影响本轮调用
            log.warn("上下文摘要失败，降级为滑窗裁剪：{}", e.getMessage());
        }
    }

    /** 末尾保留区起点；遇到 tool 消息时向前扩展，避免把 tool_calls 组切开 */
    private int protectedStart(List<ObjectNode> messages) {
        int start = Math.max(1, messages.size() - settings.keepRecentMessages());
        while (start > 1 && "tool".equals(role(messages.get(start)))) {
            start--;
        }
        return start;
    }

    private ObjectNode summaryMessage(String summary, int coveredMessages) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", "【较早上下文摘要】以下内容由前 " + Math.max(0, coveredMessages)
                + " 条历史消息压缩而来，保留关键决策、约束与未完成事项；"
                + "如需细节可重新读取文件或向用户确认：\n" + summary);
        return message;
    }

    private String transcript(List<ObjectNode> messages, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            ObjectNode message = messages.get(i);
            String content = message.path("content").asText("").trim();
            if (content.isEmpty() && message.has("tool_calls")) {
                content = "调用工具：" + toolCallNames(message);
            }
            if (content.isEmpty()) {
                continue;
            }
            sb.append(role(message)).append(": ").append(abbreviate(content, MAX_MESSAGE_CHARS)).append('\n');
        }
        return abbreviate(sb.toString(), MAX_TRANSCRIPT_CHARS);
    }

    private String toolCallNames(ObjectNode message) {
        StringBuilder names = new StringBuilder();
        JsonNode calls = message.get("tool_calls");
        if (calls != null && calls.isArray()) {
            for (JsonNode call : calls) {
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                names.append(call.path("function").path("name").asText(""));
            }
        }
        return names.toString();
    }

    // ------------------------------------------------------------------
    // 第三层：token 滑窗兜底
    // ------------------------------------------------------------------

    private int applyWindow(List<ObjectNode> working) {
        if (chars(working) <= settings.windowChars()) {
            return 0;
        }
        int sizeBefore = working.size();
        int cut = working.size();
        int kept = 0;
        while (cut > 1) {
            int len = chars(working.get(cut - 1));
            if (kept + len > settings.windowChars()) {
                break;
            }
            kept += len;
            cut--;
        }
        // 首条保留消息是 tool 时，说明切开了 tool_calls 组：整组一起淘汰
        while (cut > 1 && "tool".equals(role(working.get(cut)))) {
            cut--;
        }
        if (cut > 1) {
            working.subList(1, cut).clear();
        }
        int removed = sizeBefore - working.size();
        removed += removeOrphanToolMessages(working);
        if (removed > 0) {
            log.warn("上下文滑动窗口淘汰 {} 条最早消息（保留首条 system 与最近消息）", removed);
        }
        return removed;
    }

    /** 防御性清理：assistant 已被淘汰但 tool 响应仍在时，provider 会因“孤儿 tool”拒绝整段请求 */
    private int removeOrphanToolMessages(List<ObjectNode> working) {
        Set<String> knownIds = new HashSet<>();
        for (ObjectNode message : working) {
            if (!"assistant".equals(role(message))) {
                continue;
            }
            JsonNode calls = message.get("tool_calls");
            if (calls == null || !calls.isArray()) {
                continue;
            }
            for (JsonNode call : calls) {
                knownIds.add(call.path("id").asText(""));
            }
        }
        int removed = 0;
        for (int i = working.size() - 1; i >= 1; i--) {
            ObjectNode message = working.get(i);
            if ("tool".equals(role(message))
                    && !knownIds.contains(message.path("tool_call_id").asText(""))) {
                working.remove(i);
                removed++;
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private void replaceRange(List<ObjectNode> working, int from, int to, ObjectNode replacement) {
        if (from >= to || from < 0 || to > working.size()) {
            return;
        }
        working.subList(from, to).clear();
        working.add(from, replacement);
    }

    private static int chars(List<ObjectNode> messages) {
        int total = 0;
        for (ObjectNode message : messages) {
            total += chars(message);
        }
        return total;
    }

    private static int chars(ObjectNode message) {
        int total = message.path("content").asText("").length();
        JsonNode calls = message.get("tool_calls");
        if (calls != null) {
            total += calls.toString().length();
        }
        return total;
    }

    private static String role(ObjectNode message) {
        return message.path("role").asText("");
    }

    private static String abbreviate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        int half = max / 2;
        return text.substring(0, half) + "\n…（中间省略）…\n" + text.substring(text.length() - half);
    }

    /** 测试与日志用：当前缓存的摘要是否已生成 */
    public boolean hasCachedSummary() {
        return cachedSummary != null;
    }

    /** 仅用于诊断：当前缓存摘要是 null 时返回空串 */
    public String cachedSummary() {
        return cachedSummary == null ? "" : cachedSummary;
    }

    /** 仅用于诊断：已覆盖的原始消息下标终点 */
    public int summaryEnd() {
        return summaryEnd;
    }
}
