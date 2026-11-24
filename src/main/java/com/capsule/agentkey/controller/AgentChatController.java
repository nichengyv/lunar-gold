package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.PublicApi;
import com.capsule.agentkey.config.PublicApiCatalog;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.controller.support.ChatSseChannel;
import com.capsule.agentkey.controller.support.ChatStreamBridge;
import com.capsule.agentkey.dto.ApprovalBatchRequest;
import com.capsule.agentkey.dto.ApprovalDecisionRequest;
import com.capsule.agentkey.dto.ApprovalHistoryItem;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.ChatRequest;
import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.AgentPolicyResolver;
import com.capsule.agentkey.service.AgentRunService;
import com.capsule.agentkey.service.ApiKeyService;
import com.capsule.agentkey.service.ChatAttachmentService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.ContextSummaryService;
import com.capsule.agentkey.service.GenerationRegistry;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.service.VisionService;
import com.capsule.agentkey.tool.AskUserQuestionTool;
import com.capsule.agentkey.util.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/agent")
@Slf4j
public class AgentChatController {

    private final ApiKeyService apiKeyService;
    private final ReActAgentService agentService;
    private final ChatService chatService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatAttachmentService chatAttachmentService;
    private final ProjectService projectService;
    private final GenerationRegistry generationRegistry;
    private final VisionService visionService;
    private final ContextSummaryService contextSummaryService;
    private final AgentPolicyResolver policyResolver;
    /** 第六十八批 A2：工具审批登记表 */
    private final com.capsule.agentkey.service.ToolApprovalRegistry toolApprovalRegistry;
    /** 批次 B2：会话级自动记忆 */
    private final com.capsule.agentkey.service.SessionMemoryExtractionService memoryService;
    private final com.capsule.agentkey.service.AgentRunService agentRunService;
    /** P7-06：执行中用户询问——ask_user_question 挂起/恢复机制 */
    private final com.capsule.agentkey.tool.AskUserQuestionTool askUserQuestionTool;
    /** P5-01：提示词版本目录，用于把本轮版本写入 run 事件 */
    private final com.capsule.agentkey.service.PromptCatalog promptCatalog;
    private final Executor agentStreamExecutor;

    /** P5-10-3：预算上限，随 usage 事件下发给前端；0 表示不限制 */
    @org.springframework.beans.factory.annotation.Value("${agent.budget.max-tokens-per-run:0}")
    private long maxTokensPerRun = 0L;

    @org.springframework.beans.factory.annotation.Value("${agent.budget.max-seconds-per-run:0}")
    private long maxSecondsPerRun = 0L;

    public AgentChatController(ApiKeyService apiKeyService,
                               ReActAgentService agentService,
                               ChatService chatService,
                               ChatAttachmentService chatAttachmentService,
                               ProjectService projectService,
                               GenerationRegistry generationRegistry,
                               VisionService visionService,
                               ContextSummaryService contextSummaryService,
                               AgentPolicyResolver policyResolver,
                               com.capsule.agentkey.service.ToolApprovalRegistry toolApprovalRegistry,
                               com.capsule.agentkey.service.SessionMemoryExtractionService memoryService,
                               com.capsule.agentkey.service.AgentRunService agentRunService,
                               com.capsule.agentkey.tool.AskUserQuestionTool askUserQuestionTool,
                               com.capsule.agentkey.service.PromptCatalog promptCatalog,
                               @Qualifier("agentStreamExecutor") Executor agentStreamExecutor) {
        this.apiKeyService = apiKeyService;
        this.agentService = agentService;
        this.chatService = chatService;
        this.chatAttachmentService = chatAttachmentService;
        this.projectService = projectService;
        this.generationRegistry = generationRegistry;
        this.visionService = visionService;
        this.contextSummaryService = contextSummaryService;
        this.policyResolver = policyResolver;
        this.toolApprovalRegistry = toolApprovalRegistry;
        this.memoryService = memoryService;
        this.agentRunService = agentRunService;
        this.askUserQuestionTool = askUserQuestionTool;
        this.promptCatalog = promptCatalog;
        this.agentStreamExecutor = agentStreamExecutor;
    }

