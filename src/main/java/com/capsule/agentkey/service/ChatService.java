package com.capsule.agentkey.service;

import com.capsule.agentkey.dto.ChatSessionStats;
import com.capsule.agentkey.dto.MessageSearchHit;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.ChatMessageRepository;
import com.capsule.agentkey.repository.ChatSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class ChatService {

    @Autowired
    private ChatSessionRepository sessionRepo;//注入依赖

    @Autowired
    private ChatMessageRepository messageRepo;

    @Autowired
    private ObjectMapper mapper;

    // 运行中改权限也立即生效：ReAct 循环每次工具调用前从这里读最新档位
    @Autowired
    private SessionPermissionTracker permissionTracker;

    @Transactional
    public ChatSession createSession(Long userId, Long projectId, String title, AgentPolicy policy) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setProjectId(projectId);
        session.setTitle(title);
        applyPolicyFields(session, policy);
        return sessionRepo.save(session);
    }

    /**
     * 把本次请求携带的运行参数写回会话，用户切回旧会话时仍是当时选择的配置。
     * 与库里一致就不写，避免每轮对话都刷新 updateTime。
     */
    @Transactional
    public void applyAgentPolicy(Long sessionId, Long userId, AgentPolicy policy) {
        if (policy == null) {
            return;
        }
        ChatSession session = getOwnedSession(sessionId, userId);
        String model = policy.model();
        String reasoning = policy.reasoning().name();
        String permission = policy.permission().name();
        if (java.util.Objects.equals(session.getModel(), model)
                && java.util.Objects.equals(session.getReasoning(), reasoning)
                && java.util.Objects.equals(session.getPermission(), permission)) {
            return;
        }
        session.setModel(model);
        session.setReasoning(reasoning);
        session.setPermission(permission);
        sessionRepo.save(session);
    }

    private void applyPolicyFields(ChatSession session, AgentPolicy policy) {
        if (policy == null) {
            return;
        }
        session.setModel(policy.model());
        session.setReasoning(policy.reasoning().name());
        session.setPermission(policy.permission().name());
    }

    public List<ChatSession> listSessions(Long userId, Long projectId) {
        //调用Repository层，按用户 + 项目拿取会话
        return sessionRepo.findByUserIdAndProjectIdOrderByUpdateTimeDesc(userId, projectId);
    }

    /** 校验会话归属：会话不存在或不属于当前用户都统一提示 */
    public ChatSession getOwnedSession(Long sessionId, Long userId) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问");
        }
        return session;
    }

    public List<ChatMessage> getHistory(Long sessionId, Long userId) {
        getOwnedSession(sessionId, userId);
        return messageRepo.findBySessionIdOrderByCreateTimeAsc(sessionId);
    }

    /** 侧栏正文搜索最多返回几个会话 */
    private static final int MAX_SEARCH_HITS = 10;
    /** 关键字长度上限，防止超长串拖慢 LIKE 查询 */
    private static final int MAX_SEARCH_KEYWORD_CHARS = 50;
    /** 命中片段左右各保留多少字符 */
    private static final int SEARCH_SNIPPET_CHARS = 40;

    /**
     * 消息全文搜索：在当前用户的所有会话里按正文包含关键字匹配。
     * 同一会话只保留最新一条命中消息（按时间倒序遍历时第一次出现的即是），
     * 命中片段截取关键字附近的一段，供侧栏直接预览。
     */
    public List<MessageSearchHit> searchMessages(Long userId, String keyword) {
        if (userId == null || keyword == null) {
            return List.of();
        }
        String kw = keyword.trim();
        if (kw.isEmpty()) {
            return List.of();
        }
        if (kw.length() > MAX_SEARCH_KEYWORD_CHARS) {
            kw = kw.substring(0, MAX_SEARCH_KEYWORD_CHARS);
        }
        List<ChatMessage> matches =
                messageRepo.findTop30ByUserIdAndContentContainingIgnoreCaseOrderByCreateTimeDesc(userId, kw);
        if (matches.isEmpty()) {
            return List.of();
        }
        // 只回了消息 id，会话标题与项目归属要回表查
        Set<Long> sessionIds = new HashSet<>();
        for (ChatMessage m : matches) {
            sessionIds.add(m.getSessionId());
        }
        Map<Long, ChatSession> sessions = new HashMap<>();
        for (ChatSession s : sessionRepo.findAllById(sessionIds)) {
            sessions.put(s.getId(), s);
        }

        List<MessageSearchHit> hits = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ChatMessage m : matches) {
            if (hits.size() >= MAX_SEARCH_HITS) {
                break;
            }
            // 同一会话里多条命中只留最新一条
            if (!seen.add(m.getSessionId())) {
                continue;
            }
            ChatSession session = sessions.get(m.getSessionId());
            if (session == null) {
                continue;
            }
            hits.add(new MessageSearchHit(
                    m.getId(),
                    session.getId(),
                    session.getProjectId(),
                    session.getTitle(),
                    m.getRole() == null ? null : m.getRole().name(),
                    buildSnippet(m.getContent(), kw),
                    m.getCreateTime()));
        }
        return hits;
    }

    /** 截取关键字附近的片段；换行压成空格，侧栏按单行展示 */
    private String buildSnippet(String content, String keyword) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        int idx = flat.toLowerCase().indexOf(keyword.toLowerCase());
        if (idx < 0) {
            return flat.length() <= SEARCH_SNIPPET_CHARS * 2
                    ? flat
                    : flat.substring(0, SEARCH_SNIPPET_CHARS * 2) + "…";
        }
        int start = Math.max(0, idx - SEARCH_SNIPPET_CHARS);
        int end = Math.min(flat.length(), idx + keyword.length() + SEARCH_SNIPPET_CHARS);
        return (start > 0 ? "…" : "") + flat.substring(start, end) + (end < flat.length() ? "…" : "");
    }

    /** 点名会话最多取几条消息（保持问答成对，取偶数更整齐） */
    private static final int MAX_REFERENCE_MESSAGES = 8;
    /** 点名会话注入的总字符上限，避免挤占当前会话的上下文预算 */
    private static final int MAX_REFERENCE_CHARS = 2000;
    /** 单条消息截断长度，防止一条超长回答独占预算 */
    private static final int MAX_MESSAGE_CHARS = 400;

    /**
     * 解析消息里点名的历史会话（前端插入的 `@会话:《标题》`），把该会话最近几轮问答拼成背景文本。
     * 只在当前用户、当前项目内匹配（避免跨项目串味），并排除当前正在对话的会话本身；
     * 找不到或内容为空返回空串，不影响普通提问。
     */
    public String resolveReferencedSessionContext(Long userId, Long projectId,
                                                  Long currentSessionId, String prompt) {
        if (userId == null || projectId == null || prompt == null || !prompt.contains("@会话")) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("@会话[：:]《([^》]+)》").matcher(prompt);
        List<String> titles = new java.util.ArrayList<>();
        while (m.find()) {
            String title = m.group(1).trim();
            if (!title.isEmpty() && !titles.contains(title)) titles.add(title);
        }
        if (titles.isEmpty()) return "";

        List<ChatSession> candidates = sessionRepo.findByUserIdAndProjectIdOrderByUpdateTimeDesc(userId, projectId);
        StringBuilder sb = new StringBuilder();
        for (String title : titles) {
            if (sb.length() >= MAX_REFERENCE_CHARS) break;
            ChatSession target = candidates.stream()
                    .filter(s -> currentSessionId == null || !currentSessionId.equals(s.getId()))
                    .filter(s -> s.getTitle() != null && s.getTitle().equalsIgnoreCase(title))
                    .findFirst()
                    .orElseGet(() -> candidates.stream()
                            .filter(s -> currentSessionId == null || !currentSessionId.equals(s.getId()))
                            .filter(s -> s.getTitle() != null
                                    && s.getTitle().toLowerCase().contains(title.toLowerCase()))
                            .findFirst()
                            .orElse(null));
            if (target == null) {
                log.info("点名的历史会话未找到: {}", title);
                continue;
            }
            List<ChatMessage> recent = messageRepo.findBySessionIdOrderByCreateTimeAsc(target.getId());
            List<ChatMessage> usable = new java.util.ArrayList<>();
            for (ChatMessage msg : recent) {
                if (msg.getContent() == null || msg.getContent().isBlank()) continue;
                if (msg.getRole() != ChatMessage.MessageRole.USER
                        && msg.getRole() != ChatMessage.MessageRole.ASSISTANT) continue;
                usable.add(msg);
            }
            int from = Math.max(0, usable.size() - MAX_REFERENCE_MESSAGES);
            sb.append("\n【引用会话：").append(target.getTitle()).append("】\n");
            for (int i = from; i < usable.size(); i++) {
                if (sb.length() >= MAX_REFERENCE_CHARS) break;
                ChatMessage msg = usable.get(i);
                String speaker = msg.getRole() == ChatMessage.MessageRole.USER ? "用户" : "助手";
                String content = msg.getContent().trim();
                if (content.length() > MAX_MESSAGE_CHARS) {
                    content = content.substring(0, MAX_MESSAGE_CHARS) + "…（已截断）";
                }
                sb.append(speaker).append("：").append(content).append('\n');
            }
            log.info("点名引用历史会话: {}（{} 条消息）", target.getTitle(), usable.size() - from);
        }
        return sb.toString().trim();
    }

    /** 重命名会话（校验归属，标题长度与创建时保持一致） */
    @Transactional
    public void renameSession(Long sessionId, Long userId, String title) {
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException("会话名称不能为空");
        }
        if (trimmed.length() > 100) {
            throw new BusinessException("会话名称不能超过 100 个字符");
        }
        ChatSession session = getOwnedSession(sessionId, userId);
        session.setTitle(trimmed);
        sessionRepo.save(session);
    }

    /** 会话头切工作权限时立即落库：否则切走再切回会恢复成会话里存的旧值 */
    @Transactional
    public void updateSessionPermission(Long sessionId, Long userId, String permission) {
        String normalized = permission == null ? "" : permission.trim().toUpperCase();
        boolean valid = java.util.Arrays.stream(AgentPolicy.Permission.values())
                .anyMatch(p -> p.name().equals(normalized));
        if (!valid) {
            throw new BusinessException("未知的工作权限：" + permission);
        }
        ChatSession session = getOwnedSession(sessionId, userId);
        session.setPermission(normalized);
        sessionRepo.save(session);
        // 正在作答的轮次也能看到新档位（不必等下一轮）
        permissionTracker.put(sessionId, AgentPolicy.Permission.from(normalized));
    }

    @Transactional
    public ChatMessage saveMessage(Long userId, Long sessionId, ChatMessage.MessageRole role, String content, String toolName) {
        return saveMessage(userId, sessionId, role, content, toolName, null);
    }

    /** 带执行轨迹的保存：只有助手消息会带 trace（思考/工具/来源/改动 JSON） */
    @Transactional
    public ChatMessage saveMessage(Long userId, Long sessionId, ChatMessage.MessageRole role, String content,
                                   String toolName, String trace) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(userId);
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setToolName(toolName);
        msg.setTrace(trace);
        //B4：任一消息落库都刷新会话 updateTime，
        //否则会话列表"按最近活跃排序"（findByUserIdOrderByUpdateTimeDesc）名不副实
        sessionRepo.findById(sessionId)
                .ifPresent(s -> s.setUpdateTime(LocalDateTime.now()));
        return messageRepo.save(msg);
    }

    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问");
        }
        messageRepo.deleteBySessionId(sessionId);
        sessionRepo.delete(session);
    }

    /**
     * 会话追踪面板的聚合统计。
     * 数据源：ChatMessage.trace（TEXT 列存的 JSON，每次 assistant 消息执行完工具循环后落库）。
     * 不新建表——trace 里已有 tools/changes/durationMs，按需 parse + 聚合。
     *
     * 第五十四批：前端侧边栏"会话追踪"面板后端支撑。
     */
    public ChatSessionStats aggregateStats(Long sessionId, Long userId) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("无权访问");
        }

        List<ChatMessage> messages = messageRepo.findBySessionIdOrderByCreateTimeAsc(sessionId);
        ChatSessionStats stats = new ChatSessionStats();

        // ==== 1. 工具调用聚合 ====
        // Map<工具名, 调用记录> 统计次数/成功/失败/耗时
        Map<String, ToolAccumulator> toolAccMap = new LinkedHashMap<>();
        List<ChatSessionStats.ToolTimelineEntry> timeline = new ArrayList<>();
        Map<String, FileAccumulator> fileAccMap = new LinkedHashMap<>();
        Set<String> toolNames = new HashSet<>();
        Set<String> filePaths = new HashSet<>();
        long totalToolCalls = 0;
        long totalToolSuccess = 0;
        long totalDurationMs = 0;
        long sessionStartMs = 0;
        // 第五十六批：耗时口径拆分——会话级 breakdown 累加
        long setupMsSum = 0;
        long toolsMsSum = 0;
        long answerMsSum = 0;
        int roundsWithSegments = 0;

        for (ChatMessage msg : messages) {
            String traceJson = msg.getTrace();
            if (traceJson == null || traceJson.isBlank()) continue;
            JsonNode root;
            try {
                root = mapper.readTree(traceJson);
            } catch (Exception e) {
                log.debug("trace JSON 解析跳过（非工具消息）: {}", e.getMessage());
                continue;
            }
            // 跳过非 assistant 消息（user 消息没有 trace）
            if (msg.getRole() != ChatMessage.MessageRole.ASSISTANT) continue;

            long offsetMs = msg.getCreateTime() == null ? 0 : msg.getCreateTime()
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (sessionStartMs == 0) sessionStartMs = offsetMs;

            // durationMs 累加
            if (root.has("durationMs")) {
                totalDurationMs += root.get("durationMs").asLong(0);
            }

            // 第五十六批：segments 累加（setup/tools/answer 三段耗时）
            JsonNode segments = root.get("segments");
            if (segments != null && segments.isArray() && !segments.isEmpty()) {
                for (JsonNode seg : segments) {
                    String phase = seg.path("phase").asText("");
                    long ms = seg.path("ms").asLong(0);
                    switch (phase) {
                        case "setup" -> setupMsSum += ms;
                        case "tools" -> toolsMsSum += ms;
                        case "answer" -> answerMsSum += ms;
                    }
                }
                roundsWithSegments++;
            }

            // tools 数组
            JsonNode tools = root.get("tools");
            if (tools != null && tools.isArray()) {
                for (JsonNode tool : tools) {
                    String name = tool.path("name").asText("");
                    if (name.isEmpty()) continue;
                    String status = tool.path("status").asText("unknown");
                    boolean success = "success".equals(status);
                    long costMs = tool.path("costMs").asLong(0);
                    String source = name.startsWith("mcp_") ? "mcp" : "builtin";

                    toolNames.add(name);
                    totalToolCalls++;
                    if (success) totalToolSuccess++;

                    // 聚合到 toolAccMap
                    ToolAccumulator acc = toolAccMap.computeIfAbsent(name, k -> new ToolAccumulator(name, source));
                    acc.calls++;
                    if (success) acc.success++;
                    else acc.failure++;
                    acc.costs.add(costMs);

                    // 时间线（带 offset 但相对值，前端渲染时用）
                    ChatSessionStats.ToolTimelineEntry entry = new ChatSessionStats.ToolTimelineEntry();
                    entry.setOffsetMs(offsetMs);
                    entry.setName(name);
                    entry.setSource(source);
                    entry.setStatus(status);
                    entry.setCostMs(costMs);
                    // 参数截断到 80 字符，trace 里 args 已经是字符串
                    String args = tool.path("args").asText("{}");
                    entry.setArgsSummary(args.length() > 80 ? args.substring(0, 80) + "…" : args);
                    timeline.add(entry);
                }
            }

            // changes 数组
            JsonNode changes = root.get("changes");
            if (changes != null && changes.isArray()) {
                for (JsonNode change : changes) {
                    String path = change.path("path").asText("");
                    if (path.isEmpty()) continue;
                    filePaths.add(path);
                    String action = change.path("action").asText("modify");
                    String source = change.path("source").asText("unknown");
                    int adds = change.path("additions").asInt(0);
                    int dels = change.path("deletions").asInt(0);

                    FileAccumulator facc = fileAccMap.computeIfAbsent(path, k -> new FileAccumulator(path));
                    facc.lastAction = action;
                    facc.source = source;
                    facc.additions += adds;
                    facc.deletions += dels;
                }
            }
        }

        // ==== 2. 构建 Overview ====
        ChatSessionStats.Overview ov = new ChatSessionStats.Overview();
        long finalStartMs = sessionStartMs;
        ov.setTotalRounds((int) messages.stream().filter(m -> m.getRole() == ChatMessage.MessageRole.ASSISTANT).count());
        ov.setTotalToolCalls((int) totalToolCalls);
        ov.setToolSuccessRate(totalToolCalls == 0 ? 1.0 : (double) totalToolSuccess / totalToolCalls);
        ov.setUniqueTools(toolNames.size());
        ov.setUniqueFilesChanged(filePaths.size());
        ov.setTotalDurationMs(totalDurationMs);
        ov.setReasoningPolicy(session.getReasoning() == null ? "STANDARD" : session.getReasoning());
        ov.setPermission(session.getPermission() == null ? "MANUAL" : session.getPermission());
        ov.setStartedAt(session.getCreateTime() == null ? null : session.getCreateTime().toString());
        ov.setLastActiveAt(session.getUpdateTime() == null ? null : session.getUpdateTime().toString());
        stats.setOverview(ov);

        // 第五十六批：耗时 breakdown（只在有 segments 时才设置，老会话兼容）
        if (roundsWithSegments > 0) {
            ChatSessionStats.TimeBreakdown bd = new ChatSessionStats.TimeBreakdown();
            bd.setSetupMs(setupMsSum);
            bd.setToolsMs(toolsMsSum);
            bd.setAnswerMs(answerMsSum);
            bd.setRoundsWithSegments(roundsWithSegments);
            stats.setTimeBreakdown(bd);
        }

        // ==== 3. 构建 ToolStat（按调用次数降序）====
        List<ChatSessionStats.ToolStat> toolStats = toolAccMap.values().stream()
                .map(acc -> {
                    ChatSessionStats.ToolStat ts = new ChatSessionStats.ToolStat();
                    ts.setName(acc.name);
                    ts.setSource(acc.source);
                    ts.setCalls(acc.calls);
                    ts.setSuccess(acc.success);
                    ts.setFailure(acc.failure);
                    ts.setSuccessRate(acc.calls == 0 ? 1.0 : (double) acc.success / acc.calls);
                    ts.setAvgCostMs(acc.costs.stream().mapToLong(Long::longValue).average().orElse(0));
                    ts.setP95CostMs(p95(acc.costs));
                    ts.setMaxCostMs(acc.costs.stream().mapToLong(Long::longValue).max().orElse(0));
                    return ts;
                })
                .sorted((a, b) -> Integer.compare(b.getCalls(), a.getCalls()))
                .toList();
        stats.setTools(toolStats);

        // ==== 4. 构建 FileChangeStat（按新增+删除行数降序）====
        List<ChatSessionStats.FileChangeStat> fileStats = fileAccMap.values().stream()
                .map(facc -> {
                    ChatSessionStats.FileChangeStat fs = new ChatSessionStats.FileChangeStat();
                    fs.setPath(facc.path);
                    fs.setAction(facc.lastAction);
                    fs.setSource(facc.source);
                    fs.setAdditions(facc.additions);
                    fs.setDeletions(facc.deletions);
                    return fs;
                })
                .sorted((a, b) -> Integer.compare(
                        b.getAdditions() + b.getDeletions(),
                        a.getAdditions() + a.getDeletions()))
                .toList();
        stats.setFiles(fileStats);

        // ==== 5. 时间线 ====
        stats.setTimeline(timeline);

        return stats;
    }

    /** P95：排序后取 ceil(n * 0.95) - 1 位 */
    private long p95(List<Long> costs) {
        if (costs.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(costs);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(sorted.size() * 0.95) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    /** 工具调用聚合用的临时 accumulator */
    private static class ToolAccumulator {
        final String name;
        final String source; // builtin / mcp
        int calls;
        int success;
        int failure;
        List<Long> costs = new ArrayList<>();
        ToolAccumulator(String name, String source) { this.name = name; this.source = source; }
    }

    /** 文件变更聚合用的临时 accumulator */
    private static class FileAccumulator {
        final String path;
        String lastAction;
        String source;
        int additions;
        int deletions;
        FileAccumulator(String path) { this.path = path; }
    }
}