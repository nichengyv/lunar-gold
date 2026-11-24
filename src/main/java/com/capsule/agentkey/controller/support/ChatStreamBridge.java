package com.capsule.agentkey.controller.support;

import com.capsule.agentkey.diagnostics.Diagnostic;
import com.capsule.agentkey.diagnostics.DiagnosticsResult;
import com.capsule.agentkey.dto.ApprovalPreview;
import com.capsule.agentkey.entity.AgentRunEvent;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.exception.TokenBudgetExceededException;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.DocumentService;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.service.SessionMemoryExtractionService;
import com.capsule.agentkey.tool.FileChange;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.capsule.agentkey.verify.VerifyResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一轮对话的流式回调桥接。
 *
 * <p>把 {@link ReActAgentService.StreamCallback} 的各个回调统一翻译成 SSE 事件、
 * Run Event 与助手消息轨迹的持久化，避免这些细节全部堆在 Controller 里。
 * 职责边界：只处理「拿到回调之后」的事；会话准备、运行记录创建、附件与摘要等
 * 前置编排仍由 Controller 负责。事件名、载荷字段与写出顺序与抽取前保持一致。
 */
@Slf4j
public final class ChatStreamBridge implements ReActAgentService.StreamCallback {

    /** P5-10：回答内容的断点检查点间隔；太小会把事件表写爆，太大丢字多 */
    private static final long CONTENT_CHECKPOINT_INTERVAL_MS = 800L;
    /** P5-10：单个检查点累计正文上限，超长回答只保证前 2 万字可恢复 */
    private static final int CONTENT_CHECKPOINT_LIMIT = 20000;

    private final ChatSseChannel channel;
    private final Long runId;
    private final String turnId;
    private final Long chatSessionId;
    private final Long userId;
    private final AgentPolicy policy;
    private final String apiKey;
    private final ChatService chatService;
    private final SessionMemoryExtractionService memoryService;
    private final AgentRunService agentRunService;
    private final ObjectMapper mapper;
    private final long maxTokensPerRun;
    private final long maxSecondsPerRun;

    /** 本轮开始时间，供 usage 与轨迹里的耗时口径使用；由 beginTurn 在业务线程上置位 */
    private long traceStartedAt;
    private final StringBuilder fullResponse = new StringBuilder();
    // 第五十六批：耗时口径拆分——记录各阶段时间戳
    private final AtomicLong firstToolCallAt = new AtomicLong(0);
    private final AtomicLong lastToolResultAt = new AtomicLong(0);
    private final AtomicLong toolCostMsSum = new AtomicLong(0);
    private final AtomicLong firstChunkAt = new AtomicLong(0);
    // P5-10：上一次正文检查点的时间；断线后前端靠它重放已生成内容
    private final AtomicLong lastContentCheckpointAt = new AtomicLong(0);
    // P4-07：本轮 token 累计；用量只落库一次，避免多处收尾重复累加
    private final AtomicLong turnPromptTokens = new AtomicLong(0);
    private final AtomicLong turnCompletionTokens = new AtomicLong(0);
    // P5-10-3：本轮已完成的模型轮次，用于向前端展示轮次预算
    private final AtomicInteger turnRounds = new AtomicInteger(0);
    private final AtomicBoolean usagePersisted = new AtomicBoolean(false);
    private final StringBuilder traceThinking = new StringBuilder();
    private final List<Map<String, Object>> traceTools = new ArrayList<>();
    private final List<Map<String, Object>> traceSources = new ArrayList<>();
    private final List<Map<String, Object>> traceChanges = new ArrayList<>();
    // 第六十八批 A1：本轮任务计划的最新快照，随助手消息 trace 落库
    private final AtomicReference<Map<String, Object>> tracePlan = new AtomicReference<>();
    // P5-13：交付状态独立于 Run 生命周期，随消息轨迹与 run.resultJson 持久化
    private final AtomicReference<Map<String, Object>> traceCompletion = new AtomicReference<>();
    // P5-05：最近一批变更文件诊断，随消息轨迹落库供历史会话还原
    private final AtomicReference<Map<String, Object>> traceDiagnostics = new AtomicReference<>();
    // P5-06：最近一次项目验证结果，随消息轨迹落库供历史会话与断线重放还原
    private final AtomicReference<Map<String, Object>> traceVerify = new AtomicReference<>();
    private final AtomicReference<String> runResultJson = new AtomicReference<>();

