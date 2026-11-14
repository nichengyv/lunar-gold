package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.PendingFileChange;
import com.capsule.agentkey.entity.Project;
import com.capsule.agentkey.repository.AgentRunEventRepository;
import com.capsule.agentkey.repository.AgentRunRepository;
import com.capsule.agentkey.repository.ChatMessageRepository;
import com.capsule.agentkey.repository.PendingFileChangeRepository;
import com.capsule.agentkey.repository.ProjectRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P5-14：任务级 ChangeSet 聚合与交付。
 *
 * <p>不新增 Task/ChangeSet 表：以 Session 为任务边界，聚合现有
 * {@link PendingFileChange}、{@link AgentRun}、{@link AgentRunEvent} 与审批事件。
 * 验证/诊断状态只来自事件与 completion，不受用户「接受」动作影响。
 */
@Service
@Slf4j
public class ChangeSetService {

    private static final int MAX_RUNS = 50;
    private static final int MAX_CHANGES = 200;
    private static final int MAX_PROMPT_OUTPUT = 800;
    private static final Set<String> CONTINUABLE_STATUSES =
            Set.of("FAILED", "INCOMPLETE", "UNVERIFIED", "STOPPED");

    private final PendingFileChangeRepository changeRepo;
    private final AgentRunRepository runRepo;
    private final AgentRunEventRepository eventRepo;
    private final ChatMessageRepository messageRepo;
    private final ProjectRepository projectRepo;
    private final WorkspaceFileService workspaceFileService;
    private final ObjectMapper mapper;