    /**
     * 流式聊天（SSE）。
     *
     * 为什么用 SseEmitter 而不是手写 HttpServletResponse 流：
     * 旧实现在 Tomcat 请求线程上同步调用 LLM，每次聊天占用一个 Tomcat 线程
     * 直到流结束（5~60s），并发稍高就把默认 200 个请求线程耗尽，其他接口被拖死。
     * SseEmitter 让 Controller 立即返回（容器释放请求线程），
     * 真正的 LLM 调用在独立 agentStreamExecutor 上执行，互不阻塞。
     */
    @PostMapping(value = "/chat/stream")
    @PublicApi(scope = "chat:write", group = PublicApiCatalog.GROUP_TASK)
    public SseEmitter streamChat(@RequestBody ChatRequest request, HttpServletResponse response) {
        String prompt = request.getPrompt();
        Long sessionId = request.getSessionId();
        boolean useRag = request.isUseRag();
        List<Long> attachmentIds = request.getAttachmentIds();
        Long userId = CurrentUser.requireId();
        if (request.getClientRequestId() != null
                && agentRunService.findByIdempotent(userId, request.getClientRequestId()).isPresent()) {
            throw new BusinessException("该请求已提交，请勿重复执行");
        }

        //Nginx 默认会缓冲 SSE，置 no 让事件实时下发
        response.setHeader("X-Accel-Buffering", "no");

        //———— 准备阶段（仍在请求线程上，失败直接抛 4xx/5xx 给前端，不走 SSE） ————
        Long finalSessionId = sessionId;
        Long effectiveProjectId;
        ChatSession existingSession = null;
        if (finalSessionId != null) {
            //老会话：以会话自身的项目归属为准，避免切项目后把别的项目资料混进上下文
            existingSession = chatService.getOwnedSession(finalSessionId, userId);
            effectiveProjectId = existingSession.getProjectId();
        } else {
            effectiveProjectId = projectService.resolveProjectId(userId, request.getProjectId());
        }

        //运行参数以会话已存值为基线，请求显式传值则覆盖（用户在会话头切换模型/档位/权限）；
        //老会话没有存权限时继承项目默认，项目未配置按可执行处理
        String baseModel = existingSession != null ? existingSession.getModel() : null;
        String baseReasoning = existingSession != null ? existingSession.getReasoning() : null;
        String basePermission = existingSession != null ? existingSession.getPermission() : null;
        if (basePermission == null) {
            basePermission = projectService.getOwned(userId, effectiveProjectId).getDefaultPermission();
        }
        final AgentPolicy policy = policyResolver.resolve(
                request.getModel() != null ? request.getModel() : baseModel,
                request.getReasoning() != null ? request.getReasoning() : baseReasoning,
                request.getPermission() != null ? request.getPermission() : basePermission);

        if (existingSession != null) {
            //把本次选择写回会话，切回旧会话时下拉框仍显示当时的配置
            chatService.applyAgentPolicy(existingSession.getId(), userId, policy);
        } else {
            String title;
            if (prompt != null && !prompt.isEmpty()) {
                //B6：按 Unicode 码点截断标题，避免 substring 按 UTF-16 切代理对字符成乱码
                int cp = Math.min(20, prompt.codePointCount(0, prompt.length()));
                title = prompt.substring(0, prompt.offsetByCodePoints(0, cp));
            } else {
                title = "文件分析";
            }
            ChatSession session = chatService.createSession(userId, effectiveProjectId, title, policy);
            finalSessionId = session.getId();
        }
        final Long chatSessionId = finalSessionId;
        final Long projectId = effectiveProjectId;
        // P5-14：本轮执行 id 提前生成并写入 requestJson，建立 runId ↔ turnId 的持久映射，
        // 供任务级 ChangeSet 按 Run 聚合与撤回；SSE 下发的仍是同一个值。
        final String turnId = "turn-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final com.capsule.agentkey.service.AgentRunService.CreateResult runCreation;
        try {
            Map<String, Object> runRequest = new LinkedHashMap<>();
            runRequest.put("prompt", prompt == null ? "" : prompt);
            runRequest.put("model", policy.model());
            runRequest.put("turnId", turnId);
            // P6-01：客户端发起的运行落审计字段；Web / 云端发起时不写该字段
            String clientId = com.capsule.agentkey.security.CurrentClient.clientIdOrNull();
            if (clientId != null) {
                runRequest.put("clientId", clientId);
            }
            runCreation = agentRunService.create(userId, chatSessionId, projectId,
                    request.getClientRequestId(), mapper.writeValueAsString(runRequest));
        } catch (Exception e) {
            throw new BusinessException("无法创建运行记录，请稍后重试");
        }
        // 并发请求可能越过入口预检后才命中已提交记录，此时不能再次保存消息或调度执行
        if (!runCreation.created()) {
            throw new BusinessException("该请求已提交，请勿重复执行");
        }
        final Long runId = runCreation.run().getId();

        // P5-01：记录本轮实际使用的提示词版本（scenario -> id@version），排查回答差异时先看这里
        try {
            Map<String, Object> promptPayload = new LinkedHashMap<>(promptCatalog.describe());
            agentRunService.appendEvent(runId,
                    com.capsule.agentkey.entity.AgentRunEvent.EventType.PROMPT_LOADED,
                    promptPayload);
        } catch (Exception e) {
            log.warn("记录提示词版本失败: runId={}, {}", runId, e.getMessage());
        }

        // SSE 不设固定总时长：多轮审批可能超过 180 秒，审批本身由 ToolApprovalRegistry 独立超时。
        // P6-06：客户端断流只停止推送，Run 继续执行；只有 /chat/stop 会置位取消标记。
        // emitter、心跳与两个标记统一由通道管理，Controller 只管业务编排。
        ChatSseChannel channel = ChatSseChannel.open(userId);

        String fileContext = "";
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            try {
                fileContext = chatAttachmentService.buildSmartContext(userId, attachmentIds, prompt);
            } catch (Exception e) {
                log.warn("构建附件智能上下文失败，降级为普通上下文", e);
                fileContext = chatAttachmentService.buildFileContext(attachmentIds);
            }
        }