    public ChatStreamBridge(ChatSseChannel channel,
                            Long runId,
                            String turnId,
                            Long chatSessionId,
                            Long userId,
                            AgentPolicy policy,
                            String apiKey,
                            ChatService chatService,
                            SessionMemoryExtractionService memoryService,
                            AgentRunService agentRunService,
                            ObjectMapper mapper,
                            long maxTokensPerRun,
                            long maxSecondsPerRun) {
        this.channel = channel;
        this.runId = runId;
        this.turnId = turnId;
        this.chatSessionId = chatSessionId;
        this.userId = userId;
        this.policy = policy;
        this.apiKey = apiKey;
        this.chatService = chatService;
        this.memoryService = memoryService;
        this.agentRunService = agentRunService;
        this.mapper = mapper;
        this.maxTokensPerRun = maxTokensPerRun;
        this.maxSecondsPerRun = maxSecondsPerRun;
    }

    /** 业务线程开始执行本轮时调用，保证耗时口径不含线程池排队时间 */
    public void beginTurn() {
        this.traceStartedAt = System.currentTimeMillis();
    }

    @Override
    public void onSources(List<DocumentService.Source> sources) {
        try {
            //下发资料出处：id、文件名、相关度、片段原文（前端可点击展开）
            List<Map<String, Object>> list = sources.stream()
                    .map(s -> {
                        Map<String, Object> item = new HashMap<String, Object>();
                        item.put("id", s.documentId());
                        item.put("name", s.fileName());
                        item.put("score", Math.round(s.score() * 100) / 100.0);
                        item.put("content", s.content());
                        // P5-07：引用可定位到具体切片
                        if (s.chunkId() != null) item.put("chunkId", s.chunkId());
                        if (s.chunkIndex() != null) item.put("chunkIndex", s.chunkIndex());
                        return item;
                    })
                    .toList();
            traceSources.clear();
            traceSources.addAll(list);
            channel.send("sources", mapper.writeValueAsString(list));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 sources 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onThinking(String thought) {
        try {
            if (traceThinking.length() > 0) traceThinking.append('\n');
            traceThinking.append(thought == null ? "" : thought);
            Map<String, String> data = new HashMap<>();
            data.put("thought", thought);
            channel.send("thinking", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 thinking 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onToolCall(String toolName, String arguments) {
        try {
            // 第五十六批：记录工具阶段起始时间
            if (firstToolCallAt.get() == 0) firstToolCallAt.set(System.currentTimeMillis());
            Map<String, Object> item = new HashMap<>();
            item.put("name", toolName);
            item.put("args", arguments == null ? "{}" : arguments);
            item.put("status", "running");
            traceTools.add(item);
            Map<String, Object> data = new HashMap<>();
            data.put("tool", toolName);
            data.put("args", arguments);
            data.put("status", "calling");
            // P4-04：先落事件表，再尝试推送；P6-06 断流后推送是 no-op，但事件必须完整
            Map<String, Object> persisted = new LinkedHashMap<>();
            persisted.put("tool", toolName);
            persisted.put("args", truncateForTrace(arguments, 1000));
            appendRunEvent(AgentRunEvent.EventType.TOOL_CALL, persisted);
            channel.send("tool_call", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 tool_call 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onToolResult(String toolName, ToolResult result) {
        try {
            // 第五十六批：记录工具阶段结束时间 + 累加 costMs
            lastToolResultAt.set(System.currentTimeMillis());
            if (result.getCostMs() > 0) toolCostMsSum.addAndGet(result.getCostMs());
            // 与前端一致：回填最近一条同名且仍在运行的调用
            for (int i = traceTools.size() - 1; i >= 0; i--) {
                Map<String, Object> item = traceTools.get(i);
                if (equalsIgnoreCase(item.get("name"), toolName) && "running".equals(item.get("status"))) {
                    item.put("status", result.isSuccess() ? "success" : "error");
                    item.put("result", truncateForTrace(result.getContent(), 2000));
                    item.put("error", truncateForTrace(result.getError(), 1000));
                    item.put("costMs", result.getCostMs());
                    break;
                }
            }
            Map<String, Object> data = new HashMap<>();
            data.put("tool", toolName);
            data.put("success", result.isSuccess());
            data.put("content", result.getContent());
            data.put("error", result.getError());
            data.put("costMs", result.getCostMs());
            data.put("status", "done");
            // P4-04：先落事件表，再尝试推送；工具结果只落摘要，避免整份文件把事件表撑大
            Map<String, Object> persisted = new LinkedHashMap<>();
            persisted.put("tool", toolName);
            persisted.put("success", result.isSuccess());
            persisted.put("content", truncateForTrace(result.getContent(), 1000));
            persisted.put("error", truncateForTrace(result.getError(), 500));
            appendRunEvent(AgentRunEvent.EventType.TOOL_RESULT, persisted);
            channel.send("tool_result", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 tool_result 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onFileChange(FileChange change) {
        try {
            //下发「这次改了什么文件」：路径、动作、来源工具、增删行数与逐行 diff
            Map<String, Object> data = new HashMap<>();
            data.put("path", change.path());
            data.put("action", change.action());
            data.put("source", change.source());
            data.put("additions", change.additions());
            data.put("deletions", change.deletions());
            data.put("diff", change.diff());
            // 同一文件多次改动只留最新一条，与前端展示规则一致
            traceChanges.removeIf(c -> change.path().equals(c.get("path")));
            traceChanges.add(data);
            channel.send("file_change", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 file_change 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onPlan(List<com.capsule.agentkey.agent.run.PlanStep> steps) {
        try {
            // 下发任务计划：前端凭 plan 事件实时刷新计划面板
            List<Map<String, Object>> list = new ArrayList<>();
            for (com.capsule.agentkey.agent.run.PlanStep step : steps) {
                Map<String, Object> item = new HashMap<>();
                item.put("title", step.title());
                item.put("status", step.status());
                list.add(item);
            }
            Map<String, Object> data = new HashMap<>();
            data.put("steps", list);
            data.put("updatedAt", System.currentTimeMillis());
            tracePlan.set(data);
            channel.send("plan", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 plan 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onApprovalRequired(String approvalId, String tool, ApprovalPreview preview,
                                   String risk, long expiresAt) {
        agentRunService.pauseForApproval(runId, approvalId, tool);
        try {
            // 审批卡片只收到可读摘要（路径/命令/改了多少行），不下发裸参数 JSON
            Map<String, Object> data = new HashMap<>();
            data.put("approvalId", approvalId);
            data.put("tool", tool);
            data.put("preview", preview);
            data.put("risk", risk);
            data.put("expiresAt", expiresAt);
            channel.send("approval_required", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 approval_required 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onApprovalResolved(String approvalId, String decision, String source, String batchId) {
        agentRunService.resumeAfterApproval(runId, decision, source, batchId);
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("approvalId", approvalId);
            data.put("decision", decision);
            // P5-10-2：前端据此展示来源；批量审批带同一 batchId
            data.put("source", source);
            if (batchId != null) data.put("batchId", batchId);
            channel.send("approval_resolved", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 approval_resolved 事件失败（客户端可能已断开）", e);
        }
    }

    // -----------------------------------------------------------------
    // P7-06：ask_user_question（执行中决策询问）
    // -----------------------------------------------------------------

    @Override
    public void onUserQuestion(String questionId, String question, String header,
                               List<Map<String, Object>> options, boolean multiSelect) {
        agentRunService.pauseForUserInput(runId, questionId, question, header, options, multiSelect);
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("runId", runId);
            data.put("questionId", questionId);
            data.put("question", question);
            if (header != null) data.put("header", header);
            if (options != null && !options.isEmpty()) data.put("options", options);
            data.put("multiSelect", multiSelect);
            channel.send("user_question", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 user_question 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onSubtask(com.capsule.agentkey.agent.subtask.SubtaskUpdate update) {
        if (update == null) {
            return;
        }
        // 父 Run 事件表只落轻量状态；子 Run 的完整事件在 child runId 下
        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("type", update.type());
        persisted.put("childRunId", update.childRunId());
        persisted.put("subtaskId", update.subtaskId());
        persisted.put("status", update.status());
        persisted.put("message", truncateForTrace(update.message(), 500));
        appendRunEvent(AgentRunEvent.EventType.SUBTASK, persisted);
        try {
            channel.send("subtask", mapper.writeValueAsString(update));
            switch (update.type()) {
                case com.capsule.agentkey.agent.subtask.SubtaskUpdate.TYPE_APPROVAL -> {
                    // 复用审批卡片：带上 childRunId，前端不会把父 Run 置为等待审批
                    Map<String, Object> data = new HashMap<>();
                    data.put("approvalId", update.approvalId());
                    data.put("tool", update.tool());
                    data.put("preview", update.preview());
                    data.put("risk", update.risk());
                    data.put("expiresAt", update.expiresAt());
                    data.put("runId", update.childRunId());
                    data.put("subtaskId", update.subtaskId());
                    channel.send("approval_required", mapper.writeValueAsString(data));
                }
                case com.capsule.agentkey.agent.subtask.SubtaskUpdate.TYPE_APPROVAL_RESOLVED -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("approvalId", update.approvalId());
                    data.put("decision", update.decision());
                    data.put("source", update.source());
                    if (update.batchId() != null) {
                        data.put("batchId", update.batchId());
                    }
                    data.put("runId", update.childRunId());
                    data.put("subtaskId", update.subtaskId());
                    channel.send("approval_resolved", mapper.writeValueAsString(data));
                }
                case com.capsule.agentkey.agent.subtask.SubtaskUpdate.TYPE_QUESTION -> {
                    // 用户回答按 childRunId 路由；父 Run 不进入 WAITING_USER_INPUT
                    Map<String, Object> data = new HashMap<>();
                    data.put("runId", update.childRunId());
                    data.put("questionId", update.questionId());
                    data.put("question", update.question());
                    if (update.header() != null) data.put("header", update.header());
                    if (update.options() != null && !update.options().isEmpty()) {
                        data.put("options", update.options());
                    }
                    data.put("multiSelect", update.multiSelect());
                    data.put("subtaskId", update.subtaskId());
                    channel.send("user_question", mapper.writeValueAsString(data));
                }
                default -> {
                    // status 更新只发 subtask 事件
                }
            }
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 subtask 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onUsage(long promptTokens, long completionTokens) {
        // P4-07：模型多次调用会分多次上报，这里只累加，终态时统一落库
        turnPromptTokens.addAndGet(promptTokens);
        turnCompletionTokens.addAndGet(completionTokens);
        // P5-10-3：每轮实时下发预算进度，前端不需要等到结束才知道用了多少
        turnRounds.incrementAndGet();
        sendUsageEvent(turnRounds.get());
    }

    @Override
    public void onChunk(String chunk) {
        long now = System.currentTimeMillis();
        // 第五十六批：记录回答阶段起始时间
        if (firstChunkAt.get() == 0) firstChunkAt.set(now);
        fullResponse.append(chunk);
        try {
            //按 JSON 下发：答案里的换行/冒号不会被 SSE 行协议截断
            Map<String, String> data = new HashMap<>();
            data.put("text", chunk);
            channel.send("content", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("写入 content 失败（客户端可能已断开）", e);
        }
        // P5-10：按固定间隔把「累计正文」落成检查点，
        // 前端断线/刷新后可凭 runId 重放到断点位置，而不是整段重答
        long last = lastContentCheckpointAt.get();
        if ((last == 0 || now - last >= CONTENT_CHECKPOINT_INTERVAL_MS)
                && lastContentCheckpointAt.compareAndSet(last, now)) {
            appendContentCheckpoint();
        }
    }

    @Override
    public void onCompletion(ReActAgentService.Completion completion) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", completion.status().name());
            data.put("reason", completion.reason());
            data.put("message", completion.message());
            data.put("filesChanged", completion.filesChanged());
            traceCompletion.set(data);
            // P6-06：断流只记录在 Run 内部结果，不向公开事件字段扩散
            Map<String, Object> result = new LinkedHashMap<>(data);
            result.put("clientDetached", channel.detached().get());
            runResultJson.set(mapper.writeValueAsString(result));
            appendRunEvent(AgentRunEvent.EventType.COMPLETION, data);
            channel.send("completion", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 completion 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onDiagnostics(DiagnosticsResult diagnostics) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("runId", runId);
            data.put("turnId", turnId);
            data.put("provider", diagnostics.provider());
            data.put("available", diagnostics.available());
            data.put("analyzedFiles", diagnostics.analyzedFiles());
            data.put("totalCount", diagnostics.totalCount());
            data.put("truncated", diagnostics.truncated());
            data.put("at", System.currentTimeMillis());
            WorkspaceContext ctx = WorkspaceContext.get();
            data.put("fileVersion", ctx == null ? "" : ctx.diagnosticsFileVersion());
            List<Map<String, Object>> items = new ArrayList<>();
            for (Diagnostic item : diagnostics.diagnostics()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("file", item.file());
                entry.put("line", item.line());
                entry.put("column", item.column());
                entry.put("severity", item.severity().name());
                entry.put("message", item.message());
                items.add(entry);
            }
            data.put("diagnostics", items);
            traceDiagnostics.set(data);
            appendRunEvent(AgentRunEvent.EventType.DIAGNOSTICS, data);
            channel.send("diagnostics", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 diagnostics 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onVerify(VerifyResult verify) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("runId", runId);
            data.put("turnId", turnId);
            data.put("status", verify.status().name());
            data.put("configured", verify.configured());
            data.put("attempt", verify.attempt());
            data.put("maxAttempts", verify.maxAttempts());
            data.put("configPath", verify.configPath());
            // 项目配置命令视为预授权；Run Event 即持久审计记录，不弹审批卡
            data.put("authorization", "project-config");
            data.put("message", verify.message());
            data.put("failureFingerprint", verify.failureFingerprint());
            data.put("at", System.currentTimeMillis());
            List<Map<String, Object>> items = new ArrayList<>();
            for (VerifyResult.CommandOutcome item : verify.commands()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", item.name());
                entry.put("command", item.command());
                entry.put("cwd", item.cwd());
                entry.put("required", item.required());
                entry.put("exitCode", item.exitCode());
                entry.put("timedOut", item.timedOut());
                entry.put("durationMs", item.durationMs());
                entry.put("skipped", item.skipped());
                entry.put("outputTail", item.outputTail());
                items.add(entry);
            }
            data.put("commands", items);
            traceVerify.set(data);
            appendRunEvent(AgentRunEvent.EventType.VERIFY, data);
            channel.send("verify", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 verify 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onNoProgress(ReActAgentService.NoProgress noProgress) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", noProgress.reason());
            data.put("toolName", noProgress.toolName());
            data.put("repeatCount", noProgress.repeatCount());
            data.put("stagnantRounds", noProgress.stagnantRounds());
            data.put("message", noProgress.message());
            appendRunEvent(AgentRunEvent.EventType.NO_PROGRESS, data);
            channel.send("no_progress", mapper.writeValueAsString(data));
        } catch (IOException e) {
            channel.detached().set(true);
            log.error("发送 no_progress 事件失败（客户端可能已断开）", e);
        }
    }

    @Override
    public void onComplete() {
        try {
            sendUsageEvent(turnRounds.get());
            // P6-06：断流只停止推送；Run 正常完成时仍保存完整回答与轨迹
            if (!channel.detached().get()) {
                channel.send("done", "[DONE]");
            }
            if (fullResponse.length() > 0) {
                // 轨迹随完整回答落库；断流不再丢弃回答，因此也不会留下残缺轨迹
                Map<String, Object> trace = new HashMap<>();
                if (traceThinking.length() > 0) {
                    trace.put("thinking", truncateForTrace(traceThinking.toString(), 6000));
                }
                if (!traceTools.isEmpty()) trace.put("tools", traceTools);
                if (!traceSources.isEmpty()) trace.put("sources", traceSources);
                if (!traceChanges.isEmpty()) trace.put("changes", traceChanges);
                // 第六十八批 A1：计划随轨迹落库，切回旧会话可还原计划面板
                Map<String, Object> plan = tracePlan.get();
                if (plan != null) trace.put("plan", plan);
                Map<String, Object> completion = traceCompletion.get();
                if (completion != null) trace.put("completion", completion);
                Map<String, Object> diagnostics = traceDiagnostics.get();
                if (diagnostics != null) trace.put("diagnostics", diagnostics);
                Map<String, Object> verify = traceVerify.get();
                if (verify != null) trace.put("verify", verify);
                // 第六十八批 A3：本轮 id 随轨迹落库，切回历史消息也能撤回该轮
                trace.put("turnId", turnId);
                // 耗时随轨迹落库，切回历史会话也能显示「已完成，用时…」
                long totalMs = System.currentTimeMillis() - traceStartedAt;
                trace.put("durationMs", totalMs);

                // 第五十六批：耗时口径拆分——写入三段耗时用于追踪面板 breakdown 展示
                // setup: traceStartedAt → 第一次工具调用 或 第一个回答字符
                // tools: 所有工具调用 costMs 之和（精确到单次工具执行，含 ReAct 循环间的 LLM 决策时间）
                // answer: 第一个回答字符 → 完成
                long setupMs;
                long answerMs;
                if (firstToolCallAt.get() > 0) {
                    setupMs = firstToolCallAt.get() - traceStartedAt;
                } else if (firstChunkAt.get() > 0) {
                    setupMs = firstChunkAt.get() - traceStartedAt;
                } else {
                    setupMs = totalMs;
                }
                if (firstChunkAt.get() > 0) {
                    answerMs = System.currentTimeMillis() - firstChunkAt.get();
                } else {
                    answerMs = 0;
                }
                long toolsMs = toolCostMsSum.get();
                // 安全兜底：setup + tools + answer 不能超过 total
                if (setupMs + toolsMs + answerMs > totalMs) {
                    // 只保留 total - setup - answer 作为 tools 的兜底
                    toolsMs = Math.max(0, totalMs - setupMs - answerMs);
                }
                List<Map<String, Object>> segments = new ArrayList<>();
                Map<String, Object> s1 = new HashMap<>();
                s1.put("phase", "setup");
                s1.put("ms", setupMs);
                segments.add(s1);
                Map<String, Object> s2 = new HashMap<>();
                s2.put("phase", "tools");
                s2.put("ms", toolsMs);
                segments.add(s2);
                Map<String, Object> s3 = new HashMap<>();
                s3.put("phase", "answer");
                s3.put("ms", answerMs);
                segments.add(s3);
                trace.put("segments", segments);
                ChatMessage savedAssistant = chatService.saveMessage(userId, chatSessionId,
                        ChatMessage.MessageRole.ASSISTANT,
                        fullResponse.toString(), null,
                        mapper.writeValueAsString(trace));
                // R8a：被用户停止的轮次打标，回放历史时标注为「已由用户中断」
                Map<String, Object> completionTrace = traceCompletion.get();
                if (completionTrace != null && "CANCELLED".equals(completionTrace.get("reason"))
                        && savedAssistant != null && savedAssistant.getId() != null) {
                    chatService.markCancelled(savedAssistant.getId());
                }
                // 批次 B2：会话级自动记忆——每次助手消息后判断是否到了触发点
                try {
                    memoryService.onAssistantMessage(chatSessionId, userId, apiKey, policy.model());
                } catch (Exception ignore) {
                    // 提炼失败不影响主流程，Service 内部已经 log
                }
            }
        } catch (Exception e) {
            log.error("SSE 完成处理失败", e);
        } finally {
            persistRunUsage();
            // P6-06：断流时补写最后一个正文检查点，把最后不到 1s 的内容也保住
            if (channel.detached().get() && fullResponse.length() > 0) {
                appendContentCheckpoint();
            }
            Map<String, Object> completion = traceCompletion.get();
            boolean agentCancelled = completion != null
                    && "CANCELLED".equals(completion.get("reason"));
            if (agentCancelled) {
                markRunCancelled("agent cancelled");
            } else {
                markRunSucceeded(runResultJson.get());
            }
            channel.emitter().complete();
        }
    }

    @Override
    public void onError(Throwable t) {
        fail(t, "Agent 执行错误");
    }

    /**
     * 统一的失败收尾：落用量、置运行终态、下发 error 事件并关闭连接。
     * 供 {@link #onError} 与 Controller 里 execute 的兜底 catch 共用。
     */
    public void fail(Throwable t, String logMessage) {
        persistRunUsage();
        boolean budgetExceeded = t instanceof TokenBudgetExceededException;
        markRunFailed(budgetExceeded ? "TOKEN_BUDGET_EXCEEDED" : "AGENT_EXECUTION_FAILED",
                t.getMessage());
        //真实异常只进日志；下发给用户的是一句能看懂的话
        log.error(logMessage, t);
        try {
            channel.send("error",
                    budgetExceeded ? t.getMessage() : "这次回答没有完成，请稍后再试一次");
        } catch (Exception ignore) {
            //连接已断，无需再发
        } finally {
            channel.emitter().complete();
        }
    }

    /** 运行落库失败不能覆盖原始聊天错误，也不能把终态再次迁移。 */
    private void markRunSucceeded(String resultJson) {
        try {
            agentRunService.succeedIfActive(runId, resultJson);
        } catch (Exception e) {
            log.error("更新运行成功状态失败: runId={}", runId, e);
        }
    }

    private void markRunFailed(String code, String message) {
        try {
            agentRunService.failIfActive(runId, code, message);
        } catch (Exception e) {
            log.error("更新运行失败状态失败: runId={}", runId, e);
        }
    }

    private void markRunCancelled(String reason) {
        try {
            agentRunService.cancelIfActive(runId, reason);
        } catch (Exception e) {
            log.error("更新运行取消状态失败: runId={}", runId, e);
        }
    }

    /** P4-07：用量只落库一次；失败只告警，不影响对话结果 */
    private void persistRunUsage() {
        if (!usagePersisted.compareAndSet(false, true)) {
            return;
        }
        try {
            agentRunService.recordUsage(runId, turnPromptTokens.get(), turnCompletionTokens.get());
        } catch (Exception e) {
            log.warn("记录 token 用量失败 [runId={}]: {}", runId, e.getMessage());
        }
    }

    /**
     * P4-07 / P5-10-3：把本轮用量与预算进度下发给前端。
     * round / maxRounds / maxTokens / elapsedMs / maxElapsedMs 为 P5-10-3 新增字段，
     * 旧前端忽略即可；旧后端不发这些字段时前端按「不限/—」降级。
     */
    private void sendUsageEvent(int round) {
        long p = turnPromptTokens.get();
        long c = turnCompletionTokens.get();
        if (p <= 0 && c <= 0) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("promptTokens", p);
            data.put("completionTokens", c);
            data.put("totalTokens", p + c);
            data.put("round", round);
            data.put("maxRounds", policy.reasoning().maxToolRounds());
            data.put("maxTokens", maxTokensPerRun);
            data.put("elapsedMs", Math.max(0L, System.currentTimeMillis() - traceStartedAt));
            data.put("maxElapsedMs", maxSecondsPerRun > 0 ? maxSecondsPerRun * 1000L : 0L);
            channel.send("usage", mapper.writeValueAsString(data));
        } catch (Exception e) {
            log.debug("下发 usage 事件失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /** 运行事件落库失败不能中断聊天流，只记录告警 */
    private void appendRunEvent(AgentRunEvent.EventType type, Map<String, Object> payload) {
        try {
            agentRunService.appendEvent(runId, type, payload);
        } catch (Exception e) {
            log.warn("写入运行事件失败 [runId={}, type={}]: {}", runId, type, e.getMessage());
        }
    }

    private void appendContentCheckpoint() {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("text", truncateForTrace(fullResponse.toString(), CONTENT_CHECKPOINT_LIMIT));
        checkpoint.put("length", fullResponse.length());
        appendRunEvent(AgentRunEvent.EventType.MODEL_CHUNK, checkpoint);
    }

    /** 轨迹落库前截断：工具返回可能是整份文件，避免把 chat_message 撑到几 MB */
    private static String truncateForTrace(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max) + "…（已截断）";
    }

    private static boolean equalsIgnoreCase(Object value, String expected) {
        return value != null && expected != null && expected.equalsIgnoreCase(value.toString());
    }
}