    public ChangeSetService(PendingFileChangeRepository changeRepo,
                            AgentRunRepository runRepo,
                            AgentRunEventRepository eventRepo,
                            ChatMessageRepository messageRepo,
                            ProjectRepository projectRepo,
                            WorkspaceFileService workspaceFileService,
                            ObjectMapper mapper) {
        this.changeRepo = changeRepo;
        this.runRepo = runRepo;
        this.eventRepo = eventRepo;
        this.messageRepo = messageRepo;
        this.projectRepo = projectRepo;
        this.workspaceFileService = workspaceFileService;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------------
    // 交付视图
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ChangeSetView view(Long userId, Long sessionId, Long runIdFilter) {
        List<AgentRun> runs = new ArrayList<>(
                runRepo.findByUserIdAndSessionIdOrderByCreateTimeDescIdDesc(userId, sessionId));
        if (runIdFilter != null) {
            runs.removeIf(run -> !runIdFilter.equals(run.getId()));
        }
        if (runs.size() > MAX_RUNS) {
            runs = new ArrayList<>(runs.subList(0, MAX_RUNS));
        }

        List<PendingFileChange> changes = new ArrayList<>(
                changeRepo.findBySessionIdOrderByCreateTimeDescIdDesc(sessionId));
        if (changes.size() > MAX_CHANGES) {
            changes = new ArrayList<>(changes.subList(0, MAX_CHANGES));
        }
        Long projectId = runs.isEmpty()
                ? (changes.isEmpty() ? null : changes.get(0).getProjectId())
                : runs.get(0).getProjectId();
        if (projectId == null) {
            projectId = changes.isEmpty() ? null : changes.get(0).getProjectId();
        }

        // 每个 Run 的摘要；变更先按 turnId 归属，第二轮补充计数
        Map<String, RunAggregate> aggregates = new LinkedHashMap<>();
        Set<String> filteredTurns = new LinkedHashSet<>();
        for (AgentRun run : runs) {
            RunAggregate aggregate = parseRun(run);
            aggregates.put(run.getId().toString(), aggregate);
            if (aggregate.turnId != null && !aggregate.turnId.isBlank()) {
                filteredTurns.add(aggregate.turnId);
            }
        }

        List<ChangeView> changeViews = new ArrayList<>();
        for (PendingFileChange change : changes) {
            RunAggregate owner = findRunByTurn(aggregates, change.getTurnId());
            if (runIdFilter != null && (owner == null || !runIdFilter.equals(owner.run.getId()))) {
                continue;
            }
            Long runId = owner == null ? null : owner.run.getId();
            changeViews.add(new ChangeView(
                    change.getId(), runId, change.getTurnId(), change.getFilePath(), change.getAction(),
                    change.getSource(), change.getAdditions(), change.getDeletions(), change.getDiffText(),
                    change.getStatus(), change.getCreateTime()));
            if (owner != null) {
                owner.files++;
                owner.additions += nullSafe(change.getAdditions());
                owner.deletions += nullSafe(change.getDeletions());
            }
        }

        List<RunView> runViews = new ArrayList<>();
        for (RunAggregate aggregate : aggregates.values()) {
            runViews.add(aggregate.toView());
        }

        RunAggregate candidate = selectCandidate(aggregates.values());
        VerificationView verification;
        if (candidate == null) {
            verification = missingVerification();
        } else if (candidate.verification != null) {
            verification = candidate.verification;
        } else {
            verification = fromCompletion(candidate.completion, candidate.run.getId(), candidate.turnId);
        }
        String status = resolveStatus(runViews, candidate, verification, changeViews);
        boolean accepted = !changeViews.isEmpty()
                && changeViews.stream().allMatch(c -> "ACCEPTED".equals(c.status()));

        Totals totals = totals(changeViews);
        ApprovalSummary approvals = mergeApprovals(aggregates.values());
        String continuePrompt = buildContinuePrompt(status, candidate, verification, changeViews, sessionId);

        return new ChangeSetView(sessionId, projectId, runIdFilter, status, statusLabel(status), accepted,
                verification, approvals, totals, runViews, changeViews, continuePrompt);
    }

    private RunAggregate parseRun(AgentRun run) {
        RunAggregate aggregate = new RunAggregate(run);
        JsonNode request = parse(run.getRequestJson());
        if (request != null) {
            aggregate.prompt = request.path("prompt").asText("");
            aggregate.turnId = blankToNull(request.path("turnId").asText(""));
        }
        JsonNode result = parse(run.getResultJson());
        if (result != null) {
            aggregate.completion = parseCompletion(result);
        }

        List<AgentRunEvent> events;
        try {
            events = eventRepo.findByRunIdOrderBySeqAsc(run.getId());
        } catch (Exception e) {
            log.warn("读取 Run 事件失败，降级为空摘要: runId={}, {}", run.getId(), e.getMessage());
            events = List.of();
        }
        Map<String, ApprovalItem> approvalItems = new LinkedHashMap<>();
        for (AgentRunEvent event : events) {
            JsonNode payload = parse(event.getPayload());
            if (payload == null) {
                continue;
            }
            switch (event.getEventType()) {
                case COMPLETION -> aggregate.completion = parseCompletion(payload);
                case VERIFY -> aggregate.verification = parseVerification(payload, run.getId());
                case DIAGNOSTICS -> aggregate.diagnostics = parseDiagnostics(payload);
                case APPROVAL_REQUESTED -> {
                    String approvalId = payload.path("approvalId").asText("");
                    if (!approvalId.isBlank()) {
                        approvalItems.put(approvalId, new ApprovalItem(approvalId,
                                blankToNull(payload.path("tool").asText("")), null,
                                run.getId(), aggregate.turnId));
                    }
                }
                case APPROVAL_RESOLVED -> {
                    String approvalId = payload.path("approvalId").asText("");
                    ApprovalItem existing = approvalId.isBlank() ? null : approvalItems.get(approvalId);
                    if (existing != null) {
                        approvalItems.put(approvalId, new ApprovalItem(existing.approvalId(),
                                existing.tool(), blankToNull(payload.path("decision").asText("")),
                                existing.runId(), existing.turnId()));
                    } else if (!approvalId.isBlank()) {
                        approvalItems.put(approvalId, new ApprovalItem(approvalId, null,
                                blankToNull(payload.path("decision").asText("")),
                                run.getId(), aggregate.turnId));
                    }
                }
                default -> {
                    // 其他事件与交付摘要无关
                }
            }
        }
        aggregate.approvals = new ArrayList<>(approvalItems.values());
        if (aggregate.turnId == null && aggregate.verification != null) {
            aggregate.turnId = aggregate.verification.turnId();
        }
        if (aggregate.turnId == null && aggregate.diagnostics != null) {
            aggregate.turnId = aggregate.diagnostics.turnId();
        }
        return aggregate;
    }

    private CompletionView parseCompletion(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new CompletionView(
                blankToNull(node.path("status").asText("")),
                blankToNull(node.path("reason").asText("")),
                blankToNull(node.path("message").asText("")),
                node.has("filesChanged") ? node.path("filesChanged").asBoolean() : null);
    }

    private VerificationView parseVerification(JsonNode node, Long runId) {
        List<CommandView> commands = new ArrayList<>();
        JsonNode array = node.path("commands");
        if (array.isArray()) {
            for (JsonNode item : array) {
                commands.add(new CommandView(
                        text(item, "name"), text(item, "command"),
                        item.hasNonNull("exitCode") ? item.path("exitCode").asInt() : null,
                        item.has("timedOut") ? item.path("timedOut").asBoolean() : null,
                        item.has("required") ? item.path("required").asBoolean() : null,
                        text(item, "outputTail")));
            }
        }
        String status = text(node, "status");
        if (status == null) {
            return null;
        }
        return new VerificationView(status, verificationLabel(status), text(node, "message"),
                node.hasNonNull("runId") ? node.path("runId").asLong() : runId,
                blankToNull(text(node, "turnId")), text(node, "failureFingerprint"), commands);
    }

    private DiagnosticsView parseDiagnostics(JsonNode node) {
        List<Map<String, Object>> items = new ArrayList<>();
        JsonNode array = node.path("diagnostics");
        if (array.isArray()) {
            for (JsonNode item : array) {
                items.add(mapper.convertValue(item, new TypeReference<Map<String, Object>>() {
                }));
            }
        }
        return new DiagnosticsView(text(node, "provider"),
                node.has("available") ? node.path("available").asBoolean() : null,
                node.hasNonNull("analyzedFiles") ? node.path("analyzedFiles").asInt() : null,
                node.hasNonNull("totalCount") ? node.path("totalCount").asInt() : null,
                blankToNull(text(node, "turnId")), items);
    }

    private RunAggregate findRunByTurn(Map<String, RunAggregate> aggregates, String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return null;
        }
        for (RunAggregate aggregate : aggregates.values()) {
            if (turnId.equals(aggregate.turnId)) {
                return aggregate;
            }
        }
        return null;
    }

