package com.capsule.agentkey.service;

import com.capsule.agentkey.client.AgentStreamCallback;
import com.capsule.agentkey.client.SpringAiAgentClient;
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

    /**
     * 第六十二批：system prompt 固定骨架——"你是一个通用智能助手..."大段规则。
     * 提成 static final 避免每次 buildMessages 都重新 new StringBuilder + append 50 行。
     * 动态部分（projectInstruction / permission / workspaceTree 等）仍然在 buildMessages 里拼。
     */
    private static final String SYSTEM_PROMPT_SKELETON =
            "你是一个通用智能助手，具备天气查询、联网搜索、知识库问答、文件分析等能力。\n"
                    + "\n"
                    + "【核心原则】\n"
                    + "1. 用户问天气、气温、穿衣建议时 → 必须调用 get_current_weather 工具\n"
                    + "2. 用户问最新新闻、实时信息、你不确定的知识时 → 必须调用 web_search 工具\n"
                    + "3. 用户上传了文件并询问文件内容时 → 基于文件内容回答，不要调用工具\n"
                    + "4. 用户问通用问题（编程、数学、常识、闲聊）→ 直接回答，不调用工具\n"
                    + "5. 工具返回错误时，如实告知用户，不要编造数据\n"
                    + "6. 如果下方提供了【知识库检索结果】或【用户上传的文件内容】，请优先基于这些信息回答\n"
                    + "\n"
                    + "【文件处理要求】\n"
                    + "1. 用户上传文件后，文件内容会附在问题之前\n"
                    + "2. 回答时明确指出你引用了哪个文件\n"
                    + "3. 用户上传图片时，系统会先用视觉模型把图片转写成文字（见【用户上传的截图】），"
                    + "直接基于转写内容回答；不要声称自己看不到图片\n"
                    + "4. 如果文件内容过长被截断，告知用户已基于部分内容回答\n"
                    + "\n"
                    + "【文件修改规则】\n"
                    + "1. 修改文件前先用 read_file 读取实际内容，oldText 必须与文件完全一致（空格、缩进、换行都要一致）\n"
                    + "2. oldText 只取「要改动的最小片段」，不要整行/整段复制。"
                    + "例如在某一行的句尾追加文字：oldText 用该行末尾 2-5 个已有字符，newText = 这些字符 + 要追加的内容\n"
                    + "3. 除非用户明确要求重写整行，否则不要删掉整行再加回整行；"
                    + "未被要求改动的原有文字必须原样保留，否则差异视图会显示成「整行删除再新增」\n"
                    + "4. 在指定行插入内容时：用该行或相邻行的唯一片段作为锚点，不要覆盖整行\n"
                    + "5. 修改完成后用 read_file 复核结果\n"
                    + "6. 删除文件或目录用 delete_file（不要用 run_command 执行 rm/del）；删除非空目录必须传 recursive=true\n"
                    + "\n"
                    + "【任务计划】\n"
                    + "1. 任务需要 3 个及以上步骤（例如先读文件、再修改、最后运行测试）时，先调用 update_plan 列出步骤再开始执行\n"
                    + "2. 每完成一步，立即再次调用 update_plan 更新状态：该步 completed，下一步 in_progress\n"
                    + "3. 同一时间最多一个步骤为 in_progress；步骤标题不超过 80 字\n"
                    + "4. 单步问题、闲聊或纯问答不要调用 update_plan\n"
                    + "\n"
                    + "【天气播报要求】\n"
                    + "当调用天气工具后，生成一段完整的天气播报，包含：城市、天气状况、温度、体感温度、湿度、风速。\n"
                    + "播报要口语化、有温度，不要罗列原始数据。\n"
                    + "必须给出生活建议（穿衣、是否带伞、是否适合户外活动）\n"
                    + "\n"
                    + "【知识库引用要求】\n"
                    + "1. 回答时如果使用了知识库内容，请在相关段落末尾标注 [知识库]\n"
                    + "2. 如果知识库内容与用户问题无关，忽略它，正常回答\n"
                    + "3. 不要编造知识库中没有的信息\n"
                    + "\n"
                    + "【通用对话要求】\n"
                    + "1. 回答用户通用问题时，保持友好、专业、简洁\n"
                    + "2. 不要过度自我介绍，不要每句话都提'天气'\n"
                    + "3. 如果用户问'你能做什么'，如实列举：天气查询、联网搜索、知识库问答、文件分析、通用问答\n"
                    + "4. 回答控制在 100-500 字，信息完整不啰嗦"
                    + "\n\n"
                    + "【知识库全局规则】\n"
                    + "1. 知识库检索在当前项目内进行：本项目下的任何一个会话提问都可能命中项目资料\n"
                    + "2. 只要本轮消息中附带了【知识库检索结果】，就证明系统已成功检索到知识库内容，直接基于它回答\n"
                    + "3. 绝不要声称\"本会话没有绑定知识库\"\"我看不到知识库\"\"检索没有被触发\"；\n"
                    + "   如果检索结果与当前问题无关，直接基于自身知识正常回答即可，不要向用户讨论检索机制\n"
                    + "4. 历史回复中若出现过\"看不到知识库\"等旧说法，一律以本轮实际的检索结果为准，不要延续旧结论";

    //第四十九批删旧：Spring AI 是唯一模型接入层，自研 okhttp 客户端已移除
    private final SpringAiAgentClient springAiClient;
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
    private final ObjectMapper mapper = new ObjectMapper();

    //B2：送入大模型的单条工具结果上限（字符）。网页抓取/文档片段可能极大，
    //不截断的话一次工具调用就能撑爆上下文窗口、推高 token 费用
    private static final int TOOL_CONTENT_LIMIT = 2000;

    //B2：超长工具结果截断，末尾附提示让模型知道内容不完整。
    //构建/测试日志的关键信息（报错行、失败原因）通常在末尾，只留头部会让模型看不到问题，
    //所以改成「头部 + 尾部」各留一半。
    private String truncateForContext(String s) {
        if (s == null || s.length() <= TOOL_CONTENT_LIMIT) {
            return s;
        }
        int half = TOOL_CONTENT_LIMIT / 2;
        return s.substring(0, half)
                + "\n…（中间内容省略）…\n"
                + s.substring(s.length() - half);
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

    public ReActAgentService(SpringAiAgentClient springAiClient,
                             ToolRegistry toolRegistry,
                             DocumentService documentService,
                             ProjectService projectService,
                             WorkspaceFileService workspaceFileService,
                             com.capsule.agentkey.mcp.McpToolBridge mcpToolBridge,
                             com.capsule.agentkey.repository.AgentRuleRepository ruleRepo,
                             ToolApprovalRegistry toolApprovalRegistry,
                             ScopeConstraintService scopeConstraintService,
                             SessionPermissionTracker permissionTracker) {
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
    }

    //流式回调接口
    public interface StreamCallback {
        void onThinking(String thought);//模型思考内容
        void onToolCall(String toolName, String arguments);//触发工具调用，显示调用工具，入参
        void onToolResult(String toolName, ToolResult result);//工具执行完毕，推送工具返回结果
        //工作区文件被工具改动时推送（write_file/edit_file/run_command），前端据此展示变更文件与 diff
        default void onFileChange(com.capsule.agentkey.tool.FileChange change) {}
        //第六十八批 A1：update_plan 提交/更新任务计划后推送，前端实时刷新计划面板
        default void onPlan(java.util.List<WorkspaceContext.PlanStep> steps) {}
        //第六十八批 A2：危险工具执行前请求用户审批，approvalId 用于前端回传决策
        default void onApprovalRequired(String approvalId, String tool,
                                        com.capsule.agentkey.dto.ApprovalPreview preview,
                                        String risk, long expiresAt) {}
        //批次 B：审批被处理后的结果通知（无论用户批准/拒绝、超时、断开，前端据此把卡片从"待决策"态切换到"已处理"态）
        default void onApprovalResolved(String approvalId, String decision) {}
        void onChunk(String chunk);//大模型普通文字流式分片，逐字返回给前端
        //命中的资料出处，用于回答下方展示「参考了哪些资料」；无命中时不触发
        default void onSources(java.util.List<DocumentService.Source> sources) {}
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
                        AtomicBoolean cancelled,//B3：客户端断连标记，SSE 写出失败即置位
                        StreamCallback callback) {//流式回调，向前端推送每一步状态
        //工作区工具需要 userId/projectId 上下文，用 ThreadLocal 透传（dispatch 签名不变）；
        //cancelled 让 run_command 这类长耗时工具能在用户停止生成时杀掉子进程
        WorkspaceContext.set(userId, projectId, cancelled);
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
            String workspaceTree = "";
            String workspacePath = null;
            try {
                Long resolvedId = projectService.resolveProjectId(userId, projectId);
                com.capsule.agentkey.entity.Project p = projectService.getOwned(userId, resolvedId);
                projectInstruction = p.getInstruction();
                workspacePath = p.getWorkspacePath();
                //如果绑定了工作区，把根目录文件树注入 system prompt，让 Agent 知道项目结构
                if (workspacePath != null) {
                    workspaceTree = buildWorkspaceTree(workspacePath);
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
                    projectInstruction, workspaceTree, policy);
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
            //开始 ReAct 循环（轮次上限由推理档位决定：快速 10 / 标准 30 / 深度 60）
            boolean budgetHintSent = false;
            while (round < policy.reasoning().maxToolRounds()) {
                //B3：客户端已断开（SSE 写出失败）→ 立即中止，不再发起下一次 LLM 调用，
                //避免用户关掉页面后智能体继续空转消耗 token
                if (cancelled != null && cancelled.get()) {
                    log.info("客户端已断开，中止 ReAct 推理 [round={}]", round + 1);
                    // 批次 B：客户端断开前也标记剩余步骤 aborted
                    finalizePlan(callback, false);
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
                    public void onError(Throwable t) {
                        streamError.set(t);
                    }
                });

                if (streamError.get() != null) {
                    throw new RuntimeException(streamError.get());
                }

                if (streamedToolCalls.isEmpty()) {//模型没要求调工具 → 文本已经边生成边推送完毕
                    // 模型给出了最终回答，说明它认为任务结束：把进行中的步骤收口为 completed
                    finalizePlan(callback, true);
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

                for (ObjectNode toolCall : streamedToolCalls) {//循环遍历，支持一次调用多个工具
                    String toolName = toolCall.path("function").path("name").asText();
                    String arguments = toolCall.path("function").path("arguments").asText();//工具所需的入参
                    String toolCallId = toolCall.path("id").asText();//工具调用的唯一编号

                    callback.onToolCall(toolName, arguments);
                    log.info("调用工具: {} 参数: {}", toolName, arguments);

                    ToolResult result;//根据工具名称找到对应实现类
                    String risk = ToolApprovalRegistry.riskOf(toolName, arguments);
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
                        ToolApprovalRegistry.Decision decision = toolApprovalRegistry.await(approval, cancelled);
                        // 批次 B：无论结果如何，通知前端把卡片切到"已处理"态
                        callback.onApprovalResolved(approval.approvalId(), decision.name());
                        if (decision == ToolApprovalRegistry.Decision.REJECT) {
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
                    callback.onToolResult(toolName, result);
                    log.info("工具结果: success={}, cost={}ms", result.isSuccess(), result.getCostMs());

                    //工具改动了工作区文件 → 逐个推给前端（在 tool_result 之后，保证顺序稳定）
                    for (com.capsule.agentkey.tool.FileChange change : WorkspaceContext.drainFileChanges()) {
                        callback.onFileChange(change);
                    }

                    //第六十八批 A1：update_plan 更新了计划 → 推送最新快照
                    WorkspaceContext ctx = WorkspaceContext.get();
                    if (ctx != null && ctx.drainPlanChanged()) {
                        callback.onPlan(ctx.plan());
                    }

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
                    toolMsg.put("content", truncateForContext(toolContent));

                    messages.add(toolMsg);//把组装完毕的tool消息存入全局消息数组
                }
                round++;
            }

            //B3：断连后不再做收尾生成，但计划仍然要标记 aborted
            if (cancelled != null && cancelled.get()) {
                log.info("客户端已断开，跳过收尾生成");
                finalizePlan(callback, false);
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
                public void onError(Throwable t) {
                    finalError.set(t);
                }
            });
            if (finalError.get() != null) {
                throw new RuntimeException(finalError.get());
            }
            // 批次 B 修复：finalizePlan 必须在 onComplete 之前推 plan 事件，
            // 因为 onComplete 发 SSE done 后前端就停止处理后续事件了
            finalizePlan(callback, false);
            callback.onComplete();

        } catch (Exception e) {
            log.error("ReAct 执行异常", e);
            // 异常退出同样要先收口计划，再通知前端出错
            finalizePlan(callback, false);
            callback.onError(e);
        } finally {
            WorkspaceContext.clear();
        }
    }

    /**
     * 一轮结束时收口计划状态。
     *
     * 以前统一标 aborted，导致「任务其实做完了」刷新后仍显示只完成一部分；
     * 现在区分退出方式：
     * - naturalFinish=true（模型不再调工具、直接给出最终回答）→ in_progress 记为 completed，pending 记为 aborted；
     * - naturalFinish=false（断开 / 异常 / 轮次耗尽）→ 未完成步骤一律 aborted。
     * 无论哪种都推一次最新快照，保证前端与刷新后的历史一致。
     */
    private void finalizePlan(StreamCallback callback, boolean naturalFinish) {
        try {
            WorkspaceContext ctx = WorkspaceContext.get();
            if (ctx == null || ctx.plan() == null) return;
            var plan = ctx.plan();
            boolean hasRemaining = plan.stream()
                    .anyMatch(s -> "pending".equals(s.status()) || "in_progress".equals(s.status()));
            if (!hasRemaining) return;

            var finalized = plan.stream()
                    .map(s -> {
                        if ("completed".equals(s.status())) return s;
                        if (naturalFinish && "in_progress".equals(s.status())) {
                            return new WorkspaceContext.PlanStep(s.title(), "completed");
                        }
                        return new WorkspaceContext.PlanStep(s.title(), "aborted");
                    })
                    .toList();
            ctx.setPlan(finalized);
            callback.onPlan(finalized);
            log.info("本轮结束，计划已收口（naturalFinish={}，未完成 {} 步）",
                    naturalFinish,
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
                                           String projectInstruction, String workspaceTree,
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

        //第六十二批：固定骨架提成 static final，这里只 new StringBuilder(常量)
        systemContent.append(SYSTEM_PROMPT_SKELETON);

        //项目级自定义指令：补充对话约束（如背景、口吻、领域要求），权重低于内置规则但高于 RAG 上下文
        if (projectInstruction != null && !projectInstruction.isBlank()) {
            systemContent.append("\n\n【项目自定义要求】\n").append(projectInstruction.trim());
            log.info("项目指令已注入 system 消息（{} 字符）", projectInstruction.trim().length());
        }

        //当前工作权限：让模型知道哪些操作不可用，避免反复尝试越权工具
        systemContent.append("\n\n【当前工作权限】\n").append(policy.permission().promptRule());

        //【第五十一批】MCP 外部工具规则：和内置文件工具功能重叠，指导模型别重复调
        systemContent.append("\n\n【MCP 外部工具使用规则】\n")
                .append("系统可能同时提供两类文件/目录能力：\n")
                .append("- 内置工具：list_dir / read_file / write_file / edit_file / search_files / run_command\n")
                .append("- MCP 外部工具：名称以 mcp_ 开头，description 以【外部·xxx】开头\n")
                .append("1. 同一任务只选一类，不要同时用内置和 MCP 工具做同一件事\n")
                .append("2. 拿到关键信息后就直接回答，不要把一组 MCP 工具挨个探一遍\n")
                .append("3. MCP filesystem server 能访问的目录范围由它自己的配置决定，不一定就是当前工作区；")
                .append("用 list_allowed_directories 先确认可访问范围，再选 list_directory / read_text_file 等具体工具\n");

        //工作区文件树：让 Agent 知道当前项目的目录结构，按需用 list_dir/read_file 深入
        if (workspaceTree != null && !workspaceTree.isBlank()) {
            systemContent.append("\n\n【工作区文件结构】\n")
                    .append("以下是当前工作区的根目录结构，你可以用 list_dir 查看子目录、read_file 读取文件内容：\n")
                    .append(workspaceTree);
            //没有执行权限时整段终端说明都不注入，避免模型误以为可以跑命令
            if (policy.permission().canExecute()) {
                systemContent.append("\n\n【终端命令】\n")
                        .append("需要构建、跑测试、执行脚本或查看 git 状态时，用 run_command 在工作区内执行命令")
                        .append("（如 'npm run build'、'mvn -q test'、'python main.py'）。\n")
                        .append("1. 命令失败时先读退出码和输出，定位报错再修改文件，不要盲目重复执行同一条命令\n")
                        .append("2. 不要用 dir/cat/ls 浏览文件，用 list_dir / read_file\n")
                        .append("3. 不要把命令拼接成管道或重定向，一次只执行一条命令\n")
                        .append("4. 项目自带 mvnw/mvnw.cmd、gradlew/gradlew.bat 时优先用它们执行（如 './mvnw.cmd clean package'）\n")
                        .append("5. 找不到 java/mvn 时，把错误原文如实转告用户，不要改写或猜测环境");
            }
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

    /**
     * 构建工作区根目录的单层文件树文本（只列根目录，不递归，避免上下文过长）。
     * Agent 需要深入时自己调 list_dir。
     */
    private String buildWorkspaceTree(String workspaceRoot) {
        try {
            List<WorkspaceFileService.FileNode> nodes = workspaceFileService.listDir(workspaceRoot, "");
            if (nodes.isEmpty()) return "(空工作区)";
            StringBuilder sb = new StringBuilder();
            for (WorkspaceFileService.FileNode n : nodes) {
                String prefix = n.isDirectory() ? "[dir] " : "[file] ";
                sb.append(prefix).append(n.name()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("构建工作区树失败: {}", e.getMessage());
            return "";
        }
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
