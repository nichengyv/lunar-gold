package com.capsule.agentkey.service;

import com.capsule.agentkey.client.AgentClient;
import com.capsule.agentkey.client.AgentStreamCallback;
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
import java.util.concurrent.atomic.AtomicReference;



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
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * P5-08：单轮内连续只读工具的并发上限。
     * 取 3 的依据：Runtime 评测里单轮只读批的实际规模为 2～3；fixture 基准（3 个 120ms 模拟只读）
     * 在 3 并发时 p95 已接近理论下界，继续加线程只会增加调度与上下文切换开销。
     * 写/命令批不进入该池，仍保持串行 + 审批。
     */
    @org.springframework.beans.factory.annotation.Value("${agent.parallel.readonly-max-concurrency:3}")
    private int readonlyMaxConcurrency = 3;

    /** 只读批执行池：daemon 线程，按需初始化；关闭时中断未完成任务 */
    private volatile java.util.concurrent.ExecutorService readOnlyToolPool;

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
        default void onPlan(java.util.List<WorkspaceContext.PlanStep> steps) {}
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
        //工作区工具需要 userId/projectId 上下文，用 ThreadLocal 透传（dispatch 签名不变）；
        //cancelRequested 让 run_command 这类长耗时工具能在用户停止生成时杀掉子进程
        WorkspaceContext.set(userId, projectId, cancelRequested);
        if (runId != null) WorkspaceContext.get().setRunId(runId);
        WorkspaceContext.get().setSessionId(sessionId);
        WorkspaceContext.get().setTurnId(turnId);
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
                    projectInstruction, repoMap, workspacePath, policy);
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

            int round = 0;
            // P5-10-3：本轮开始时间，用于时间预算检查和前端耗时展示的对账
            long runStartedAt = System.currentTimeMillis();
            // P4-07：本 run 的 token 累计；overBudgetTokens > 0 表示已超预算，需立即中断
            java.util.concurrent.atomic.AtomicLong promptTokensTotal = new java.util.concurrent.atomic.AtomicLong(0);
            java.util.concurrent.atomic.AtomicLong completionTokensTotal = new java.util.concurrent.atomic.AtomicLong(0);
            java.util.concurrent.atomic.AtomicLong overBudgetTokens = new java.util.concurrent.atomic.AtomicLong(0);
            //开始 ReAct 循环（轮次上限由推理档位决定：快速 10 / 标准 30 / 深度 60）
            boolean budgetHintSent = false;
            boolean workspaceChanged = false;
            boolean unresolvedWriteFailure = false;
            boolean unresolvedApprovalRejection = false;
            String lastToolSignature = null;
            int repeatedToolCalls = 0;
            String lastFailureFingerprint = null;
            int repeatedFailures = 0;
            Set<String> seenToolSignatures = new HashSet<>();
            int stagnantRounds = 0;
            //P5-05：连续相同诊断计数，达到阈值后与重复调用共用无进展停止
            String lastDiagnosticsFingerprint = null;
            int repeatedDiagnostics = 0;
            //P5-06：验证次数、文件变更轮次与最近一次验证覆盖的轮次，避免重复执行验证
            int verifyAttempts = 0;
            long changeEpoch = 0;
            long verifiedEpoch = -1;
            String lastVerifyFingerprint = null;
            int repeatedVerifyFailures = 0;
            boolean verifyUnavailableNotified = false;
            while (round < policy.reasoning().maxToolRounds()) {
                //P6-06：只有用户显式停止才中止；客户端断流不会进入这里，Run 继续执行
                if (cancelRequested != null && cancelRequested.get()) {
                    log.info("用户已停止生成，中止 ReAct 推理 [round={}]", round + 1);
                    // 批次 B：停止前也标记剩余步骤 aborted
                    finalizePlan(callback);
                    callback.onCompletion(new Completion(CompletionStatus.INCOMPLETE, "CANCELLED",
                            "本轮执行已取消，任务尚未完成。", workspaceChanged));
                    callback.onComplete();
                    return;
                }
                // P5-10-3：时间预算耗尽时在下一轮开始前停止，给出可操作的原因而不是静默截断
                if (isTimeBudgetExceeded(System.currentTimeMillis() - runStartedAt, maxSecondsPerRun)) {
                    stopWithCompletion(callback, workspaceChanged, "TIME_BUDGET_EXCEEDED",
                            "本轮已超过 " + maxSecondsPerRun + " 秒时间预算，已停止。"
                                    + "可以缩小任务范围或调高 agent.budget.max-seconds-per-run。");
                    return;
                }
                log.info("ReAct 第 {} 轮推理", round + 1);

                // 决策 + 作答合并成一次「流式」调用：
                // 模型只输出文本 → 边生成边推给前端（真正的打字机效果）；
                // 模型要调工具 → 先把分片参数累积成完整 tool_calls，再执行工具进入下一轮。
                StringBuilder streamedText = new StringBuilder();
                List<ObjectNode> streamedToolCalls = new ArrayList<>();
                AtomicReference<Throwable> streamError = new AtomicReference<>();

                // 预算告警：剩余 3 轮时提醒模型收口，避免把轮次浪费在无谓探索上
                int remainingRounds = policy.reasoning().maxToolRounds() - round;
                if (!budgetHintSent && remainingRounds <= 3) {
                    budgetHintSent = true;
                    ObjectNode hint = mapper.createObjectNode();
                    hint.put("role", "user");
                    hint.put("content", "注意：本轮只剩 " + remainingRounds + " 次工具调用机会。"
                            + "请优先完成核心步骤，跳过非必要的探索；信息已足够时直接写文件并给出结论。");
                    messages.add(hint);
                }

                springAiClient.streamChat(apiKey, policy.model(), messages, tools, advisorSummary,
                        new AgentStreamCallback() {
                    @Override
                    public void onChunk(String chunk) {
                        streamedText.append(chunk);
                        callback.onChunk(chunk);
                    }

                    @Override
                    public void onToolCalls(List<ObjectNode> toolCalls) {
                        streamedToolCalls.addAll(toolCalls);
                    }

                    @Override
                    public void onComplete() {
                        // 一轮结束由外层决定：继续工具循环还是收尾
                    }

                    @Override
                    public void onUsage(long promptTokens, long completionTokens) {
                        // P4-07：先累加并透传给上层落库，再判断是否超预算
                        long total = promptTokensTotal.addAndGet(promptTokens)
                                + completionTokensTotal.addAndGet(completionTokens);
                        callback.onUsage(promptTokens, completionTokens);
                        if (maxTokensPerRun > 0 && total > maxTokensPerRun) {
                            overBudgetTokens.set(total);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        streamError.set(t);
                    }
                });

                if (streamError.get() != null) {
                    throw new RuntimeException(streamError.get());
                }
                if (overBudgetTokens.get() > 0) {
                    throw new com.capsule.agentkey.exception.TokenBudgetExceededException(
                            overBudgetTokens.get(), maxTokensPerRun);
                }

                if (streamedToolCalls.isEmpty()) {//模型没要求调工具 → 只表示候选完成，不能代替验证证据
                    //P5-06：候选完成时若最近一次验证没有覆盖最新文件变更，先补一次验证；
                    //失败且仍有预算时把输出回灌，继续修复，而不是直接收尾。
                    if (workspaceChanged && verifiedEpoch < changeEpoch) {
                        com.capsule.agentkey.verify.VerifyResult verify =
                                runVerification(workspacePath, cancelRequested, ++verifyAttempts, callback);
                        verifiedEpoch = changeEpoch;
                        if (verify.failed()) {
                            String fingerprint = verify.failureFingerprint();
                            repeatedVerifyFailures = fingerprint.equals(lastVerifyFingerprint)
                                    ? repeatedVerifyFailures + 1 : 1;
                            lastVerifyFingerprint = fingerprint;
                            if (maxRepeatedToolCalls > 0
                                    && repeatedVerifyFailures >= maxRepeatedToolCalls) {
                                String message = "连续 " + repeatedVerifyFailures
                                        + " 次得到相同验证失败（" + verify.message()
                                        + "），已停止以避免无进展循环。";
                                stopForNoProgress(callback, workspaceChanged, new NoProgress(
                                        "REPEATED_VERIFY_FAILURE", "verify",
                                        repeatedVerifyFailures, stagnantRounds, message));
                                return;
                            }
                            if (verify.attempt() < verify.maxAttempts()) {
                                addUserMessage(messages, verify.formatForPrompt());
                                round++;
                                continue;
                            }
                        } else {
                            repeatedVerifyFailures = 0;
                            lastVerifyFingerprint = null;
                        }
                    }
                    Completion completion = candidateCompletion(
                            workspaceChanged, unresolvedWriteFailure, unresolvedApprovalRejection);
                    if (completion.status() == CompletionStatus.INCOMPLETE) {
                        finalizePlan(callback);
                    }
                    callback.onCompletion(completion);
                    callback.onComplete();//流式回调通知：本轮全部输出完毕，前端关闭加载，思考动画
                    return;
                }

                //把模型这一轮「调用工具」的意图写回上下文，后续 tool 消息才能对应上
                ObjectNode assistantMsg = mapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                if (streamedText.length() > 0) {
                    assistantMsg.put("content", streamedText.toString());
                } else {
                    assistantMsg.putNull("content");
                }
                assistantMsg.set("tool_calls", mapper.valueToTree(streamedToolCalls));
                messages.add(assistantMsg);

                boolean roundProgress = false;
                boolean roundFileChanged = false;
                //P5-08：当前连续只读批的执行结果（下标 → 结果）。写/命令调用不入批，遇到即中断批。
                Map<Integer, ToolResult> readOnlyBatch = Map.of();
                //诊断等 user 消息绝不能插在 assistant(tool_calls) 与本组 tool 响应之间：
                //并行调用多个写工具时，第一个写操作产生的诊断若立即入列，会把剩余 tool 响应
                //隔到 user 之后，provider 直接以 400「insufficient tool messages」拒绝整段历史。
                //先暂存，本组 tool 响应全部写完后统一追加。
                List<ObjectNode> deferredUserMessages = new ArrayList<>();
                for (int callIndex = 0; callIndex < streamedToolCalls.size(); callIndex++) {//循环遍历，支持一次调用多个工具
                    ObjectNode toolCall = streamedToolCalls.get(callIndex);
                    String toolName = toolCall.path("function").path("name").asText();
                    String arguments = toolCall.path("function").path("arguments").asText();//工具所需的入参
                    String toolCallId = toolCall.path("id").asText();//工具调用的唯一编号

                    //P5-08：到达新的批首时，先把连续只读调用并发执行；结果仍按原下标顺序回放
                    if (!readOnlyBatch.containsKey(callIndex)) {
                        readOnlyBatch = executeReadOnlyBatch(streamedToolCalls, callIndex,
                                sessionId, policy, projectId, cancelRequested);
                    }

                    callback.onToolCall(toolName, arguments);
                    log.info("调用工具: {} 参数: {}", toolName, arguments);

                    String toolSignature = normalizeToolCall(toolName, arguments);
                    boolean newToolSignature = seenToolSignatures.add(toolSignature);
                    repeatedToolCalls = toolSignature.equals(lastToolSignature) ? repeatedToolCalls + 1 : 1;
                    lastToolSignature = toolSignature;
                    if (maxRepeatedToolCalls > 0 && repeatedToolCalls >= maxRepeatedToolCalls) {
                        String message = "检测到连续 " + repeatedToolCalls + " 次相同工具调用（"
                                + toolName + "），已停止以避免无进展循环。";
                        callback.onToolResult(toolName, ToolResult.fail(message));
                        stopForNoProgress(callback, workspaceChanged, new NoProgress(
                                "REPEATED_TOOL_CALL", toolName, repeatedToolCalls, stagnantRounds, message));
                        return;
                    }

                    ToolResult result;//根据工具名称找到对应实现类
                    String risk = null; // 初始化为 null——ask_user_question 不走审批，risk 保持 null

                    // P7-06：ask_user_question 拦截——绕开 ToolRegistry.dispatch()，走挂起/恢复链路
                    // 原因：ask_user_question 需要挂起 ReAct 线程等用户回答，BaseTool.execute() 是同步返回的
                    if ("ask_user_question".equals(toolName)
                            && askUserQuestionTool != null
                            && WorkspaceContext.get() != null
                            && WorkspaceContext.get().runId() != null) {
                        result = executeAskUserQuestion(arguments, callback, cancelRequested);
                    } else {
                        risk = ToolApprovalRegistry.riskOf(toolName, arguments);
                    if (readOnlyBatch.containsKey(callIndex)) {
                        //P5-08：连续只读批已并发执行完毕，按原顺序取结果；不进入审批与串行分发
                        result = readOnlyBatch.get(callIndex);
                    } else {
                        // 每次工具调用前都读一次最新权限，用户在运行中改档位能立即生效
                        AgentPolicy.Permission effectivePermission =
                                permissionTracker.get(sessionId, policy.permission());
                        // 按权限档位决定是否需要人工确认：手动=写/执行都问，自动=仅破坏性命令问，完全=不问
                        if (risk != null
                                && (effectivePermission.requiresApproval(risk, commandOf(arguments))
                                    // MCP 写操作（含内置写工具的 MCP fallback）不能在自动/完全权限下绕过审批
                                    || toolName.startsWith("mcp_")
                                    || toolRegistry.hasFallback(toolName))
                                && !toolApprovalRegistry.isSessionAllowed(sessionId, toolName)) {
                            //第六十八批 A2：危险工具先挂起，等用户批准；拒绝时回一条 tool 错误让模型换方案
                            ToolApprovalRegistry.Pending approval =
                                    toolApprovalRegistry.create(userId, sessionId, toolName, arguments, risk);
                            var preview = buildApprovalPreview(toolName, arguments, userId, projectId);
                            callback.onApprovalRequired(approval.approvalId(), toolName, preview, risk, approval.expiresAt());
                            ToolApprovalRegistry.Resolution decision = toolApprovalRegistry.await(approval, cancelRequested);
                            // 批次 B：无论结果如何，通知前端把卡片切到"已处理"态
                            callback.onApprovalResolved(approval.approvalId(), decision.decision().name(),
                                    decision.source().name(), decision.batchId());
                            if (decision.decision() == ToolApprovalRegistry.Decision.REJECT) {
                                unresolvedApprovalRejection = true;
                                String label = "EXECUTE".equals(risk) ? "执行命令"
                                        : "DESTRUCTIVE".equals(risk) ? "递归删除目录" : "写入文件";
                                result = ToolResult.fail("用户拒绝了" + label + "操作（" + toolName + "）。"
                                        + "请改用不具破坏性的方案；如果确实需要该操作，请向用户说明理由后等待其重新批准。");
                                log.info("用户拒绝工具调用: {}", toolName);
                            } else {
                                result = dispatchWithScopeCheck(toolName, arguments, effectivePermission, projectId);
                            }
                        } else {
                            result = dispatchWithScopeCheck(toolName, arguments, effectivePermission, projectId);
                        }
                    }
                    } // end else（非 ask_user_question 的常规审批 + dispatch 路径）
                    callback.onToolResult(toolName, result);
                    log.info("工具结果: success={}, cost={}ms", result.isSuccess(), result.getCostMs());

                    //协议要求：assistant(tool_calls) 之后必须紧跟每个 id 的 tool 响应。
                    //诊断/验证等 user 消息只能排在 tool 响应之后，否则模型侧会以 400 拒绝整段历史。
                    ObjectNode toolMsg = mapper.createObjectNode();//新建一个空白的json对象
                    //role标签规定该json对象的数据类型，方便大模型处理
                    //user：人类输入
                    //assistant：大模型自己说的
                    //tool：外部程序返回
                    toolMsg.put("role", "tool");

                    toolMsg.put("tool_call_id", toolCallId);
                    //工具执行成功：content填入返回数据
                    //执行失败：填入错误提示文本
                    //B2：无论成败都过一遍截断，防止超长内容进入上下文
                    String toolContent = result.isSuccess() ? result.getContent() : "错误: " + result.getError();
                    toolMsg.put("content", truncateForContext(toolContent, policy.reasoning().toolContentLimit()));

                    messages.add(toolMsg);//把组装完毕的tool消息存入全局消息数组


                    if (newToolSignature && result.isSuccess()) roundProgress = true;
                    if (result.isSuccess() && risk != null) {
                        unresolvedWriteFailure = false;
                        unresolvedApprovalRejection = false;
                        roundProgress = true;
                    } else if (!result.isSuccess() && ("WRITE".equals(risk) || "DESTRUCTIVE".equals(risk))) {
                        unresolvedWriteFailure = true;
                    }

                    //工具改动了工作区文件 → 逐个推给前端（在 tool_result 之后，保证顺序稳定）
                    java.util.List<com.capsule.agentkey.tool.FileChange> fileChanges =
                            WorkspaceContext.drainFileChanges();
                    if (!fileChanges.isEmpty()) {
                        workspaceChanged = true;
                        roundProgress = true;
                        roundFileChanged = true;
                    }
                    for (com.capsule.agentkey.tool.FileChange change : fileChanges) {
                        callback.onFileChange(change);
                    }

                    //P5-05：变更文件诊断 → 注入下一轮上下文、推送前端，并参与无进展判定
                    if (!fileChanges.isEmpty()) {
                        WorkspaceContext activeCtx = WorkspaceContext.get();
                        com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics =
                                diagnosticsService.collect(
                                        workspacePath == null || workspacePath.isBlank()
                                                ? null : java.nio.file.Paths.get(workspacePath),
                                        fileChanges);
                        String fingerprint = com.capsule.agentkey.diagnostics.DiagnosticsService
                                .fingerprint(diagnostics.diagnostics());
                        if (activeCtx != null) {
                            activeCtx.setDiagnostics(diagnostics);
                            activeCtx.setDiagnosticsFingerprint(fingerprint);
                            activeCtx.setDiagnosticsFileVersion(
                                    diagnosticsFileVersion(workspacePath, fileChanges));
                        }
                        if (diagnostics.available() || diagnostics.analyzedFiles() > 0) {
                            callback.onDiagnostics(diagnostics);
                        }
                        if (diagnostics.analyzedFiles() > 0) {
                            ObjectNode diagMsg = mapper.createObjectNode();
                            diagMsg.put("role", "user");
                            diagMsg.put("content", diagnostics.formatForPrompt());
                            deferredUserMessages.add(diagMsg);
                            if (diagnostics.hasDiagnostics()) {
                                repeatedDiagnostics = fingerprint.equals(lastDiagnosticsFingerprint)
                                        ? repeatedDiagnostics + 1 : 1;
                                lastDiagnosticsFingerprint = fingerprint;
                                if (maxRepeatedToolCalls > 0 && repeatedDiagnostics >= maxRepeatedToolCalls) {
                                    com.capsule.agentkey.diagnostics.Diagnostic first =
                                            diagnostics.diagnostics().get(0);
                                    String message = "连续 " + repeatedDiagnostics + " 次得到相同诊断（"
                                            + first.file() + ":" + first.line()
                                            + "），已停止以避免在同一个编译错误上循环。";
                                    stopForNoProgress(callback, workspaceChanged, new NoProgress(
                                            "REPEATED_DIAGNOSTICS", "diagnostics",
                                            repeatedDiagnostics, stagnantRounds, message));
                                    return;
                                }
                            } else {
                                repeatedDiagnostics = 0;
                                lastDiagnosticsFingerprint = null;
                            }
                        }
                    }

                    //第六十八批 A1：update_plan 更新了计划 → 推送最新快照
                    WorkspaceContext ctx = WorkspaceContext.get();
                    if (ctx != null && ctx.drainPlanChanged()) {
                        roundProgress = true;
                        callback.onPlan(ctx.plan());
                    }

                    if (!result.isSuccess()) {
                        String failureFingerprint = normalizeFailure(toolName, result);
                        repeatedFailures = failureFingerprint.equals(lastFailureFingerprint) ? repeatedFailures + 1 : 1;
                        lastFailureFingerprint = failureFingerprint;
                        if (maxRepeatedToolCalls > 0 && repeatedFailures >= maxRepeatedToolCalls) {
                            String message = "检测到连续 " + repeatedFailures + " 次相同失败（"
                                    + toolName + "），已停止以避免无效重试。";
                            stopForNoProgress(callback, workspaceChanged, new NoProgress(
                                    "REPEATED_FAILURE", toolName, repeatedFailures, stagnantRounds, message));
                            return;
                        }
                    } else {
                        repeatedFailures = 0;
                        lastFailureFingerprint = null;
                    }

                    //P5-08：停止生成后不再执行本轮剩余调用（含已入池但未开始的只读任务），
                    //按取消语义立即收尾，而不是等下一轮开头才发现。
                    if (cancelRequested != null && cancelRequested.get()) {
                        log.info("用户已停止生成，取消本轮剩余工具调用");
                        finalizePlan(callback);
                        callback.onCompletion(new Completion(CompletionStatus.INCOMPLETE, "CANCELLED",
                                "本轮执行已取消，任务尚未完成。", workspaceChanged));
                        callback.onComplete();
                        return;
                    }
                }

                //本组 tool 响应已全部就位，诊断等 user 消息现在才能入列（排在 tool 响应之后）
                messages.addAll(deferredUserMessages);

                //P5-06：一轮写操作结束 → 执行一次项目验证。通过则告诉模型不要重复验证；
                //失败则在预算内回灌输出，让模型修复；超过重试或重复指纹阈值立即停止。
                if (roundFileChanged) {
                    changeEpoch++;
                    com.capsule.agentkey.verify.VerifyResult verify =
                            runVerification(workspacePath, cancelRequested, ++verifyAttempts, callback);
                    verifiedEpoch = changeEpoch;
                    if (verify.failed()) {
                        String fingerprint = verify.failureFingerprint();
                        repeatedVerifyFailures = fingerprint.equals(lastVerifyFingerprint)
                                ? repeatedVerifyFailures + 1 : 1;
                        lastVerifyFingerprint = fingerprint;
                        if (maxRepeatedToolCalls > 0
                                && repeatedVerifyFailures >= maxRepeatedToolCalls) {
                            String message = "连续 " + repeatedVerifyFailures
                                    + " 次得到相同验证失败（" + verify.message()
                                    + "），已停止以避免无进展循环。";
                            stopForNoProgress(callback, workspaceChanged, new NoProgress(
                                    "REPEATED_VERIFY_FAILURE", "verify",
                                    repeatedVerifyFailures, stagnantRounds, message));
                            return;
                        }
                        if (verify.attempt() >= verify.maxAttempts()) {
                            stopWithCompletion(callback, workspaceChanged, "VERIFY_RETRY_EXHAUSTED",
                                    "验证连续失败 " + verify.attempt() + " 次，已达到重试上限，任务尚未完成。");
                            return;
                        }
                        addUserMessage(messages, verify.formatForPrompt());
                    } else {
                        repeatedVerifyFailures = 0;
                        lastVerifyFingerprint = null;
                        if (verify.passed()) {
                            addUserMessage(messages, verify.formatForPrompt());
                        } else if (!verifyUnavailableNotified) {
                            //未配置/环境不可用只需提示一次，避免每轮重复噪声
                            verifyUnavailableNotified = true;
                            addUserMessage(messages, verify.formatForPrompt());
                        }
                    }
                }

                stagnantRounds = roundProgress ? 0 : stagnantRounds + 1;
                if (maxStagnantRounds > 0 && stagnantRounds >= maxStagnantRounds) {
                    String message = "连续 " + stagnantRounds + " 轮没有新的有效结果、计划或文件变化，已安全停止。";
                    stopForNoProgress(callback, workspaceChanged, new NoProgress(
                            "STAGNANT_ROUNDS", null, 0, stagnantRounds, message));
                    return;
                }
                round++;
            }

            //P6-06：停止后不再做收尾生成，但计划仍然要标记 aborted
            if (cancelRequested != null && cancelRequested.get()) {
                log.info("用户已停止生成，跳过收尾生成");
                finalizePlan(callback);
                callback.onCompletion(new Completion(CompletionStatus.INCOMPLETE, "CANCELLED",
                        "本轮执行已取消，任务尚未完成。", workspaceChanged));
                callback.onComplete();
                return;
            }

            //轮次耗尽：不再允许工具调用，让模型基于已收集的工具结果做最终生成。
            //原实现直接 onComplete，用户收不到任何实质回答——这是核心体验 bug。
            //第五十三批修复：追加一条明确的 user 指令，防止模型输出 tool_calls 意图或内部推理标记（如 DSML）。
            callback.onChunk("\n\n（已达到最大工具调用轮次，以下基于已获取的信息作答）\n\n");
            ObjectNode finalInstruction = mapper.createObjectNode();
            finalInstruction.put("role", "user");
            finalInstruction.put("content",
                    "请基于以上已经收集到的全部工具结果，直接输出你的最终文本回答。"
                    + "不要再调用任何工具（本轮工具调用上限已耗尽），也不要输出任何工具调用意图、"
                    + "内部推理标记（如 <DSML>、<tool_call> 等）、或思考过程，"
                    + "只输出面向用户的最终回答文本。");
            messages.add(finalInstruction);
            //第三个参数传空工具列表：收尾这一次调用模型不能再发起 tool_calls，只能输出文本答案
            AtomicReference<Throwable> finalError = new AtomicReference<>();
            springAiClient.streamChat(apiKey, policy.model(), messages, List.of(), advisorSummary,
                    new AgentStreamCallback() {
                @Override
                public void onChunk(String chunk) {
                    callback.onChunk(chunk);
                }

                @Override
                public void onComplete() {
                    //收尾由下面统一触发
                }

                @Override
                public void onUsage(long promptTokens, long completionTokens) {
                    long total = promptTokensTotal.addAndGet(promptTokens)
                            + completionTokensTotal.addAndGet(completionTokens);
                    callback.onUsage(promptTokens, completionTokens);
                    if (maxTokensPerRun > 0 && total > maxTokensPerRun) {
                        overBudgetTokens.set(total);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    finalError.set(t);
                }
            });
            if (finalError.get() != null) {
                throw new RuntimeException(finalError.get());
            }
            if (overBudgetTokens.get() > 0) {
                throw new com.capsule.agentkey.exception.TokenBudgetExceededException(
                        overBudgetTokens.get(), maxTokensPerRun);
            }
            // 批次 B 修复：finalizePlan 必须在 onComplete 之前推 plan 事件，
            // 因为 onComplete 发 SSE done 后前端就停止处理后续事件了
            finalizePlan(callback);
            callback.onCompletion(new Completion(CompletionStatus.INCOMPLETE, "MAX_TOOL_ROUNDS",
                    "已达到最大工具调用轮次，任务尚未确认完成。", workspaceChanged));
            callback.onComplete();

        } catch (Exception e) {
            log.error("ReAct 执行异常", e);
            // 异常退出同样要先收口计划，再通知前端出错
            finalizePlan(callback);
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

    private Completion candidateCompletion(boolean workspaceChanged, boolean unresolvedWriteFailure,
                                           boolean unresolvedApprovalRejection) {
        if (hasUnfinishedPlan()) {
            return new Completion(CompletionStatus.INCOMPLETE, "PLAN_INCOMPLETE",
                    "模型已停止，但计划仍有未完成步骤。", workspaceChanged);
        }
        if (unresolvedApprovalRejection) {
            return new Completion(CompletionStatus.INCOMPLETE, "APPROVAL_REJECTED",
                    "所需操作未获批准，任务尚未完成。", workspaceChanged);
        }
        if (unresolvedWriteFailure) {
            return new Completion(CompletionStatus.INCOMPLETE, "WRITE_TOOL_FAILED",
                    "最后一次文件修改未成功，任务尚未完成。", workspaceChanged);
        }
        if (workspaceChanged) {
            WorkspaceContext ctx = WorkspaceContext.get();
            com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics =
                    ctx == null ? null : ctx.diagnostics();
            if (diagnostics != null && diagnostics.available() && diagnostics.hasErrors()) {
                long errors = diagnostics.diagnostics().stream()
                        .filter(d -> d.severity()
                                == com.capsule.agentkey.diagnostics.Diagnostic.Severity.ERROR)
                        .count();
                return new Completion(CompletionStatus.INCOMPLETE, "DIAGNOSTICS_FAILED",
                        "变更文件仍有 " + errors + " 个编译错误，任务尚未完成。", true);
            }

            //P5-06：项目验证优先于编译诊断作为完成证据；未配置/不可用不得包装成成功
            com.capsule.agentkey.verify.VerifyResult verify = ctx == null ? null : ctx.verify();
            if (verify != null) {
                switch (verify.status()) {
                    case FAILED -> {
                        return new Completion(CompletionStatus.INCOMPLETE, "VERIFY_FAILED",
                                "验证失败：" + verify.message() + "（第 " + verify.attempt() + '/'
                                        + verify.maxAttempts() + " 次），任务尚未完成。", true);
                    }
                    case PASSED -> {
                        return new Completion(CompletionStatus.COMPLETED, "VERIFY_PASSED",
                                "验证命令全部通过。", true);
                    }
                    case NOT_CONFIGURED -> {
                        return new Completion(CompletionStatus.UNVERIFIED, "VERIFY_NOT_CONFIGURED",
                                "文件已修改，但项目未配置验证命令；可在 "
                                        + verify.configPath()
                                        + " 配置验证，或人工审查并接受未验证变更。", true);
                    }
                    case UNAVAILABLE -> {
                        return new Completion(CompletionStatus.UNVERIFIED, "VERIFY_UNAVAILABLE",
                                "文件已修改，但验证无法执行：" + verify.message()
                                        + "；可修复环境后重试，或人工审查并接受未验证变更。", true);
                    }
                }
            }

            //没有验证结果时的兜底：保留 P5-05 的干净编译诊断作为证据
            if (diagnostics != null && diagnostics.available() && diagnostics.analyzedFiles() > 0) {
                return new Completion(CompletionStatus.COMPLETED, "DIAGNOSTICS_PASSED",
                        "变更文件已通过编译诊断。", true);
            }
            return new Completion(CompletionStatus.UNVERIFIED, "VERIFICATION_MISSING",
                    "文件已修改，但尚未运行诊断或验证。", true);
        }
        return new Completion(CompletionStatus.COMPLETED, "NO_VERIFICATION_REQUIRED",
                "本轮执行已结束，无需文件验证。", false);
    }

    private boolean hasUnfinishedPlan() {
        WorkspaceContext ctx = WorkspaceContext.get();
        return ctx != null && ctx.plan().stream()
                .anyMatch(s -> "pending".equals(s.status()) || "in_progress".equals(s.status()));
    }

    /** P5-05：把本批变更文件的版本拼成可追溯字符串，随诊断结果一起落事件 */
    private String diagnosticsFileVersion(String workspacePath,
                                          java.util.List<com.capsule.agentkey.tool.FileChange> changes) {
        if (workspacePath == null || workspacePath.isBlank() || changes == null || changes.isEmpty()) {
            return "";
        }
        try {
            java.nio.file.Path root = java.nio.file.Paths.get(workspacePath);
            return changes.stream()
                    .filter(c -> c != null && c.path() != null)
                    .map(c -> c.path() + "@" + diagnosticsService.fileVersion(root.resolve(c.path())))
                    .collect(java.util.stream.Collectors.joining(","));
        } catch (Exception e) {
            return "";
        }
    }

    /** P5-06：执行一次项目验证；任何异常都降级为 UNAVAILABLE，并始终推送 onVerify */
    private com.capsule.agentkey.verify.VerifyResult runVerification(
            String workspacePath, AtomicBoolean cancelRequested, int attempt, StreamCallback callback) {
        com.capsule.agentkey.verify.VerifyResult result;
        try {
            java.nio.file.Path root = workspacePath == null || workspacePath.isBlank()
                    ? null : java.nio.file.Paths.get(workspacePath);
            result = verifyService.verify(root, attempt, cancelRequested);
        } catch (Exception e) {
            log.warn("验证执行异常，降级为不可用: {}", e.getMessage(), e);
            result = new com.capsule.agentkey.verify.VerifyResult(
                    com.capsule.agentkey.verify.VerifyResult.Status.UNAVAILABLE,
                    ".lunargold/verify.json", false, attempt, Math.max(1, attempt),
                    java.util.List.of(), "", "验证执行异常：" + e.getMessage());
        }
        WorkspaceContext ctx = WorkspaceContext.get();
        if (ctx != null) {
            ctx.setVerify(result);
        }
        callback.onVerify(result);
        return result;
    }

    /** P5-06：把验证结果作为 user 消息注入下一轮模型上下文 */
    private void addUserMessage(java.util.List<ObjectNode> messages, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", content);
        messages.add(message);
    }

    /** P5-10-3：时间预算是否已耗尽；maxSeconds <= 0 表示不限制。 */
    static boolean isTimeBudgetExceeded(long elapsedMs, long maxSeconds) {
        return maxSeconds > 0 && elapsedMs >= maxSeconds * 1000L;
    }

    // -----------------------------------------------------------------
    // P7-06：ask_user_question 执行（从工具循环拦截点调用）
    // -----------------------------------------------------------------

    /**
     * 解析 ask_user_question 的 arguments JSON，挂起 ReAct 线程等用户回答，返回 ToolResult。
     * 绕开 ToolRegistry.dispatch()——因为 ask_user_question 需要挂起等待，
     * 而 BaseTool.execute() 是同步返回的。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>解析 arguments → questionId / question / header / options / multi_select</li>
     *   <li>调 AskUserQuestionTool.ask() 挂起当前线程，推 SSE event:user_question</li>
     *   <li>等待用户通过 {@code POST /api/agent/runs/{runId}/answer} 回答</li>
     *   <li>返回 DHS 格式答案 JSON 包装的 ToolResult.success()</li>
     * </ol>
     */
    private ToolResult executeAskUserQuestion(String arguments, StreamCallback callback,
                                               AtomicBoolean cancelRequested) {
        Long runId = WorkspaceContext.get().runId();
        if (runId == null || askUserQuestionTool == null) {
            return ToolResult.fail("ask_user_question 需要有效的 runId 和 AskUserQuestionTool bean");
        }

        // 1. 解析 arguments——schema 规定 questions 数组长度为 1
        String questionId, question, header = null;
        List<Map<String, Object>> options = null;
        boolean multiSelect = false;
        try {
            var root = mapper.readTree(arguments);
            var questions = root.path("questions");
            if (!questions.isArray() || questions.size() < 1) {
                return ToolResult.fail("ask_user_question 需要 questions 数组（长度 1）");
            }
            var q = questions.get(0);
            questionId = q.path("id").asText(null);
            question = q.path("question").asText(null);
            if (questionId == null || questionId.isBlank()) {
                return ToolResult.fail("ask_user_question 缺少 id");
            }
            if (question == null || question.isBlank()) {
                return ToolResult.fail("ask_user_question 缺少 question");
            }
            if (q.hasNonNull("header")) header = q.path("header").asText();
            if (q.hasNonNull("options") && q.path("options").isArray()) {
                options = new java.util.ArrayList<>();
                for (var opt : q.path("options")) {
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    if (opt.has("label")) map.put("label", opt.path("label").asText());
                    if (opt.has("description")) map.put("description", opt.path("description").asText());
                    options.add(map);
                }
            }
            multiSelect = q.path("multi_select").asBoolean(false);
        } catch (Exception e) {
            log.warn("解析 ask_user_question 参数失败: {}", e.getMessage());
            return ToolResult.fail("ask_user_question 参数解析失败: " + e.getMessage());
        }

        // 2. 挂起等待 + 返回
        String answerJson = askUserQuestionTool.ask(
                runId, questionId, question, header, options, multiSelect,
                callback, cancelRequested::get);

        // 3. 包装成 ToolResult——DHS 格式：{ "answers": [{ "id": "...", "selected": [...], "custom": "..." }] }
        try {
            var parsed = mapper.readTree(answerJson);
            if (parsed.has("error")) {
                // 超时/中断/取消时 Run 还停在 WAITING_USER_INPUT，拉回 RUNNING 与实际执行保持一致；
                // 用户已作答或 Run 已取消时该方法不改状态（内部按当前状态判断）
                abandonQuestion(runId, questionId, parsed.path("error").asText("UNKNOWN"));
                return ToolResult.fail("ask_user_question 失败: " + parsed.path("message").asText(answerJson));
            }
            return ToolResult.ok(answerJson);
        } catch (Exception e) {
            // answerJson 本身就是合法 JSON（AskUserQuestionTool 保证），解析失败说明格式有 bug
            log.error("ask_user_question 返回值解析失败: {}", e.getMessage());
            return ToolResult.ok(answerJson); // 降级——原样返回让模型自己理解
        }
    }

    /** 询问没有被回答（超时/中断/取消）：把 Run 从 WAITING_USER_INPUT 拉回 RUNNING，失败不影响主循环 */
    private void abandonQuestion(Long runId, String questionId, String reason) {
        if (agentRunService == null) return;
        try {
            agentRunService.abandonUserQuestion(runId, questionId, reason);
        } catch (Exception e) {
            log.warn("恢复等待用户输入的 Run 状态失败: runId={}, reason={}, error={}",
                    runId, reason, e.getMessage());
        }
    }

    /** P5-06：预算耗尽时保留计划实况并如实给出 INCOMPLETE */
    private void stopWithCompletion(StreamCallback callback, boolean workspaceChanged,
                                    String reason, String message) {
        log.warn("ReAct 预算停止: reason={}", reason);
        finalizePlan(callback);
        callback.onCompletion(new Completion(CompletionStatus.INCOMPLETE, reason, message, workspaceChanged));
        callback.onComplete();
    }

    private void stopForNoProgress(StreamCallback callback, boolean workspaceChanged, NoProgress event) {
        log.warn("ReAct 无进展停止: reason={}, tool={}, repeats={}, stagnantRounds={}",
                event.reason(), event.toolName(), event.repeatCount(), event.stagnantRounds());
        callback.onNoProgress(event);
        finalizePlan(callback);
        callback.onCompletion(new Completion(
                CompletionStatus.INCOMPLETE, event.reason(), event.message(), workspaceChanged));
        callback.onComplete();
    }

    /** 参数 JSON 按 key 排序，避免语义相同的调用因字段顺序不同绕过重复检测。 */
    private String normalizeToolCall(String toolName, String arguments) {
        try {
            Object value = mapper.readValue(arguments == null || arguments.isBlank() ? "{}" : arguments, Object.class);
            String canonical = mapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
            return toolName + ":" + canonical;
        } catch (Exception e) {
            return toolName + ":" + (arguments == null ? "" : arguments.trim());
        }
    }

    private String normalizeFailure(String toolName, ToolResult result) {
        String error = result.getError() != null ? result.getError() : result.getContent();
        return toolName + ":" + (error == null ? "" : error.trim().replaceAll("\\s+", " "));
    }

    /**
     * 一轮结束时收口计划状态。
     *
     * <p>只有模型通过 update_plan 显式提交的 completed 才保留为完成；自然结束、断开、异常、
     * 轮次耗尽或无进展停止时，pending/in_progress 一律记为 aborted，不能替模型宣称步骤已完成。
     */
    private void finalizePlan(StreamCallback callback) {
        try {
            WorkspaceContext ctx = WorkspaceContext.get();
            if (ctx == null || ctx.plan() == null) return;
            var plan = ctx.plan();
            boolean hasRemaining = plan.stream()
                    .anyMatch(s -> "pending".equals(s.status()) || "in_progress".equals(s.status()));
            if (!hasRemaining) return;

            var finalized = plan.stream()
                    .map(s -> "completed".equals(s.status())
                            ? s : new WorkspaceContext.PlanStep(s.title(), "aborted"))
                    .toList();
            ctx.setPlan(finalized);
            callback.onPlan(finalized);
            log.info("本轮结束，计划已收口（未完成 {} 步）",
                    finalized.stream().filter(s -> !"completed".equals(s.status())).count());
        } catch (Exception e) {
            // 收尾动作失败不应该抛——主流程已经或即将结束
            log.debug("finalizePlan 静默失败（可忽略）: {}", e.getMessage());
        }
    }

    private List<ObjectNode> buildMessages(Long userId, Long projectId, Long sessionId,
                                           List<ChatMessage> history, String userPrompt,
                                           String ragContext, String fileContext, String imageContext,
                                           String sessionReferenceContext,
                                           String projectInstruction, String repoMap, String workspacePath,
                                           AgentPolicy policy) {
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

    /** 从工具入参中取出命令原文，供权限档位判断是否为破坏性命令；解析失败按空串处理（不额外要求审批） */
    private String commandOf(String arguments) {
        if (arguments == null || arguments.isBlank()) return "";
        try {
            return mapper.readTree(arguments).path("command").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * P5-08：从 start 开始的连续只读调用是否可进入并行批。
     * 只并列「连续」的只读调用：中间出现写/命令必须断开，避免后置读取跑到写入之前；
     * 需要审批（含 MCP/fallback）的调用一律走串行路径。
     */
    private boolean isParallelReadOnlyCall(ObjectNode toolCall) {
        String toolName = toolCall.path("function").path("name").asText();
        // P7-06：ask_user_question 虽然 readOnly，但必须串行走挂起/恢复拦截点；
        // 若混入只读并行批，同轮后续工具会在用户回答之前被预执行，破坏"先问再做"的语义
        if ("ask_user_question".equals(toolName)) return false;
        if (toolName.isBlank() || !toolRegistry.isReadOnly(toolName)) return false;
        if (toolRegistry.hasFallback(toolName)) return false;
        String arguments = toolCall.path("function").path("arguments").asText();
        return ToolApprovalRegistry.riskOf(toolName, arguments) == null;
    }

    /**
     * P5-08：并发执行从 start 开始的连续只读批，返回下标对齐的结果。
     * 批规模小于 2 时返回空 Map（单次调用没有并行收益，仍走串行路径）。
     * 结果只回放不合并事件：事件与 tool 消息仍由主循环按原下标顺序发出。
     */
    private Map<Integer, ToolResult> executeReadOnlyBatch(List<ObjectNode> toolCalls, int start,
                                                          Long sessionId, AgentPolicy policy,
                                                          Long projectId, AtomicBoolean cancelRequested) {
        int end = start;
        while (end < toolCalls.size() && isParallelReadOnlyCall(toolCalls.get(end))) {
            end++;
        }
        int size = end - start;
        if (size < 2) {
            return Map.of();
        }

        AgentPolicy.Permission permission = permissionTracker.get(sessionId, policy.permission());
        WorkspaceContext parent = WorkspaceContext.get();
        List<java.util.concurrent.Future<ToolResult>> futures = new ArrayList<>(size);
        for (int i = start; i < end; i++) {
            ObjectNode call = toolCalls.get(i);
            String toolName = call.path("function").path("name").asText();
            String arguments = call.path("function").path("arguments").asText();
            futures.add(readOnlyToolPool().submit(() -> {
                if (cancelRequested != null && cancelRequested.get()) {
                    return ToolResult.fail("本轮执行已取消，未执行只读工具 " + toolName);
                }
                if (parent == null) {
                    return dispatchWithScopeCheck(toolName, arguments, permission, projectId);
                }
                // 工作区工具依赖 ThreadLocal 上下文，子线程必须带入派生上下文
                return WorkspaceContext.callIn(parent.child(),
                        () -> dispatchWithScopeCheck(toolName, arguments, permission, projectId));
            }));
        }
        log.info("P5-08 只读批并发执行: {} 个调用，并发上限 {}", size, Math.max(1, readonlyMaxConcurrency));

        Map<Integer, ToolResult> results = new HashMap<>();
        for (int i = 0; i < size; i++) {
            if (cancelRequested != null && cancelRequested.get()) {
                // 停止生成：取消尚未完成的任务，剩余调用标记为未执行
                for (int j = i; j < size; j++) {
                    futures.get(j).cancel(true);
                    results.put(start + j, ToolResult.fail("本轮执行已取消，未执行只读工具"));
                }
                break;
            }
            try {
                results.put(start + i, futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.put(start + i, ToolResult.fail("只读工具执行被中断"));
            } catch (java.util.concurrent.ExecutionException e) {
                results.put(start + i, ToolResult.fail("只读工具执行异常: " + e.getCause()));
            }
        }
        return results;
    }

    /** P5-08：只读批线程池，按配置上限懒初始化；daemon 线程不阻止进程退出 */
    private java.util.concurrent.ExecutorService readOnlyToolPool() {
        java.util.concurrent.ExecutorService pool = readOnlyToolPool;
        if (pool == null) {
            synchronized (this) {
                pool = readOnlyToolPool;
                if (pool == null) {
                    java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
                    pool = java.util.concurrent.Executors.newFixedThreadPool(
                            Math.max(1, readonlyMaxConcurrency), r -> {
                                Thread t = new Thread(r, "readonly-tool-" + seq.incrementAndGet());
                                t.setDaemon(true);
                                return t;
                            });
                    readOnlyToolPool = pool;
                }
            }
        }
        return pool;
    }

    @jakarta.annotation.PreDestroy
    void shutdownReadOnlyToolPool() {
        java.util.concurrent.ExecutorService pool = readOnlyToolPool;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /**
     * D1: 带 scope 约束的工具分发——scope 非空 + 文件类工具 → 先校验路径再执行；校验失败返回 ToolResult.fail
     * 让 Agent 收到 tool_error 后自动换方案，不抛 BusinessException 炸掉整个 ReAct 循环
     */
    private ToolResult dispatchWithScopeCheck(String toolName, String arguments,
                                              AgentPolicy.Permission permission, Long projectId) {
        try {
            com.fasterxml.jackson.databind.JsonNode args = null;
            try {
                args = mapper.readTree(arguments);
            } catch (Exception e) {
                // 参数不是合法 JSON → 让工具自己解析，scope 检查跳过
            }

            // scope 约束只用相对路径匹配——如果工具传了绝对路径，让工具自己的沙箱越界校验先拦掉
            scopeConstraintService.checkToolCall(toolName, args, null);
            return toolRegistry.dispatch(toolName, arguments, permission);
        } catch (com.capsule.agentkey.exception.BusinessException e) {
            log.info("D1 scope 拦截工具调用: tool={}, reason={}", toolName, e.getMessage());
            return ToolResult.fail(e.getMessage());
        }
    }
}