    private RunAggregate selectCandidate(Iterable<RunAggregate> aggregates) {
        RunAggregate fallback = null;
        for (RunAggregate aggregate : aggregates) {
            if (fallback == null) {
                fallback = aggregate;
            }
            if (aggregate.completion != null || aggregate.verification != null
                    || aggregate.diagnostics != null || aggregate.files > 0) {
                return aggregate;
            }
        }
        return fallback;
    }

    private String resolveStatus(List<RunView> runs, RunAggregate candidate,
                                 VerificationView verification, List<ChangeView> changes) {
        if (runs.isEmpty()) {
            return "EMPTY";
        }
        for (RunView run : runs) {
            if ("RUNNING".equals(run.state()) || "QUEUED".equals(run.state())) {
                return "RUNNING";
            }
        }
        for (RunView run : runs) {
            if ("WAITING_APPROVAL".equals(run.state())) {
                return "WAITING_APPROVAL";
            }
        }
        if (candidate == null) {
            return changes.isEmpty() ? "EMPTY" : "UNVERIFIED";
        }
        if ("CANCELLED".equals(candidate.run.getState().name())) {
            return "STOPPED";
        }
        if ("FAILED".equals(candidate.run.getState().name())) {
            return "FAILED";
        }
        String completionStatus = candidate.completion == null ? null : candidate.completion.status();
        if ("INCOMPLETE".equals(completionStatus)) {
            return "INCOMPLETE";
        }
        if ("UNVERIFIED".equals(completionStatus)) {
            return "UNVERIFIED";
        }
        boolean hasChanges = !changes.isEmpty();
        boolean allAccepted = hasChanges && changes.stream()
                .allMatch(c -> "ACCEPTED".equals(c.status()));
        if ("FAILED".equals(verification.status())) {
            return "FAILED";
        }
        if (hasChanges) {
            if ("PASSED".equals(verification.status())) {
                return allAccepted ? "ACCEPTED" : "VERIFIED";
            }
            return "UNVERIFIED";
        }
        return "COMPLETED";
    }

