package com.capsule.agentkey.service;

import com.capsule.agentkey.agent.run.PlanStep;
import com.capsule.agentkey.agent.run.RunContext;
import com.capsule.agentkey.client.AgentClient;
import com.capsule.agentkey.entity.AgentRule;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import org.springframework.ai.tool.ToolCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;



//Agent大脑决策
@Service
@Slf4j
public class ReActAgentService {

    // P5-01：提示词正文已外置到 classpath:prompts/，这里只保留 PromptCatalog 的引用。
    // 使用哪个版本由 agent.prompt.scenario.* 配置决定，改文案不需要改 Java 代码。

    //第四十九批删旧：Spring AI 是唯一模型接入层，自研 okhttp 客户端已移除
    private final AgentClient springAiClient;
    private final ToolRegistry toolRegistry;
    private final DocumentService documentService;
    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;
    //第五十一批：MCP 工具桥接——会话开始时把项目 workspacePath 动态注入 MCP Roots 白名单
    private final com.capsule.agentkey.mcp.McpToolBridge mcpToolBridge;
    //第六十五批：AgentRule 三层规则注入 system prompt
    private final com.capsule.agentkey.repository.AgentRuleRepository ruleRepo;
    //第六十八批 A2：危险工具执行前的人工审批
    private final ToolApprovalRegistry toolApprovalRegistry;
    //D1 批次：用户消息里的文件范围约束（scope 声明 → 工具执行前拦截）
    private final ScopeConstraintService scopeConstraintService;
    private final SessionPermissionTracker permissionTracker;
    /** P5-01：按场景加载的提示词目录 */
    private final PromptCatalog promptCatalog;
    /** P5-05：变更文件诊断采集；任何失败都在内部降级，不影响主循环 */
    private final com.capsule.agentkey.diagnostics.DiagnosticsService diagnosticsService;
    /** P5-06：项目验证回路；命令经 CommandService 沙箱执行，失败回灌下一轮 */
    private final com.capsule.agentkey.verify.VerifyService verifyService;
    /** P5-04 前置：工作区 repo map（目录 + 关键符号），替代原来的单层目录树 */
    private final RepoMapService repoMapService;
    /** P7-06：执行中用户询问——ask_user_question 的挂起/恢复机制；测试手动 new 时为 null，走 null check */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.capsule.agentkey.tool.AskUserQuestionTool askUserQuestionTool;
    /** P7-06：询问超时/中断后把 Run 从 WAITING_USER_INPUT 拉回 RUNNING；测试手动 new 时为 null，走 null check */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentRunService agentRunService;
    /** R1：计划快照落库 + 事件追加；测试手动 new 时为 null，计划只保留内存快照 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.capsule.agentkey.agent.run.RunEventStore runEventStore;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * P5-08：单轮内连续只读工具的并发上限。
     * 取 3 的依据：Runtime 评测里单轮只读批的实际规模为 2～3；fixture 基准（3 个 120ms 模拟只读）
     * 在 3 并发时 p95 已接近理论下界，继续加线程只会增加调度与上下文切换开销。
     * 写/命令批不进入该池，仍保持串行 + 审批。
     */
    @org.springframework.beans.factory.annotation.Value("${agent.parallel.readonly-max-concurrency:3}")
    private int readonlyMaxConcurrency = 3;

    /** 只读批执行池：daemon 线程，按需初始化；关闭时中断未完成任务（R3 已迁入 ToolExecutor） */

    /** R3：工具执行的唯一入口（串行 + 只读并行批），持有只读批线程池 */
    private volatile com.capsule.agentkey.agent.tool.ToolExecutor toolExecutor;
    /** R3：审批与 scope 判定的唯一入口 */
    private final com.capsule.agentkey.agent.tool.ApprovalGate approvalGate;
    /** R3：文件变更收集与诊断 */
    private final com.capsule.agentkey.agent.run.FileChangeService fileChangeService;
    /** R4b：ReAct 主循环编排器；延迟构建以读取 @Value 配置与可选协作者 */
    private volatile AgentOrchestrator orchestrator;

    //B2 已移至 AgentPolicy.Reasoning.toolContentLimit()，按档位动态收紧（FAST=500, STANDARD=1000, DEEP=2000）

    //P4-07：单次运行 token 预算，0 表示不限制；超限直接中断本轮
    @org.springframework.beans.factory.annotation.Value("${agent.budget.max-tokens-per-run:0}")
    private long maxTokensPerRun = 0L;

    //P5-10-3：单次运行时间预算（秒），0 表示不限制；超限在下一轮开始时安全停止
    @org.springframework.beans.factory.annotation.Value("${agent.budget.max-seconds-per-run:0}")
    private long maxSecondsPerRun = 0L;