        //先加载历史再保存当前消息，避免历史里已含当前消息导致 buildMessages 重复追加一遍
        List<ChatMessage> history;
        try {
            history = chatService.getHistory(chatSessionId, userId);
        } catch (Exception e) {
            log.error("加载历史消息失败", e);
            history = null;
        }
        // R8a：上一轮被用户停止或失败时，显式告诉模型不要自动续跑旧任务
        history = appendTaskBoundaryNote(history, userId, chatSessionId);

        try {
            String msgContent = (prompt != null && !prompt.isEmpty()) ? prompt : "[文件提问]";
            ChatMessage savedMsg = chatService.saveMessage(userId, chatSessionId,
                    ChatMessage.MessageRole.USER, msgContent, null);
            if (attachmentIds != null && !attachmentIds.isEmpty()) {
                chatAttachmentService.linkAttachmentsToMessage(attachmentIds, savedMsg.getId());
            }
        } catch (Exception e) {
            log.error("保存用户消息失败", e);
        }

        final String realApiKey;
        try {
            realApiKey = apiKeyService.getDecryptedKey(userId, "DEEPSEEK");
        } catch (Exception e) {
            //用户未配置 Key：通过 SSE 通道下发错误事件后正常关闭，前端按协议处理即可
            try {
                agentRunService.failIfActive(runId, "API_KEY_MISSING", e.getMessage());
            } catch (Exception persisted) {
                log.error("更新运行失败状态失败: runId={}", runId, persisted);
            }
            channel.sendQuietly("error", e.getMessage());
            channel.emitter().complete();
            return channel.emitter();
        }

        final String finalFileContext = fileContext;
        final List<ChatMessage> finalHistory = history;
        final List<Long> finalAttachmentIds = attachmentIds;

        //登记本次生成的取消标记：只有 /chat/stop 会置位；断流不登记，Run 可在后台继续
        generationRegistry.register(chatSessionId, channel.cancelFlag());

        ChatStreamBridge bridge = new ChatStreamBridge(
                channel, runId, turnId, chatSessionId, userId, policy, realApiKey,
                chatService, memoryService, agentRunService, mapper,
                maxTokensPerRun, maxSecondsPerRun);