    private Totals totals(List<ChangeView> changes) {
        int additions = 0;
        int deletions = 0;
        int pending = 0;
        int accepted = 0;
        int reverted = 0;
        for (ChangeView change : changes) {
            additions += nullSafe(change.additions());
            deletions += nullSafe(change.deletions());
            switch (change.status() == null ? "" : change.status()) {
                case "PENDING" -> pending++;
                case "ACCEPTED" -> accepted++;
                case "REVERTED" -> reverted++;
                default -> {
                }
            }
        }
        return new Totals(changes.size(), additions, deletions, pending, accepted, reverted);
    }

    private ApprovalSummary mergeApprovals(Iterable<RunAggregate> aggregates) {
        List<ApprovalItem> items = new ArrayList<>();
        int requested = 0;
        int resolved = 0;
        for (RunAggregate aggregate : aggregates) {
            for (ApprovalItem item : aggregate.approvals) {
                items.add(item);
                requested++;
                if (item.decision() != null) {
                    resolved++;
                }
            }
        }
        return new ApprovalSummary(requested, resolved, items);
    }

    // ---------------------------------------------------------------------
    // 继续修复
    // ---------------------------------------------------------------------

    private String buildContinuePrompt(String status, RunAggregate candidate,
                                       VerificationView verification,
                                       List<ChangeView> changes, Long sessionId) {
        if (!CONTINUABLE_STATUSES.contains(status)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("继续修复上一个任务，沿用当前任务上下文，不要从头重做。\n");

        boolean evidence = false;
        if (candidate != null && candidate.prompt != null && !candidate.prompt.isBlank()) {
            sb.append("\n【原任务】\n").append(candidate.prompt.trim()).append('\n');
            evidence = true;
        }

        if (verification != null && !"PASSED".equals(verification.status())
                && verification.message() != null && !verification.message().isBlank()) {
            sb.append("\n【验证状态】").append(verification.label());
            sb.append("：").append(verification.message()).append('\n');
            evidence = true;
            int printed = 0;
            if (verification.commands() != null) {
                for (CommandView command : verification.commands()) {
                    if (printed >= 3) {
                        break;
                    }
                    boolean failed = command.timedOut() != null && command.timedOut()
                            || command.exitCode() != null && command.exitCode() != 0;
                    if (!failed) {
                        continue;
                    }
                    sb.append("- ").append(command.name() == null ? command.command() : command.name())
                            .append("（exit=").append(command.exitCode()).append("，timeout=")
                            .append(command.timedOut()).append("）\n");
                    if (command.outputTail() != null && !command.outputTail().isBlank()) {
                        sb.append("  输出尾部：").append(truncate(command.outputTail(), MAX_PROMPT_OUTPUT))
                                .append('\n');
                    }
                    printed++;
                }
            }
        }

        if (candidate != null && candidate.diagnostics != null
                && candidate.diagnostics.diagnostics() != null) {
            int printed = 0;
            for (Map<String, Object> item : candidate.diagnostics.diagnostics()) {
                if (printed >= 10) {
                    break;
                }
                Object severity = item.get("severity");
                if (severity == null || !"ERROR".equalsIgnoreCase(severity.toString())) {
                    continue;
                }
                sb.append("- 诊断：").append(value(item, "file"))
                        .append(':').append(value(item, "line"))
                        .append(' ').append(value(item, "message")).append('\n');
                printed++;
                evidence = true;
            }
        }

        List<String> unfinished = unfinishedPlanSteps(sessionId);
        if (!unfinished.isEmpty()) {
            sb.append("\n【未完成计划】\n");
            for (int i = 0; i < unfinished.size(); i++) {
                sb.append(i + 1).append(". ").append(unfinished.get(i)).append('\n');
            }
            evidence = true;
        }

        if (!evidence) {
            return "";
        }
        sb.append("\n请先确认当前工作区状态，再修改并重新验证；验证未通过不得声称完成。");
        return sb.toString();
    }

    private List<String> unfinishedPlanSteps(Long sessionId) {
        List<ChatMessage> messages;
        try {
            messages = messageRepo.findBySessionIdOrderByCreateTimeAsc(sessionId);
        } catch (Exception e) {
            return List.of();
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.getRole() != ChatMessage.MessageRole.ASSISTANT || message.getTrace() == null) {
                continue;
            }
            JsonNode trace = parse(message.getTrace());
            if (trace == null) {
                continue;
            }
            JsonNode steps = trace.path("plan").path("steps");
            if (!steps.isArray()) {
                continue;
            }
            List<String> result = new ArrayList<>();
            for (JsonNode step : steps) {
                String stepStatus = step.path("status").asText("");
                if (!"pending".equals(stepStatus) && !"in_progress".equals(stepStatus)) {
                    continue;
                }
                String title = step.path("title").asText("");
                if (title.isBlank()) {
                    title = step.path("step").asText("");
                }
                if (title.isBlank()) {
                    title = step.path("content").asText("");
                }
                if (!title.isBlank()) {
                    result.add(title.trim());
                }
            }
            return result;
        }
        return List.of();
    }

