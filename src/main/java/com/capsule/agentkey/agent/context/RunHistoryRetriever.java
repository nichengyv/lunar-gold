package com.capsule.agentkey.agent.context;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Run 内本地检索型记忆（R7c / P8-05）。
 *
 * <p>只从当前 Run 的权威消息里找回「被压缩/滑窗省略」的历史片段：不接向量库、
 * 不调用 embedding、不产生额外模型请求。查询由最新真实用户输入与当前计划标题组成，
 * 评分完全确定：
 * <ul>
 *   <li>标识符（{@code [A-Za-z_][A-Za-z0-9_]*}，长度 ≥3）权重 5；</li>
 *   <li>普通英文词（长度 ≥2）权重 2；</li>
 *   <li>中文 bigram 权重 1；</li>
 *   <li>消息角色系数：user 3 / assistant 2 / tool 1。</li>
 * </ul>
 *
 * <p>结果按总分降序、原始序号升序排序，直到 TopK 与总字符预算耗尽。
 * 调用方只把命中内容渲染成一条合成 user 消息，不重新插入原始 assistant/tool
 * 协议节点，避免破坏 tool_call 配对。
 */
public final class RunHistoryRetriever {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9]{1,}");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");
    private static final int MAX_QUERY_TOKENS = 200;

    /**
     * 检索参数。
     *
     * @param topK             最多返回的片段数
     * @param maxFragmentChars 单条片段保留的最大字符数
     * @param maxTotalChars    注入内容的总字符预算
     * @param minScore         最低相关分；低于该分数不返回
     */
    public record Settings(int topK, int maxFragmentChars, int maxTotalChars, int minScore) {

        public Settings {
            if (topK <= 0) {
                topK = 6;
            }
            if (maxFragmentChars <= 0) {
                maxFragmentChars = 1500;
            }
            if (maxTotalChars <= 0) {
                maxTotalChars = 6000;
            }
            if (minScore <= 0) {
                minScore = 6;
            }
        }

        public static Settings defaults() {
            return new Settings(6, 1500, 6000, 6);
        }
    }

    /** 一条待检索的历史消息及其原始序号 */
    public record Source(int index, ObjectNode message) {
    }

    /** 一条命中片段 */
    public record Hit(int index, String role, String content, int score) {
    }

    private final Settings settings;

    public RunHistoryRetriever(Settings settings) {
        this.settings = settings == null ? Settings.defaults() : settings;
    }

    /**
     * 检索候选消息。
     *
     * @param sources 被 condense 省略的消息（保持原始顺序）
     * @param query   查询文本；为空时直接返回空列表
     */
    public List<Hit> retrieve(List<Source> sources, String query) {
        if (sources == null || sources.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, Integer> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        List<Hit> scored = new ArrayList<>();
        for (Source source : sources) {
            if (source == null || source.message() == null) {
                continue;
            }
            String role = source.message().path("role").asText("");
            String content = source.message().path("content").asText("").trim();
            if (content.isEmpty()) {
                continue;
            }
            int score = score(content, role, tokens);
            if (score < settings.minScore()) {
                continue;
            }
            String fragment = content.length() <= settings.maxFragmentChars()
                    ? content : content.substring(0, settings.maxFragmentChars()) + "…";
            scored.add(new Hit(source.index(), role, fragment, score));
        }

        scored.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparingInt(Hit::index));

        List<Hit> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int totalChars = 0;
        for (Hit hit : scored) {
            if (result.size() >= settings.topK()) {
                break;
            }
            String key = normalize(hit.content());
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            if (totalChars + hit.content().length() > settings.maxTotalChars()) {
                break;
            }
            totalChars += hit.content().length();
            result.add(hit);
        }
        return result;
    }

    /** 查询分词：标识符/英文词/中文 bigram，同一 token 取最高权重 */
    static Map<String, Integer> tokenize(String query) {
        Map<String, Integer> tokens = new LinkedHashMap<>();
        Matcher identifier = IDENTIFIER.matcher(query);
        while (identifier.find() && tokens.size() < MAX_QUERY_TOKENS) {
            tokens.merge(identifier.group().toLowerCase(Locale.ROOT), 5, Math::max);
        }
        Matcher word = ENGLISH_WORD.matcher(query);
        while (word.find() && tokens.size() < MAX_QUERY_TOKENS) {
            tokens.merge(word.group().toLowerCase(Locale.ROOT), 2, Math::max);
        }
        Matcher cjk = CJK_RUN.matcher(query);
        while (cjk.find() && tokens.size() < MAX_QUERY_TOKENS) {
            String run = cjk.group();
            for (int i = 0; i + 2 <= run.length(); i++) {
                tokens.merge(run.substring(i, i + 2), 1, Math::max);
            }
        }
        return tokens;
    }

    private static int score(String content, String role, Map<String, Integer> tokens) {
        String haystack = content.toLowerCase(Locale.ROOT);
        int matchedWeight = 0;
        for (Map.Entry<String, Integer> token : tokens.entrySet()) {
            if (haystack.contains(token.getKey())) {
                matchedWeight += token.getValue();
            }
        }
        if (matchedWeight == 0) {
            return 0;
        }
        return roleWeight(role) * matchedWeight;
    }

    private static int roleWeight(String role) {
        return switch (role) {
            case "user" -> 3;
            case "assistant" -> 2;
            case "tool" -> 1;
            default -> 1;
        };
    }

    private static String normalize(String content) {
        return content == null ? "" : content.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