        //———— 执行阶段：提交到独立线程池，Controller 立即 return emitter 释放 Tomcat 线程 ————
        agentStreamExecutor.execute(() -> {
            bridge.beginTurn();
            try {
                agentRunService.start(runId);
                //先告诉前端 sessionId，前端用它把后续流式消息挂到正确的会话上
                channel.send("session_id", String.valueOf(chatSessionId));
                channel.sendQuietly("run_id", String.valueOf(runId));
                //本轮 id 用于按轮次撤回文件改动；失败也不影响主流程
                channel.sendQuietly("turn_id", turnId);

                //截图：先交给视觉模型转写成文字，主对话模型再基于这段文字推理
                String imageContext = "";
                if (finalAttachmentIds != null && !finalAttachmentIds.isEmpty() && visionService.isEnabled()) {
                    try {
                        List<ChatAttachmentService.ImageInput> images =
                                chatAttachmentService.buildImageInputs(userId, finalAttachmentIds);
                        if (!images.isEmpty()) {
                            channel.sendQuietly("thinking", "{\"thought\":\"正在识别截图…\"}");
                            try {
                                imageContext = visionService.describeImages(realApiKey, images, prompt);
                                //补发收尾事件：否则前端思考链会一直停在「正在识别截图…」
                                channel.sendQuietly("thinking",
                                        imageContext.isBlank()
                                                ? "{\"thought\":\"截图识别无结果，已按纯文本处理\"}"
                                                : "{\"thought\":\"截图识别完成\"}");
                            } catch (Exception e) {
                                //带异常类型，便于区分超时 / 鉴权 / 参数错误
                                log.warn("截图识别失败，降级为纯文本对话 [type={}]: {}",
                                        e.getClass().getSimpleName(), e.getMessage());
                                channel.sendQuietly("thinking",
                                        "{\"thought\":\"截图识别失败，已降级为纯文本\"}");
                            }
                        }
                    } catch (Exception e) {
                        log.warn("截图识别失败，降级为纯文本对话 [type={}]: {}",
                                e.getClass().getSimpleName(), e.getMessage());
                    }
                }

                //长会话：把较早的对话压成滚动摘要并注入本轮上下文；
                //失败只降级为不注入摘要，不影响本轮回答
                String contextSummary = "";
                try {
                    contextSummary = contextSummaryService.refresh(realApiKey, chatSessionId, finalHistory, policy);
                } catch (Exception e) {
                    log.warn("生成会话摘要失败，跳过摘要注入: {}", e.getMessage());
                }

                //@会话 点名的历史会话：取最近几轮问答作为背景注入；失败只跳过引用，不影响本轮回答
                String sessionReferenceContext = "";
                try {
                    sessionReferenceContext =
                            chatService.resolveReferencedSessionContext(userId, projectId, chatSessionId, prompt);
                    if (!sessionReferenceContext.isEmpty()) {
                        channel.sendQuietly("thinking", "{\"thought\":\"已引用历史会话\"}");
                    }
                } catch (Exception e) {
                    log.warn("解析点名会话失败，跳过引用: {}", e.getMessage());
                }

                agentService.execute(realApiKey, policy, prompt, finalHistory, userId, projectId, runId, chatSessionId, turnId, useRag, finalFileContext,
                        imageContext,
                        contextSummary,
                        sessionReferenceContext,
                        channel.cancelFlag(),
                        bridge);
            } catch (Exception e) {
                bridge.fail(e, "流式聊天执行异常");
            } finally {
                generationRegistry.unregister(chatSessionId, channel.cancelFlag());
            }
        });