    // ---------------------------------------------------------------------
    // 撤回与冲突检测
    // ---------------------------------------------------------------------

    @Transactional
    public RevertResult revertSingle(Long id, Long userId) {
        PendingFileChange change = changeRepo.findById(id).orElse(null);
        if (change == null) {
            return new RevertResult(false, 0, 0, 0,
                    List.of(new Conflict(id, null, "变更不存在")));
        }
        if (userId != null && change.getUserId() != null && !change.getUserId().equals(userId)) {
            return new RevertResult(false, 0, 0, 0,
                    List.of(new Conflict(id, change.getFilePath(), "无权操作该变更")));
        }
        if (!"PENDING".equals(change.getStatus())) {
            return new RevertResult(false, 0, 0,
                    "ACCEPTED".equals(change.getStatus()) ? 1 : 0,
                    List.of(new Conflict(id, change.getFilePath(),
                            "ACCEPTED".equals(change.getStatus()) ? "已接受的变更保留审计，不能撤回" : "该变更已处理")));
        }
        return applyReverts(List.of(change), 0);
    }

    @Transactional
    public RevertResult revertTurn(Long sessionId, String turnId, Long userId) {
        List<PendingFileChange> all = changeRepo.findBySessionIdOrderByCreateTimeDescIdDesc(sessionId);
        long accepted = all.stream()
                .filter(c -> turnId != null && turnId.equals(c.getTurnId()))
                .filter(c -> "ACCEPTED".equals(c.getStatus()))
                .count();
        List<PendingFileChange> pending = all.stream()
                .filter(c -> turnId != null && turnId.equals(c.getTurnId()))
                .filter(c -> "PENDING".equals(c.getStatus()))
                .toList();
        return applyReverts(pending, accepted);
    }

    @Transactional
    public RevertResult revertRun(Long sessionId, Long runId, Long userId) {
        AgentRun run = runRepo.findById(runId).orElse(null);
        if (run == null || !sessionId.equals(run.getSessionId())
                || (run.getUserId() != null && !run.getUserId().equals(userId))) {
            return new RevertResult(false, 0, 0, 0,
                    List.of(new Conflict(runId, null, "运行不存在或不属于当前任务")));
        }
        String turnId = resolveTurnId(run);
        if (turnId == null) {
            return new RevertResult(false, 0, 0, 0,
                    List.of(new Conflict(runId, null, "该运行缺少 turnId，无法按 Run 撤回")));
        }
        return revertTurn(sessionId, turnId, userId);
    }

    @Transactional
    public RevertResult revertSession(Long sessionId) {
        List<PendingFileChange> all = changeRepo.findBySessionIdOrderByCreateTimeDescIdDesc(sessionId);
        long accepted = all.stream().filter(c -> "ACCEPTED".equals(c.getStatus())).count();
        List<PendingFileChange> pending = all.stream()
                .filter(c -> "PENDING".equals(c.getStatus()))
                .toList();
        return applyReverts(pending, accepted);
    }