    //P4-07：超过该长度的工具结果落文件，上下文只保留前若干字符 + 路径
    @org.springframework.beans.factory.annotation.Value("${agent.budget.tool-spill-chars:20000}")
    private int toolSpillChars = 20000;

    //P4-07：工具结果落盘目录；默认跟随桌面端数据目录，避免写系统临时目录
    @org.springframework.beans.factory.annotation.Value(
            "${agent.budget.tool-spill-dir:${user.home}/.lunargold/tool-results}")
    private String toolSpillDir = System.getProperty("user.home") + "/.lunargold/tool-results";

    // P5-13：连续相同调用/失败达到阈值后安全停止，0 表示关闭对应检测
    @org.springframework.beans.factory.annotation.Value("${agent.loop.no-progress.max-repeats:3}")
    private int maxRepeatedToolCalls = 3;

    // P5-13：没有新工具结果、计划或文件变化的连续轮次上限，0 表示关闭
    @org.springframework.beans.factory.annotation.Value("${agent.loop.no-progress.max-stagnant-rounds:4}")
    private int maxStagnantRounds = 4;

    private static final int SPILL_PREVIEW_CHARS = 1500;

    /**
     * B2：超长工具结果截断，末尾附提示让模型知道内容不完整。
     * 构建/测试日志的关键信息（报错行、失败原因）通常在末尾，只留头部会让模型看不到问题，
     * 所以改成「头部 + 尾部」各留一半。
     *
     * <p>P4-07：超过 {@code toolSpillChars} 的结果先整份落文件，
     * 上下文里只留头部预览和文件路径，需要细节时模型可以自己 read_file。
     *
     * <p>P7-04b：limit 不再固定 2000，改为随推理档位动态收紧
     * （FAST=500, STANDARD=1000, DEEP=2000），避免短任务也塞大段工具结果。
     */
    private String truncateForContext(String s, int limit) {
        if (s == null || s.length() <= limit) {
            return s;
        }
        if (s.length() > toolSpillChars) {
            String path = spillToolResult(s);
            if (path != null) {
                return s.substring(0, Math.min(SPILL_PREVIEW_CHARS, s.length()))
                        + "\n…（内容过长，完整内容已写入 " + path
                        + "，需要细节请用 read_file 分段读取）…";
            }
        }
        int half = limit / 2;
        return s.substring(0, half)
                + "\n…（中间内容省略）…\n"
                + s.substring(s.length() - half);
    }