        return channel.emitter();
    }

    /**
     * R7b（P8-01）：从最近断点显式续跑一次被进程重启中断的 Run。
     *
     * <p>只接受 FAILED/PROCESS_RESTARTED 且留有断点的 Run；正常失败仍然保持终态。
     * 续跑不重建历史消息，直接把断点里的模型上下文作为起点，避免从头再来；
     * 工具、工作区、权限与计划快照按当前会话与项目重新解析。
     */
    @PostMapping(value = "/runs/{runId}/resume")
    @RequiresCapability(Capability.CHAT)
    @PublicApi(scope = "chat:write", group = PublicApiCatalog.GROUP_TASK)
    public SseEmitter resumeRun(@PathVariable Long runId, HttpServletResponse response) {
        Long userId = CurrentUser.requireId();
        AgentRun run = agentRunService.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new BusinessException("运行不存在或不属于当前用户"));
        if (!AgentRunService.isResumableByUser(run)) {
            throw new BusinessException("该运行不可续跑：仅支持进程重启中断或用户主动停止且已留下断点的任务");
        }
        Long sessionId = run.getSessionId();
        if (sessionId == null) {
            throw new BusinessException("该运行没有关联会话，无法续跑");
        }
        ChatSession session = chatService.getOwnedSession(sessionId, userId);
        Long projectId = session.getProjectId();
        com.capsule.agentkey.agent.run.RunCheckpoint checkpoint = agentRunService.loadCheckpoint(runId)
                .orElseThrow(() -> new BusinessException("断点快照已失效，无法续跑"));

        String prompt = "";
        String turnId = null;
        try {
            var request = mapper.readTree(run.getRequestJson() == null ? "{}" : run.getRequestJson());
            prompt = request.path("prompt").asText("");
            turnId = request.path("turnId").isMissingNode() ? null : request.path("turnId").asText(null);
        } catch (Exception e) {
            log.warn("续跑解析请求快照失败，按空提示继续: runId={}, {}", runId, e.getMessage());
        }
        final String finalPrompt = prompt == null ? "" : prompt;
        final String finalTurnId = (turnId == null || turnId.isBlank())
                ? "turn-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : turnId;

        String basePermission = session.getPermission();
        if (basePermission == null) {
            basePermission = projectService.getOwned(userId, projectId).getDefaultPermission();
        }
        final AgentPolicy policy = policyResolver.resolve(
                session.getModel(), session.getReasoning(), basePermission);
        final String apiKey;
        try {
            apiKey = apiKeyService.getDecryptedKey(userId, "DEEPSEEK");
        } catch (Exception e) {
            throw new BusinessException("模型密钥不可用，无法续跑");
        }

        response.setHeader("X-Accel-Buffering", "no");
        ChatSseChannel channel = ChatSseChannel.open(userId);
        ChatStreamBridge bridge = new ChatStreamBridge(
                channel, runId, finalTurnId, sessionId, userId, policy, apiKey,
                chatService, memoryService, agentRunService, mapper,
                maxTokensPerRun, maxSecondsPerRun);
        final com.capsule.agentkey.agent.run.RunCheckpoint finalCheckpoint = checkpoint;
        final Long finalSessionId = sessionId;
        final Long finalProjectId = projectId;
        generationRegistry.register(sessionId, channel.cancelFlag());

        agentStreamExecutor.execute(() -> {
            bridge.beginTurn();
            try {
                // FAILED(PROCESS_RESTARTED) → RUNNING；并发重复点击时第二次会在这里被拒绝
                agentRunService.resumeInterrupted(runId);
                channel.send("session_id", String.valueOf(finalSessionId));
                channel.sendQuietly("run_id", String.valueOf(runId));
                channel.sendQuietly("turn_id", finalTurnId);
                agentService.resume(apiKey, policy, runId, userId, finalProjectId, finalSessionId,
                        finalTurnId, finalPrompt, finalCheckpoint, channel.cancelFlag(), bridge);
            } catch (Exception e) {
                bridge.fail(e, "续跑执行异常");
            } finally {
                generationRegistry.unregister(finalSessionId, channel.cancelFlag());
            }
        });

        return channel.emitter();
    }

    /**
     * R7b：当前会话最近一次运行若可续跑，返回它的 runId 与断点轮次，供前端展示「继续执行」。
     * 只看最近一条 Run：用户已经继续对话后，不再提示续跑更早的中断任务。
     */
    @GetMapping("/sessions/{sessionId}/resumable-run")
    @PublicApi(scope = "run:read", group = PublicApiCatalog.GROUP_TASK)
    public ApiResponse<Map<String, Object>> resumableRun(@PathVariable Long sessionId) {
        Long userId = CurrentUser.requireId();
        chatService.getOwnedSession(sessionId, userId);
        AgentRun run = agentRunService.findLatestResumable(userId, sessionId).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resumable", run != null);
        if (run != null) {
            data.put("runId", run.getId());
            data.put("errorCode", run.getErrorCode());
            data.put("round", agentRunService.loadCheckpoint(run.getId())
                    .map(com.capsule.agentkey.agent.run.RunCheckpoint::getRound).orElse(0));
        }
        return ApiResponse.success(data);
    }

    /**
     * P4-04：断线续传入口。
     * 客户端带着最后收到的 seq 重连，只取增量事件；同时拿到运行终态，
     * 若运行已结束，可直接用现有消息接口拉取完整回答。
     */
    @GetMapping("/runs/{runId}/events")
    @PublicApi(scope = "run:read", group = PublicApiCatalog.GROUP_TASK)
    public ApiResponse<Map<String, Object>> runEvents(@PathVariable Long runId,
                                                      @RequestParam(value = "sinceSeq", required = false) Integer sinceSeq) {
        Long userId = CurrentUser.requireId();
        com.capsule.agentkey.entity.AgentRun run = agentRunService.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new BusinessException("运行不存在或不属于当前用户"));
        List<Map<String, Object>> events = agentRunService.findEvents(runId, sinceSeq).stream()
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("seq", e.getSeq());
                    item.put("type", e.getEventType().name());
                    item.put("payload", e.getPayload());
                    item.put("createTime", e.getCreateTime());
                    return item;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId());
        result.put("state", run.getState().name());
        result.put("errorCode", run.getErrorCode());
        result.put("errorMessage", run.getErrorMessage());
        result.put("promptTokens", run.getPromptTokens());
        result.put("completionTokens", run.getCompletionTokens());
        result.put("totalTokens", run.getTotalTokens());
        result.put("resumable", AgentRunService.isResumableByUser(run));
        result.put("events", events);
        return ApiResponse.success(result);
    }

    /**
     * P5-10：刷新恢复入口。
     * 页面刷新后前端不知道当前还有哪一轮在跑，先查这里拿到 runId，再用上面的接口重放事件。
     */
    @GetMapping("/runs/running")
    public ApiResponse<List<Map<String, Object>>> runningRuns() {
        Long userId = CurrentUser.requireId();
        List<Map<String, Object>> list = agentRunService.findRunningByUser(userId).stream()
                .map(run -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("runId", run.getId());
                    item.put("sessionId", run.getSessionId());
                    item.put("state", run.getState().name());
                    item.put("createTime", run.getCreateTime());
                    return item;
                })
                .toList();
        return ApiResponse.success(list);
    }

    /**
     * R8a：上一轮已停止/失败时，在历史尾部补一条 system 段。
     * 只影响本轮模型输入，不落库、不展示，避免把「任务已作废」误当成新的用户指令。
     */
    private List<ChatMessage> appendTaskBoundaryNote(List<ChatMessage> history, Long userId, Long sessionId) {
        String previousState = agentRunService.findLatestTerminalRun(userId, sessionId)
                .map(run -> run.getState().name())
                .orElse(null);
        return com.capsule.agentkey.agent.run.TaskBoundaryNote
                .appendIfUnfinished(history, userId, sessionId, previousState);
    }

    /**
     * 停止生成：置位该会话全部活跃 Run 的取消标记。
     * P6-06：客户端断流已不再等于取消，长命令执行期间也没有 SSE 写出，
     * 所以必须通过这个接口显式停止，ReAct 循环与子进程才会尽快结束。
     */
    @PostMapping("/chat/stop")
    @PublicApi(scope = "chat:write", group = PublicApiCatalog.GROUP_TASK)
    public ApiResponse<Void> stopGeneration(@RequestParam(value = "sessionId", required = false) Long sessionId) {
        Long userId = CurrentUser.requireId();
        if (sessionId != null) {
            //校验归属，避免用别人的 sessionId 打断别人的生成
            chatService.getOwnedSession(sessionId, userId);
            generationRegistry.cancel(sessionId);
            // R8a：把「用户主动停止」立即落到 Run 上，供下一轮判断上一轮已作废
            agentRunService.markStopReasonForSession(userId, sessionId, "USER_STOP");
            //第六十八批 A2：同时拒绝该会话所有待审批，唤醒 ReAct 线程
            toolApprovalRegistry.cancelSession(sessionId);
        }
        return ApiResponse.success(null);
    }

    /**
     * 第六十八批 A2：提交工具审批结果。
     * 前端在审批卡片上点「批准 / 拒绝 / 本会话总是允许」后调用。
     */
    @PostMapping("/approvals/{approvalId}")
    @RequiresCapability(Capability.APPROVAL)
    @PublicApi(scope = "approval:write", group = PublicApiCatalog.GROUP_TASK)
    public ApiResponse<Void> respondApproval(@PathVariable String approvalId,
                                             @RequestBody ApprovalDecisionRequest request) {
        Long userId = CurrentUser.requireId();
        boolean remember = "SESSION".equalsIgnoreCase(request.getRemember());
        // P6-05：审批入口由服务端从认证结果推断，CLI / VS Code 为客户端令牌，Web 为本地令牌
        String clientId = com.capsule.agentkey.security.CurrentClient.clientIdOrNull();
        if (!toolApprovalRegistry.resolve(approvalId, userId, request.getDecision(), remember, clientId)) {
            throw new BusinessException("审批已失效或不属于当前用户");
        }
        return ApiResponse.success(null);
    }

    /**
     * P5-10-2：批量允许当前会话的低风险写入审批。
     * 只允许 APPROVE；高危、跨会话、越权 ID 整批拒绝，不提供批量拒绝或会话记忆。
     */
    @PostMapping("/approvals/batch")
    @RequiresCapability(Capability.APPROVAL)
    @PublicApi(scope = "approval:write", group = PublicApiCatalog.GROUP_TASK)
    public ApiResponse<Map<String, Object>> respondApprovalBatch(@RequestBody ApprovalBatchRequest request) {
        Long userId = CurrentUser.requireId();
        Long sessionId = request.getSessionId();
        if (sessionId == null) {
            throw new BusinessException("缺少会话 id");
        }
        // 先校验会话归属，避免拿别人的 sessionId 批量批准
        chatService.getOwnedSession(sessionId, userId);
        String clientId = com.capsule.agentkey.security.CurrentClient.clientIdOrNull();
        com.capsule.agentkey.service.ToolApprovalRegistry.BatchResult result =
                toolApprovalRegistry.resolveBatch(userId, sessionId, request.getApprovalIds(), clientId);
        if (!result.accepted()) {
            throw new BusinessException(result.reason());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", result.batchId());
        data.put("resolvedIds", result.resolvedIds());
        return ApiResponse.success(data);
    }

    /** P5-10-2：会话内已处理审批历史；先校验会话归属，不返回原始参数 */
    @GetMapping("/sessions/{sessionId}/approvals")
    @PublicApi(scope = "run:read", group = PublicApiCatalog.GROUP_TASK)
    public ApiResponse<List<ApprovalHistoryItem>> sessionApprovals(@PathVariable Long sessionId) {
        Long userId = CurrentUser.requireId();
        chatService.getOwnedSession(sessionId, userId);
        List<ApprovalHistoryItem> items = toolApprovalRegistry.history(sessionId, userId).stream()
                .map(saved -> new ApprovalHistoryItem(
                        saved.getApprovalId(),
                        saved.getToolName(),
                        saved.getRisk(),
                        saved.getState(),
                        saved.getDecisionSource(),
                        saved.getClientId(),
                        saved.getDecisionTime(),
                        saved.getCreateTime(),
                        saved.isRememberSession()))
                .toList();
        return ApiResponse.success(items);
    }

    // -----------------------------------------------------------------
    // P7-06：ask_user_question 用户回答端点
    // -----------------------------------------------------------------

    /**
     * 用户回答 agent 执行中提出的决策询问。
     * Run 状态从 WAITING_USER_INPUT 恢复为 RUNNING，携带答案进入模型上下文继续循环。
     */
    @PostMapping("/runs/{runId}/answer")
    @RequiresCapability(Capability.CHAT)
    public ApiResponse<Map<String, Object>> answerUserQuestion(@PathVariable Long runId,
                                                               @RequestBody Map<String, Object> body) {
        Long userId = CurrentUser.requireId();
        // 归属校验：确保 Run 属于当前用户
        AgentRun run = agentRunService.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new BusinessException("运行不存在或无权访问"));
        if (run.getState() != AgentRun.RunState.WAITING_USER_INPUT) {
            throw new BusinessException("当前运行不在等待用户输入状态");
        }

        String questionId = (String) body.get("questionId");
        if (questionId == null || questionId.isBlank()) {
            throw new BusinessException("缺少 questionId");
        }

        @SuppressWarnings("unchecked")
        List<String> selected = (List<String>) body.getOrDefault("selected", List.of());
        String custom = (String) body.get("custom");

        // 1. 恢复 AgentRun 状态机（WAITING_USER_INPUT → RUNNING）
        agentRunService.resumeAfterUserInput(runId, questionId, selected, custom);

        // 2. 唤醒 ask_user_question 里挂起的 ReAct 线程
        boolean resolved = askUserQuestionTool.resolve(runId,
                new AskUserQuestionTool.UserAnswer(questionId, selected, custom));
        if (!resolved) {
            // Run 状态已经恢复，但 ReAct 线程已经不存在了（比如重启）——没关系，状态机已就位
            log.warn("answerUserQuestion：pending question 未找到（runId={}，可能已重启）", runId);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", runId);
        data.put("questionId", questionId);
        data.put("state", "RUNNING");
        return ApiResponse.success(data);
    }

}