    @Transactional
    public RevertResult revertProject(Long projectId) {
        List<PendingFileChange> all = new ArrayList<>(changeRepo.findByProjectIdOrderByCreateTimeDesc(projectId));
        all.sort(Comparator
                .comparing(PendingFileChange::getCreateTime,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(PendingFileChange::getId, Comparator.reverseOrder()));
        long accepted = all.stream().filter(c -> "ACCEPTED".equals(c.getStatus())).count();
        List<PendingFileChange> pending = all.stream()
                .filter(c -> "PENDING".equals(c.getStatus()))
                .toList();
        return applyReverts(pending, accepted);
    }

    private RevertResult applyReverts(List<PendingFileChange> pending, long skippedAccepted) {
        int count = 0;
        int failed = 0;
        List<Conflict> conflicts = new ArrayList<>();
        for (PendingFileChange change : pending) {
            RevertOutcome outcome = revertOne(change);
            if (outcome == null) {
                change.setStatus("REVERTED");
                changeRepo.save(change);
                count++;
            } else if (outcome.conflict) {
                conflicts.add(new Conflict(change.getId(), change.getFilePath(), outcome.reason));
            } else {
                failed++;
                conflicts.add(new Conflict(change.getId(), change.getFilePath(), outcome.reason));
            }
        }
        return new RevertResult(failed == 0, count, failed, (int) skippedAccepted, conflicts);
    }

    private RevertOutcome revertOne(PendingFileChange change) {
        try {
            Project project = projectRepo.findById(change.getProjectId()).orElse(null);
            if (project == null || project.getWorkspacePath() == null
                    || project.getWorkspacePath().isBlank()) {
                return new RevertOutcome(false, "项目或工作区路径不存在");
            }
            Path root = Paths.get(project.getWorkspacePath());
            Path file = workspaceFileService.resolveInWorkspace(root, change.getFilePath());
            boolean exists = Files.isRegularFile(file);
            String current = exists ? Files.readString(file, StandardCharsets.UTF_8) : null;

            if (change.getOldContent() == null) {
                if (!exists) {
                    return null;
                }
                if (!sameContent(current, change.getNewContent())) {
                    return new RevertOutcome(true, "文件在 Agent 新建后被外部修改");
                }
                Files.deleteIfExists(file);
                return null;
            }

            boolean deleteAction = "delete".equalsIgnoreCase(change.getAction());
            if (!exists && !deleteAction) {
                return new RevertOutcome(true, "文件在 Agent 修改后被外部删除");
            }
            if (exists && !sameContent(current, change.getNewContent())) {
                return new RevertOutcome(true, deleteAction
                        ? "文件在 Agent 删除后被外部重建"
                        : "文件在 Agent 修改后被外部改动");
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, change.getOldContent(), StandardCharsets.UTF_8);
            return null;
        } catch (Exception e) {
            log.warn("撤回文件失败 id={}, path={}: {}", change.getId(), change.getFilePath(), e.getMessage());
            return new RevertOutcome(false, "撤回失败：" + e.getMessage());
        }
    }

    private boolean sameContent(String current, String expected) {
        if (current == null || expected == null) {
            return false;
        }
        return normalize(current).equals(normalize(expected));
    }

    private String normalize(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String resolveTurnId(AgentRun run) {
        JsonNode request = parse(run.getRequestJson());
        if (request != null) {
            String turnId = blankToNull(request.path("turnId").asText(""));
            if (turnId != null) {
                return turnId;
            }
        }
        try {
            for (AgentRunEvent event : eventRepo.findByRunIdOrderBySeqAsc(run.getId())) {
                JsonNode payload = parse(event.getPayload());
                if (payload != null) {
                    String turnId = blankToNull(payload.path("turnId").asText(""));
                    if (turnId != null) {
                        return turnId;
                    }
                }
            }
        } catch (Exception ignored) {
            // 没有事件时按缺失处理
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // 解析与标签
    // ---------------------------------------------------------------------

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.debug("ChangeSet 解析 JSON 失败，按空处理: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }

    private static String value(Map<String, Object> item, String key) {
        Object value = item.get(key);
        return value == null ? "" : value.toString();
    }

    private static VerificationView missingVerification() {
        return new VerificationView("MISSING", "未验证", null, null, null, null, List.of());
    }

    private static String verificationLabel(String status) {
        if (status == null) {
            return "未验证";
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "PASSED" -> "验证通过";
            case "FAILED" -> "验证失败";
            case "NOT_CONFIGURED" -> "未配置验证";
            case "UNAVAILABLE" -> "验证不可用";
            case "DIAGNOSTICS_PASSED" -> "编译诊断通过";
            default -> "未验证";
        };
    }

    private static VerificationView fromCompletion(CompletionView completion, Long runId, String turnId) {
        if (completion == null || completion.reason() == null) {
            return missingVerification();
        }
        String status = switch (completion.reason()) {
            case "VERIFY_PASSED" -> "PASSED";
            case "VERIFY_FAILED", "DIAGNOSTICS_FAILED" -> "FAILED";
            case "VERIFY_NOT_CONFIGURED" -> "NOT_CONFIGURED";
            case "VERIFY_UNAVAILABLE" -> "UNAVAILABLE";
            case "DIAGNOSTICS_PASSED" -> "DIAGNOSTICS_PASSED";
            default -> "MISSING";
        };
        return new VerificationView(status, verificationLabel(status), completion.message(),
                runId, turnId, null, List.of());
    }

    private static String statusLabel(String status) {
        if (status == null) {
            return "暂无变更";
        }
        return switch (status) {
            case "RUNNING" -> "进行中";
            case "WAITING_APPROVAL" -> "待审批";
            case "STOPPED" -> "已停止";
            case "FAILED" -> "失败";
            case "INCOMPLETE" -> "未完成";
            case "UNVERIFIED" -> "未验证";
            case "VERIFIED" -> "验证通过";
            case "ACCEPTED" -> "用户已接受";
            case "COMPLETED" -> "已完成";
            default -> "暂无变更";
        };
    }

    // ---------------------------------------------------------------------
    // DTO / 内部聚合
    // ---------------------------------------------------------------------

    public record CommandView(String name, String command, Integer exitCode, Boolean timedOut,
                              Boolean required, String outputTail) {
    }

    public record VerificationView(String status, String label, String message, Long runId,
                                   String turnId, String failureFingerprint,
                                   List<CommandView> commands) {
    }

    public record ApprovalItem(String approvalId, String tool, String decision, Long runId, String turnId) {
    }

    public record ApprovalSummary(int requested, int resolved, List<ApprovalItem> items) {
    }

    public record CompletionView(String status, String reason, String message, Boolean filesChanged) {
    }

    public record DiagnosticsView(String provider, Boolean available, Integer analyzedFiles,
                                  Integer totalCount, String turnId, List<Map<String, Object>> diagnostics) {
    }

    public record RunView(Long runId, String turnId, String state, String prompt, LocalDateTime createTime,
                          LocalDateTime endTime, CompletionView completion, VerificationView verification,
                          DiagnosticsView diagnostics, List<ApprovalItem> approvals,
                          int files, int additions, int deletions) {
    }

    public record ChangeView(Long id, Long runId, String turnId, String filePath, String action,
                             String source, Integer additions, Integer deletions, String diffText,
                             String status, LocalDateTime createTime) {
    }

    public record Totals(int files, int additions, int deletions, int pending, int accepted, int reverted) {
    }

    public record ChangeSetView(Long sessionId, Long projectId, Long runFilter, String status,
                                String statusLabel, boolean accepted, VerificationView verification,
                                ApprovalSummary approvals, Totals totals, List<RunView> runs,
                                List<ChangeView> changes, String continuePrompt) {
    }

    public record Conflict(Long id, String filePath, String reason) {
    }

    public record RevertResult(boolean ok, int count, int failed, int skippedAccepted,
                               List<Conflict> conflicts) {
    }

    private record RevertOutcome(boolean conflict, String reason) {
    }

    private static final class RunAggregate {
        private final AgentRun run;
        private String turnId;
        private String prompt;
        private CompletionView completion;
        private VerificationView verification;
        private DiagnosticsView diagnostics;
        private List<ApprovalItem> approvals = List.of();
        private int files;
        private int additions;
        private int deletions;

        private RunAggregate(AgentRun run) {
            this.run = run;
        }

        private RunView toView() {
            VerificationView effective = verification != null
                    ? verification : fromCompletion(completion, run.getId(), turnId);
            return new RunView(run.getId(), turnId, run.getState().name(), prompt,
                    run.getCreateTime(), run.getEndTime(), completion, effective, diagnostics,
                    approvals, files, additions, deletions);
        }
    }
}