    /** 落盘失败时返回 null，调用方退化为普通截断，不能因为磁盘问题中断对话 */
    private String spillToolResult(String content) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(toolSpillDir);
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve("tool-"
                    + java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                    + "-" + java.util.UUID.randomUUID().toString().substring(0, 6) + ".txt");
            java.nio.file.Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
            log.info("工具结果过大（{} 字符），已写入 {}", content.length(), file);
            return file.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("工具结果落盘失败，退化为截断: {}", e.getMessage());
            return null;
        }
    }

    //审批卡片预览：把工具入参整理成"做什么 + 哪个文件 + 改了多少行"，不再把裸 JSON 甩给用户
    private static final int APPROVAL_DIFF_MAX_LINES = 20;

    /** 工作区文件快照：exists 表示文件是否存在，content 为 null 表示过大/不可读 */
    private record WorkspaceSnapshot(boolean exists, String content) {
    }

    private com.capsule.agentkey.dto.ApprovalPreview buildApprovalPreview(
            String toolName, String arguments, Long userId, Long projectId) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    mapper.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            switch (toolName) {
                case "run_command" -> {
                    String command = node.path("command").asText("").trim();
                    String cwd = node.path("cwd").asText("");
                    int timeout = node.path("timeout").asInt(0);
                    String note = timeout > 0 ? "超时上限 " + timeout + " 秒" : "默认超时 120 秒";
                    return new com.capsule.agentkey.dto.ApprovalPreview(
                            "command", cwd, command, null, 0, 0, false, note);
                }
                case "write_file" -> {
                    String rel = node.path("path").asText("");
                    String content = node.path("content").asText("");
                    WorkspaceSnapshot old = readWorkspaceFile(userId, projectId, rel);
                    String base = old.content() == null ? "" : old.content();
                    com.capsule.agentkey.util.SimpleDiff.Result diff =
                            com.capsule.agentkey.util.SimpleDiff.compute(base, content, APPROVAL_DIFF_MAX_LINES);
                    boolean newFile = !old.exists();
                    String note = newFile ? "新建文件"
                            : (old.content() == null ? "文件过大，仅统计改动行数" : null);
                    return new com.capsule.agentkey.dto.ApprovalPreview(
                            "write", rel, null, diff.text(), diff.additions(), diff.deletions(), newFile, note);
                }
                case "edit_file" -> {
                    String rel = node.path("path").asText("");
                    String oldText = node.path("oldText").asText("");
                    String newText = node.path("newText").asText("");
                    com.capsule.agentkey.util.SimpleDiff.Result diff =
                            com.capsule.agentkey.util.SimpleDiff.compute(oldText, newText, APPROVAL_DIFF_MAX_LINES);
                    return new com.capsule.agentkey.dto.ApprovalPreview(
                            "edit", rel, null, diff.text(), diff.additions(), diff.deletions(), false, null);
                }
                case "delete_file" -> {
                    String rel = node.path("path").asText("");
                    boolean recursive = node.path("recursive").asBoolean(false);
                    WorkspaceSnapshot old = readWorkspaceFile(userId, projectId, rel);
                    String base = old.content() == null ? "" : old.content();
                    com.capsule.agentkey.util.SimpleDiff.Result diff =
                            com.capsule.agentkey.util.SimpleDiff.compute(base, "", APPROVAL_DIFF_MAX_LINES);
                    String note = recursive ? "将连同目录内所有文件一起删除（已记录的文件可在审查流中恢复）"
                            : (old.exists() ? "将删除该文件，可在审查流中撤回" : "路径不存在或不是文件");
                    return new com.capsule.agentkey.dto.ApprovalPreview(
                            "delete", rel, null, diff.text(), 0, diff.deletions(), false, note);
                }
                default -> {
                    String brief = arguments == null ? "" : arguments;
                    if (brief.length() > 300) {
                        brief = brief.substring(0, 300) + "…";
                    }
                    return new com.capsule.agentkey.dto.ApprovalPreview(
                            "other", "", null, brief, 0, 0, false, null);
                }
            }
        } catch (Exception e) {
            log.warn("构建审批预览失败: tool={}", toolName, e);
            return new com.capsule.agentkey.dto.ApprovalPreview(
                    "other", "", null, "", 0, 0, false, "无法解析工具参数");
        }
    }

    /** 读取工作区内目标文件的当前内容；不存在或不可读时返回对应快照，不抛异常 */
    private WorkspaceSnapshot readWorkspaceFile(Long userId, Long projectId, String relPath) {
        try {
            if (projectId == null || relPath == null || relPath.isBlank()) {
                return new WorkspaceSnapshot(false, null);
            }
            var project = projectService.getOwned(userId, projectId);
            String rootPath = project.getWorkspacePath();
            if (rootPath == null || rootPath.isBlank()) {
                return new WorkspaceSnapshot(false, null);
            }
            java.nio.file.Path root = java.nio.file.Paths.get(rootPath);
            java.nio.file.Path target = workspaceFileService.resolveInWorkspace(root, relPath);
            if (!java.nio.file.Files.isRegularFile(target)) {
                return new WorkspaceSnapshot(false, null);
            }
            if (java.nio.file.Files.size(target) > 256 * 1024) {
                return new WorkspaceSnapshot(true, null);
            }
            return new WorkspaceSnapshot(true,
                    java.nio.file.Files.readString(target, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new WorkspaceSnapshot(false, null);
        }
    }

    public ReActAgentService(AgentClient springAiClient,
                             ToolRegistry toolRegistry,
                             DocumentService documentService,
                             ProjectService projectService,
                             WorkspaceFileService workspaceFileService,
                             com.capsule.agentkey.mcp.McpToolBridge mcpToolBridge,
                             com.capsule.agentkey.repository.AgentRuleRepository ruleRepo,
                             ToolApprovalRegistry toolApprovalRegistry,
                             ScopeConstraintService scopeConstraintService,
                             SessionPermissionTracker permissionTracker,
                             PromptCatalog promptCatalog,
                             com.capsule.agentkey.diagnostics.DiagnosticsService diagnosticsService,
                             com.capsule.agentkey.verify.VerifyService verifyService,
                             RepoMapService repoMapService) {
        this.springAiClient = springAiClient;
        this.toolRegistry = toolRegistry;
        this.documentService = documentService;
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
        this.mcpToolBridge = mcpToolBridge;
        this.ruleRepo = ruleRepo;
        this.toolApprovalRegistry = toolApprovalRegistry;
        this.scopeConstraintService = scopeConstraintService;
        this.permissionTracker = permissionTracker;
        this.promptCatalog = promptCatalog;
        this.diagnosticsService = diagnosticsService;
        this.verifyService = verifyService;
        this.repoMapService = repoMapService;
        // R3：审批/scope 判定与文件变更处理从主循环抽出；ToolExecutor 延迟构建以读取 @Value 配置
        this.approvalGate = new com.capsule.agentkey.agent.tool.ApprovalGate(
                toolApprovalRegistry, toolRegistry, scopeConstraintService);
        this.fileChangeService = new com.capsule.agentkey.agent.run.FileChangeService(diagnosticsService);
    }

    /** R3：延迟构建，保证 readonlyMaxConcurrency 已被 Spring 注入 */
    private com.capsule.agentkey.agent.tool.ToolExecutor toolExecutor() {
        com.capsule.agentkey.agent.tool.ToolExecutor executor = toolExecutor;
        if (executor == null) {
            synchronized (this) {
                executor = toolExecutor;
                if (executor == null) {
                    executor = new com.capsule.agentkey.agent.tool.ToolExecutor(
                            toolRegistry, toolApprovalRegistry, approvalGate,
                            permissionTracker, readonlyMaxConcurrency);
                    toolExecutor = executor;
                }
            }
        }
        return executor;
    }

    /** R4b：延迟构建，保证 @Value 配置与可选协作者都已注入；循环本身不持有 Spring 状态 */
    private AgentOrchestrator orchestrator() {
        AgentOrchestrator value = orchestrator;
        if (value == null) {
            synchronized (this) {
                value = orchestrator;
                if (value == null) {
                    value = new AgentOrchestrator(
                            springAiClient, toolExecutor(), fileChangeService, verifyService,
                            askUserQuestionTool, agentRunService,
                            this::truncateForContext,
                            new AgentOrchestrator.Limits(maxRepeatedToolCalls, maxStagnantRounds,
                                    maxTokensPerRun, maxSecondsPerRun));
                    orchestrator = value;
                }
            }
        }
        return value;
    }

    /** R3：把 SSE 回调适配成 ToolExecutor 的审批 UI */
    private com.capsule.agentkey.agent.tool.ToolExecutor.ApprovalUi approvalUi(
            StreamCallback callback, Long userId, Long projectId) {
        return new com.capsule.agentkey.agent.tool.ToolExecutor.ApprovalUi() {
            @Override
            public void onApprovalRequired(ToolApprovalRegistry.Pending pending, String risk) {
                var preview = buildApprovalPreview(pending.tool(), pending.arguments(), userId, projectId);
                callback.onApprovalRequired(pending.approvalId(), pending.tool(), preview, risk,
                        pending.expiresAt());
            }

            @Override
            public void onApprovalResolved(ToolApprovalRegistry.Pending pending,
                                           ToolApprovalRegistry.Resolution resolution) {
                callback.onApprovalResolved(pending.approvalId(), resolution.decision().name(),
                        resolution.source().name(), resolution.batchId());
            }
        };
    }

    /** P5-13：交付完成状态；与 AgentRun 的执行生命周期分开，SUCCEEDED 不代表已经验证。 */
    public enum CompletionStatus { COMPLETED, UNVERIFIED, INCOMPLETE }

    /** P5-13：候选完成判定结果。P5-05/P5-06 接入前，文件变更只能标记为未验证。 */
    public record Completion(CompletionStatus status, String reason, String message, boolean filesChanged) {}

    /** P5-13：无进展停止的结构化原因。 */
    public record NoProgress(String reason, String toolName, int repeatCount,
                             int stagnantRounds, String message) {}

    //流式回调接口
    public interface StreamCallback {
        void onThinking(String thought);//模型思考内容
        void onToolCall(String toolName, String arguments);//触发工具调用，显示调用工具，入参
        void onToolResult(String toolName, ToolResult result);//工具执行完毕，推送工具返回结果
        //工作区文件被工具改动时推送（write_file/edit_file/run_command），前端据此展示变更文件与 diff
        default void onFileChange(com.capsule.agentkey.tool.FileChange change) {}
        //第六十八批 A1：update_plan 提交/更新任务计划后推送，前端实时刷新计划面板
        default void onPlan(java.util.List<PlanStep> steps) {}
        //P4-07：本轮模型调用消耗的 token，上层按 run 累加并在终态落库
        default void onUsage(long promptTokens, long completionTokens) {}
        //第六十八批 A2：危险工具执行前请求用户审批，approvalId 用于前端回传决策
        default void onApprovalRequired(String approvalId, String tool,
                                        com.capsule.agentkey.dto.ApprovalPreview preview,
                                        String risk, long expiresAt) {}
        //批次 B：审批被处理后的结果通知（无论用户批准/拒绝、超时、断开，前端据此把卡片从"待决策"态切换到"已处理"态）
        //P5-10-2：一并回传决策来源与批次 id，供前端展示与 Run 审计使用
        default void onApprovalResolved(String approvalId, String decision, String source, String batchId) {}
        void onChunk(String chunk);//大模型普通文字流式分片，逐字返回给前端
        //命中的资料出处，用于回答下方展示「参考了哪些资料」；无命中时不触发
        default void onSources(java.util.List<DocumentService.Source> sources) {}
        /** P5-13：最终 done 之前推送交付状态，断线重放也使用同一载荷。 */
        default void onCompletion(Completion completion) {}
        /** P5-13：重复调用或无推进时推送；随后以 INCOMPLETE 正常收尾。 */
        default void onNoProgress(NoProgress noProgress) {}
        /** P5-05：变更文件诊断结果，供 SSE、Run Event 与任务验证摘要使用 */
        default void onDiagnostics(com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics) {}
        /** P5-06：项目验证结果，供 SSE、Run Event 与任务验证摘要使用 */
        default void onVerify(com.capsule.agentkey.verify.VerifyResult verify) {}
        /** P7-06：agent 暂停等用户回答决策询问，questionId 用于用户回答时关联 */
        default void onUserQuestion(String questionId, String question, String header,
                                    java.util.List<java.util.Map<String, Object>> options, boolean multiSelect) {}
        void onComplete();//整套智能体流程全部结束
        void onError(Throwable t);//发生异常，推送错误报告
    }

    //alt+ctrl+f7查找使用处
    public void execute(String apiKey, //大模型密钥
                        AgentPolicy policy,//本次对话的模型/推理档位/工作权限
                        String userPrompt,//用户当前提问文本
                        List<ChatMessage> history,//历史聊天记录
                        Long userId,//登录用户id，用于RAG检索该用户的附件向量
                        Long projectId,//当前项目id，RAG 只检索本项目内的资料
                        Long sessionId,//当前会话 id，用于 PendingFileChange 落库关联
                        String turnId,//第六十八批 A3：本轮执行 id，本轮文件改动共享，用于按轮次撤回
                        boolean useRag,//开关，是否开启知识库检索
                        String fileContext,//附带文本文件
                        String imageContext,//截图经视觉模型转写后的文字（无图时为空）
                        String contextSummary,//较早对话的滚动摘要（长会话压缩后注入，无摘要时为空）
                        String sessionReferenceContext,//用户用 @会话 点名的其他会话的最近问答（无点名时为空）
                        AtomicBoolean cancelRequested,//P6-06：用户显式停止标记，断流不会置位
                        StreamCallback callback) {//流式回调，向前端推送每一步状态
        execute(apiKey, policy, userPrompt, history, userId, projectId, null, sessionId, turnId, useRag,
                fileContext, imageContext, contextSummary, sessionReferenceContext, cancelRequested, callback);
    }

    /** P7-06：带 runId 的重载——ask_user_question 挂起/恢复需要 runId 做 key */
    public void execute(String apiKey, AgentPolicy policy, String userPrompt, List<ChatMessage> history,
                        Long userId, Long projectId, Long runId, Long sessionId, String turnId,
                        boolean useRag, String fileContext, String imageContext, String contextSummary,
                        String sessionReferenceContext, AtomicBoolean cancelRequested, StreamCallback callback) {
        // R1：runId / plan / 取消标记等 Run 级状态收敛到 RunContext，
        // WorkspaceContext 只作为工具线程视图（dispatch 签名不变）。
        // 计划变更会通过回调同步落 agent_run.plan_json 并追加 PLAN_UPDATED 事件。
        RunContext runContext = createRunContext(runId, userId, projectId, sessionId,
                turnId, cancelRequested);
        WorkspaceContext.set(runContext);
        // 记录本轮起始权限；运行中用户在会话里改档位会覆盖这里，下一次工具调用即刻生效
        permissionTracker.put(sessionId, policy.permission());
        //D1: 解析用户消息里的 scope 声明，进 WorkspaceContext；文件类工具执行前会拦截
        scopeConstraintService.parseAndApply(userPrompt);
        // 第六十批：每次新 execute() 开始时 reset 会话级工具统计
        toolRegistry.resetSessionStats();
        try {
            //如果有需要，先检索知识库
            String ragContext = "";
            if (useRag && userId != null) {
                try {
                    //消息里用 @资料:《文件名》点名的资料，检索时只在这些资料内查
                    List<Long> referencedDocs = documentService.resolveReferencedDocuments(userId, projectId, userPrompt);
                    //检索进度不写进思考链：它是内部动作，不是模型的推理过程，
                    //留在思考记录里会让用户以为回答结束后还在检索。
                    //最后参数指最相关的三条文档片段
                    DocumentService.RagResult rag = documentService.retrieveContext(
                            userId, projectId, userPrompt, 3, referencedDocs);
                    ragContext = rag.context();
                    if (!rag.sources().isEmpty()) {
                        callback.onSources(rag.sources());//先把出处推给前端，回答里就能标注来源
                        log.info("RAG 检索到 {} 条相关知识", rag.sources().size());
                    }
                } catch (Exception e) {
                    log.warn("RAG 检索失败，降级为普通模式: {}", e.getMessage());
                }
            }

            //构建消息（系统提示词 + 历史记录 + 用户问题）
            //先拿项目级自定义指令，拼进 system prompt；拿不到或为空则按无指令处理，不阻塞对话
            String projectInstruction = "";
            String repoMap = "";
            String workspacePath = null;
            try {
                Long resolvedId = projectService.resolveProjectId(userId, projectId);
                com.capsule.agentkey.entity.Project p = projectService.getOwned(userId, resolvedId);
                projectInstruction = p.getInstruction();
                workspacePath = p.getWorkspacePath();
                //绑定工作区时生成 repo map（目录 + 关键符号），让 Agent 少做探索性读取
                if (workspacePath != null) {
                    repoMap = repoMapService.build(workspacePath);
                }
            } catch (Exception e) {
                log.warn("读取项目指令/工作区失败，降级: {}", e.getMessage());
            }

            //摘要统一交给 SessionMemoryAdvisor 在 Spring AI 调用链注入（第四十九批删旧后唯一路径），
            //system prompt 里不再重复拼接
            String advisorSummary = contextSummary;

            List<ObjectNode> messages = buildMessages(userId, projectId, sessionId,
                    history, userPrompt, ragContext, fileContext,
                    imageContext, sessionReferenceContext,
                    projectInstruction, repoMap, workspacePath, policy,
                    runContext.plan());
            if (!ragContext.isEmpty()) {
                log.info("知识库上下文已注入 system 消息（{} 字符），大模型可见", ragContext.length());
            }
            //第六十批：工具清单先用 tool scoring 智能排序（关键词匹配 + 历史成功率 + 启发式），
            //再送模型——模型优先看到最相关的工具，减少瞎选
            List<ToolCallback> tools = toolRegistry.buildRankedToolCallbacks(userPrompt, policy.permission());
            log.info("本轮策略: model={}, reasoning={}, permission={}, tools={}",
                    policy.model(), policy.reasoning().name(), policy.permission().name(), tools.size());

            //第五十一批：把当前项目的工作区路径动态注入 MCP server 的 Root 白名单
            //filesystem server 启动时不传命令行根目录，全靠会话里这行来允许访问项目文件
            try {
                if (workspacePath != null && !workspacePath.isBlank()) {
                    mcpToolBridge.bindWorkspacePath(workspacePath);
                }
            } catch (Exception e) {
                // MCP 初始化失败不能阻塞主对话——用户可能根本没开 MCP，或 server 还在冷启动
                log.warn("MCP Root 注入失败，降级为不注入: {}", e.getMessage());
            }

            // R4b：主循环（模型流式调用、工具批、诊断/验证、计划收口）统一由 AgentOrchestrator 执行
            orchestrator().run(new AgentOrchestrator.Invocation(
                    apiKey, policy, messages, tools, advisorSummary, workspacePath,
                    userId, projectId, sessionId, cancelRequested, callback,
                    approvalUi(callback, userId, projectId)));

        } catch (Exception e) {
            log.error("ReAct 执行异常", e);
            // 异常退出同样要先收口计划，再通知前端出错
            orchestrator().finalizePlan(callback);
            callback.onError(e);
        } finally {
            // P7-06：无论正常结束、异常还是取消，都清掉本 Run 可能残留的 pending question，
            // 避免 AskUserQuestionTool 内部 map 泄漏；ask() 正常返回时已自行移除，这里是兜底
            if (askUserQuestionTool != null && WorkspaceContext.get() != null) {
                Long pendingRunId = WorkspaceContext.get().runId();
                if (pendingRunId != null) askUserQuestionTool.cleanup(pendingRunId);
            }
            WorkspaceContext.clear();
        }
    }

    /**
     * R1：构建本次 Run 的权威上下文。
     *
     * <p>新 Run 的初始计划为空；同一 runId 续跑/重放时从 {@code agent_run.plan_json}
     * 恢复快照，保证模型看到的计划不是线程内存里的临时值。
     * 计划变更回调负责落库并写 {@code PLAN_UPDATED} 事件；持久化失败由
     * {@link RunContext#updatePlan} 内部降级，不影响主循环。
     */
    private RunContext createRunContext(Long runId, Long userId, Long projectId, Long sessionId,
                                        String turnId, AtomicBoolean cancelRequested) {
        List<PlanStep> persistedPlan = List.of();
        if (runId != null && agentRunService != null) {
            try {
                persistedPlan = agentRunService.loadPlan(runId);
            } catch (Exception e) {
                log.warn("读取 Run 计划快照失败，按空计划继续：{}", e.getMessage());
            }
        }
        return RunContext.create(runId, userId, projectId, sessionId, turnId, cancelRequested,
                persistedPlan,
                steps -> {
                    if (runEventStore != null && runId != null) {
                        runEventStore.savePlan(runId, steps);
                    }
                });
    }

    /** P5-10-3：时间预算是否已耗尽；maxSeconds <= 0 表示不限制。 */
    static boolean isTimeBudgetExceeded(long elapsedMs, long maxSeconds) {
        return AgentOrchestrator.isTimeBudgetExceeded(elapsedMs, maxSeconds);
    }

    private List<ObjectNode> buildMessages(Long userId, Long projectId, Long sessionId,
                                           List<ChatMessage> history, String userPrompt,
                                           String ragContext, String fileContext, String imageContext,
                                           String sessionReferenceContext,
                                           String projectInstruction, String repoMap, String workspacePath,
                                           AgentPolicy policy,
                                           List<PlanStep> planSnapshot) {
        List<ObjectNode> messages = new ArrayList<>();

        //第六十五批：三层 AgentRule 注入——在固定骨架之前拼，权重最高
        //规则按 scope 排序：GLOBAL → PROJECT → SESSION，后者可覆盖前者
        StringBuilder systemContent = new StringBuilder();
        try {
            var enabledRules = ruleRepo.findEffectiveRules(userId, projectId, sessionId);
            var filtered = enabledRules.stream().filter(r -> r.isEnabled()).toList();
            if (!filtered.isEmpty()) {
                systemContent.append("【用户自定义规则】\n");
                for (var r : filtered) {
                    systemContent.append("- ").append(r.getContent().trim()).append("\n\n");
                }
                log.info("AgentRule 已注入 system 消息：{} 条（GLOBAL={}, PROJECT={}, SESSION={}）",
                        filtered.size(),
                        filtered.stream().filter(r -> r.getScope() == AgentRule.RuleScope.GLOBAL).count(),
                        filtered.stream().filter(r -> r.getScope() == AgentRule.RuleScope.PROJECT).count(),
                        filtered.stream().filter(r -> r.getScope() == AgentRule.RuleScope.SESSION).count());
            }
        } catch (Exception e) {
            log.warn("AgentRule 读取失败，降级：{}", e.getMessage());
        }

        // P5-01/P5-13：绑定工作区时使用编程交付提示词；普通问答仍走通用助手场景
        String promptScenario = workspacePath == null || workspacePath.isBlank() ? "agent" : "coding";
        systemContent.append(promptCatalog.text(promptScenario));

        //项目级自定义指令：补充对话约束（如背景、口吻、领域要求），权重低于内置规则但高于 RAG 上下文
        if (projectInstruction != null && !projectInstruction.isBlank()) {
            systemContent.append("\n\n【项目自定义要求】\n").append(projectInstruction.trim());
            log.info("项目指令已注入 system 消息（{} 字符）", projectInstruction.trim().length());
        }

        //当前工作权限：让模型知道哪些操作不可用，避免反复尝试越权工具
        systemContent.append("\n\n【当前工作权限】\n").append(policy.permission().promptRule());

        //【第五十一批】MCP 外部工具规则：和内置文件工具功能重叠，指导模型别重复调（P5-01 外置）
        systemContent.append("\n\n").append(promptCatalog.text("tool-mcp"));

        //仓库结构（repo map）：目录 + 关键符号摘要，让 Agent 了解项目结构后按需深入
        if (repoMap != null && !repoMap.isBlank()) {
            systemContent.append("\n\n【仓库结构（repo map）】\n")
                    .append("以下是当前工作区的目录与关键符号摘要（超出预算会截断），")
                    .append("可用 list_dir 查看子目录、read_file 读取文件内容：\n")
                    .append(repoMap);
        }
        //没有执行权限时整段终端说明都不注入，避免模型误以为可以跑命令。
        //P5-04：终端规则与 repo map 解耦——工作区为空、repo map 关闭或不可读时仍应注入。
        if (policy.permission().canExecute()) {
            // P5-01：终端规则外置到 tool-notes/terminal-v1.md
            systemContent.append("\n\n").append(promptCatalog.text("tool-terminal"));
        }

        //用户点名的其他会话：作为背景参考，不与本轮知识库/文件内容抢优先级
        if (sessionReferenceContext != null && !sessionReferenceContext.isBlank()) {
            systemContent.append("\n\n【引用的历史会话】\n")
                    .append("以下是用户用 @会话 点名的历史会话最近几轮问答，仅作背景参考；")
                    .append("如与本轮问题无关请忽略，不要照搬其中的结论：\n")
                    .append(sessionReferenceContext.trim());
            log.info("点名会话上下文已注入 system 消息（{} 字符）", sessionReferenceContext.trim().length());
        }

        //较早对话的滚动摘要：不在此拼接，统一由 SessionMemoryAdvisor 在调用链注入 system 消息

        if (ragContext != null && !ragContext.isEmpty()) {
            systemContent.append("\n\n").append(ragContext);//吧RAG向量检索出的参考文档拼接到提示词末尾
        }

        //截图经视觉模型转写后的文字不再放进 system 末尾：
        //老会话的历史摘要与助手旧答复里常残留「只收到元数据、看不到图片」的结论，
        //而 system 里这段转写排在摘要之前，会被后出现的旧结论压制，模型仍会回答「无法识别」。
        //改为紧贴本轮提问放进 user 消息，让最新一轮以转写内容为准。

        ObjectNode systemMsg = mapper.createObjectNode();//构建system角色消息，加入消息列表。它用于给AI设定身份，约束回答规则
        systemMsg.put("role", "system");
        systemMsg.put("content", systemContent.toString());
        messages.add(systemMsg);


        if (history != null && !history.isEmpty()) {
            // B1 历史消息双重上限：条数上限 + 字符预算，防止长会话把上下文撑爆。
            // 条数：默认 10 条；命中知识库时收窄到 4 条。
            // 字符预算：从最新一条往前累积，超过 6000 字即停止；但无论预算如何，至少保留最近 2 条。
            // 原因：老会话的历史里常残留"上传文档之前"的旧问答（当时模型如实回答
            // "知识库里没有该文件"），这些旧结论会诱导模型为了前后一致而继续否认
            // 本轮已检索到的内容，表现为"老会话读不到新上传的文档，新会话却正常"。
            // 检索命中时只保留最近几轮，弱化旧结论干扰；未命中知识库时维持原状。
            //条数与字符预算都随推理档位变化：快速档更抠、深度档更宽
            int countLimit = policy.reasoning().historyCount(ragContext != null && !ragContext.isEmpty());
            final int CHAR_BUDGET = policy.reasoning().historyCharBudget();
            int start = history.size();
            int used = 0;
            while (start > 0
                    && (history.size() - start) < countLimit
                    && (used < CHAR_BUDGET || history.size() - start < 2)) {//不足 2 条时无视预算
                ChatMessage prev = history.get(start - 1);
                used += prev.getContent() == null ? 0 : prev.getContent().length();
                start--;
            }
            for (int i = start; i < history.size(); i++) {//循环读取历史记录，转换为api要求的json节点
                ChatMessage msg = history.get(i);
                ObjectNode node = mapper.createObjectNode();//用JSON库创建json节点
                node.put("role", msg.getRole().name().toLowerCase());//设置角色
                node.put("content", msg.getContent());//设置消息内容
                messages.add(node);
            }
        }

        // R1：计划快照以 user 消息注入（续跑/重放时恢复），不改 system 前缀，
        // 避免 provider 的前缀缓存因计划变化整段失效。
        if (planSnapshot != null && !planSnapshot.isEmpty()) {
            messages.add(buildPlanSnapshotMessage(planSnapshot));
        }

        //创建消息头，创建一个json节点，指定角色为user
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");

        StringBuilder finalPrompt = new StringBuilder();
        if (fileContext != null && !fileContext.isEmpty()) {
            finalPrompt.append(fileContext).append("\n");//拼接知识库或者附件的相关片段
        }

        //截图转写紧贴本轮提问给出：用户消息是模型注意力最高、也是唯一不会被历史摘要覆盖的位置
        if (imageContext != null && !imageContext.isBlank()) {
            finalPrompt.append("【本轮截图转写（视觉模型已识别，请直接据此回答）】\n")
                    .append("以下内容由视觉模型从本轮上传的截图转写而来，可能有 OCR 误差；")
                    .append("涉及报错、路径、代码时以此为准。历史对话中「看不到图片」之类说法均已过时：\n")
                    .append(imageContext.trim())
                    .append("\n\n");
            log.info("截图转写已注入本轮用户消息（{} 字符）", imageContext.trim().length());
        }

        finalPrompt.append(userPrompt != null ? userPrompt : "");
        userMsg.put("content", finalPrompt.toString());//把用户的问题拼接在参考资料后
        messages.add(userMsg);

        return messages;
    }

    /** R1：把持久化的计划快照格式化为一条 user 消息，供续跑时对齐步骤状态 */
    private ObjectNode buildPlanSnapshotMessage(List<PlanStep> plan) {
        StringBuilder sb = new StringBuilder("【本轮任务计划（来自 Run 的持久化快照）】\n");
        for (int i = 0; i < plan.size(); i++) {
            PlanStep step = plan.get(i);
            sb.append(i + 1).append(". [").append(step.status()).append("] ")
                    .append(step.title()).append('\n');
        }
        sb.append("继续执行时保持与这份计划一致；完成某步后调用 update_plan 更新状态。");

        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", sb.toString());
        return msg;
    }

    @jakarta.annotation.PreDestroy
    void shutdownReadOnlyToolPool() {
        // R3：线程池随执行链路迁入 ToolExecutor
        com.capsule.agentkey.agent.tool.ToolExecutor executor = toolExecutor;
        if (executor != null) {
            executor.shutdown();
        }
    }

}
