package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.dto.ApprovalBatchRequest;
import com.capsule.agentkey.dto.ApprovalDecisionRequest;
import com.capsule.agentkey.dto.ApprovalHistoryItem;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.ChatRequest;
import com.capsule.agentkey.dto.ChatSessionStats;
import com.capsule.agentkey.dto.MessageSearchHit;
import com.capsule.agentkey.entity.ChatAttachment;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.service.AgentExperienceMetricsService;
import com.capsule.agentkey.service.AgentPolicy;
import com.capsule.agentkey.service.AgentPolicyResolver;
import com.capsule.agentkey.service.ApiKeyService;
import com.capsule.agentkey.service.ChatAttachmentService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.ContextSummaryService;
import com.capsule.agentkey.service.DocumentService;
import com.capsule.agentkey.service.GenerationRegistry;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.service.VisionService;
import com.capsule.agentkey.tool.FileChange;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    /** D3 可观测性：MeterRegistry 用于查 Agent 工具调用 Timer */
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @org.springframework.beans.factory.annotation.Autowired
    private AgentExperienceMetricsService experienceMetricsService;
    private final ContextSummaryService contextSummaryService;
    private final AgentPolicyResolver policyResolver;
    /** 第六十八批 A2：工具审批登记表 */
    private final com.capsule.agentkey.service.ToolApprovalRegistry toolApprovalRegistry;
    /** 批次 B2：会话级自动记忆 */
    private final com.capsule.agentkey.service.SessionMemoryExtractionService memoryService;
    private final com.capsule.agentkey.service.AgentRunService agentRunService;
    /** P5-01：提示词版本目录，用于把本轮版本写入 run 事件 */
    private final com.capsule.agentkey.service.PromptCatalog promptCatalog;
    private final Executor agentStreamExecutor;

    /** P5-10-3：预算上限，随 usage 事件下发给前端；0 表示不限制 */
    @org.springframework.beans.factory.annotation.Value("${agent.budget.max-tokens-per-run:0}")
    private long maxTokensPerRun = 0L;

    @org.springframework.beans.factory.annotation.Value("${agent.budget.max-seconds-per-run:0}")
    private long maxSecondsPerRun = 0L;

    /** P4-04：SSE 心跳间隔；Nginx/网关通常 60s 空闲断连，15s 足够安全 */
    private static final long SSE_HEARTBEAT_SECONDS = 15L;

    /** P5-10：回答内容的断点检查点间隔；太小会把事件表写爆，太大丢字多 */
    private static final long CONTENT_CHECKPOINT_INTERVAL_MS = 800L;
    /** P5-10：单个检查点累计正文上限，超长回答只保证前 2 万字可恢复 */
    private static final int CONTENT_CHECKPOINT_LIMIT = 20000;

    /** P4-04：心跳线程与业务线程隔离，单线程足够（写 SSE 不阻塞） */
    private static final ScheduledExecutorService SSE_HEARTBEAT =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

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
                               com.capsule.agentkey.service.PromptCatalog promptCatalog,
                               @Qualifier("agentStreamExecutor") Executor agentStreamExecutor,
                               io.micrometer.core.instrument.MeterRegistry meterRegistry) {
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
        this.promptCatalog = promptCatalog;
        this.agentStreamExecutor = agentStreamExecutor;
        this.meterRegistry = meterRegistry;
    }

    //从security上下文取出（princcipal就是取出第一个参数，这里是userid）
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {//instanceof x确认前面是不是x类型
            return (Long) auth.getPrincipal();
        }
        throw new BusinessException("用户未认证");
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
    public SseEmitter streamChat(@RequestBody ChatRequest request, HttpServletResponse response) {
        String prompt = request.getPrompt();
        Long sessionId = request.getSessionId();
        boolean useRag = request.isUseRag();
        List<Long> attachmentIds = request.getAttachmentIds();
        Long userId = getCurrentUserId();
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
        // 客户端断开仍通过写出异常或 /chat/stop 置位，不会放任工具继续执行。
        TrackedEmitter emitter = new TrackedEmitter(0L);

        //B3：断连标记——任一 SSE 写出抛异常即置位，ReAct 循环在每轮开始前检查该标记并中止
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);

        // P4-04：心跳注释帧保活；连接结束后必须取消，否则会一直往已关闭的 emitter 写。
        // 注意在准备阶段之后才创建，避免参数校验失败时留下永不取消的定时任务。
        ScheduledFuture<?> heartbeat = SSE_HEARTBEAT.scheduleAtFixedRate(() -> {
            try {
                emitter.heartbeat();
            } catch (Exception e) {
                clientDisconnected.set(true);
            }
        }, SSE_HEARTBEAT_SECONDS, SSE_HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        emitter.onCompletion(() -> heartbeat.cancel(false));
        emitter.onTimeout(() -> {
            heartbeat.cancel(false);
            clientDisconnected.set(true);
            log.warn("SSE 超时，中止 LLM 调用 [userId={}]", userId);
        });
        emitter.onError(t -> {
            heartbeat.cancel(false);
            clientDisconnected.set(true);
            log.warn("SSE 连接错误 [userId={}]: {}", userId, t.getMessage());
        });

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
            markRunFailed(runId, "API_KEY_MISSING", e.getMessage());
            sendEventQuietly(emitter, "error", e.getMessage(), clientDisconnected);
            emitter.complete();
            return emitter;
        }

        final String finalFileContext = fileContext;
        final List<ChatMessage> finalHistory = history;
        final List<Long> finalAttachmentIds = attachmentIds;

        //登记本次生成：前端点「停止」时通过 /chat/stop 置位同一标记，长命令也能被及时杀掉
        generationRegistry.register(chatSessionId, clientDisconnected);

        //———— 执行阶段：提交到独立线程池，Controller 立即 return emitter 释放 Tomcat 线程 ————
        agentStreamExecutor.execute(() -> {
            StringBuilder fullResponse = new StringBuilder();
            // 本轮执行轨迹：思考/工具/来源/改动，完成时随助手消息一起落库，切回会话仍能还原卡片
            long traceStartedAt = System.currentTimeMillis();
            // 第五十六批：耗时口径拆分——记录各阶段时间戳
            // 用 AtomicLong 才能在匿名 StreamCallback 内部读写（Java effectively final 要求）
            java.util.concurrent.atomic.AtomicLong firstToolCallAt = new java.util.concurrent.atomic.AtomicLong(0);
            java.util.concurrent.atomic.AtomicLong lastToolResultAt = new java.util.concurrent.atomic.AtomicLong(0);
            java.util.concurrent.atomic.AtomicLong toolCostMsSum = new java.util.concurrent.atomic.AtomicLong(0);
            java.util.concurrent.atomic.AtomicLong firstChunkAt = new java.util.concurrent.atomic.AtomicLong(0);
            // P5-10：上一次正文检查点的时间；断线后前端靠它重放已生成内容
            java.util.concurrent.atomic.AtomicLong lastContentCheckpointAt = new java.util.concurrent.atomic.AtomicLong(0);
            // P4-07：本轮 token 累计；用量只落库一次，避免多处收尾重复累加
            java.util.concurrent.atomic.AtomicLong turnPromptTokens = new java.util.concurrent.atomic.AtomicLong(0);
            java.util.concurrent.atomic.AtomicLong turnCompletionTokens = new java.util.concurrent.atomic.AtomicLong(0);
            // P5-10-3：本轮已完成的模型轮次，用于向前端展示轮次预算
            AtomicInteger turnRounds = new AtomicInteger(0);
            AtomicBoolean usagePersisted = new AtomicBoolean(false);
            StringBuilder traceThinking = new StringBuilder();
            List<Map<String, Object>> traceTools = new ArrayList<>();
            List<Map<String, Object>> traceSources = new ArrayList<>();
            List<Map<String, Object>> traceChanges = new ArrayList<>();
            // 第六十八批 A1：本轮任务计划的最新快照，随助手消息 trace 落库
            java.util.concurrent.atomic.AtomicReference<Map<String, Object>> tracePlan =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // P5-13：交付状态独立于 Run 生命周期，随消息轨迹与 run.resultJson 持久化
            java.util.concurrent.atomic.AtomicReference<Map<String, Object>> traceCompletion =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // P5-05：最近一批变更文件诊断，随消息轨迹落库供历史会话还原
            java.util.concurrent.atomic.AtomicReference<Map<String, Object>> traceDiagnostics =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // P5-06：最近一次项目验证结果，随消息轨迹落库供历史会话与断线重放还原
            java.util.concurrent.atomic.AtomicReference<Map<String, Object>> traceVerify =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> runResultJson =
                    new java.util.concurrent.atomic.AtomicReference<>();
            try {
                agentRunService.start(runId);
                //先告诉前端 sessionId，前端用它把后续流式消息挂到正确的会话上
                sendEvent(emitter, "session_id", String.valueOf(chatSessionId));
                sendEventQuietly(emitter, "run_id", String.valueOf(runId), clientDisconnected);
                //本轮 id 用于按轮次撤回文件改动；失败也不影响主流程
                sendEventQuietly(emitter, "turn_id", turnId, clientDisconnected);

                //截图：先交给视觉模型转写成文字，主对话模型再基于这段文字推理
                String imageContext = "";
                if (finalAttachmentIds != null && !finalAttachmentIds.isEmpty() && visionService.isEnabled()) {
                    try {
                        List<ChatAttachmentService.ImageInput> images =
                                chatAttachmentService.buildImageInputs(userId, finalAttachmentIds);
                        if (!images.isEmpty()) {
                            sendEventQuietly(emitter, "thinking",
                                    "{\"thought\":\"正在识别截图…\"}", clientDisconnected);
                            try {
                                imageContext = visionService.describeImages(realApiKey, images, prompt);
                                //补发收尾事件：否则前端思考链会一直停在「正在识别截图…」
                                sendEventQuietly(emitter, "thinking",
                                        imageContext.isBlank()
                                                ? "{\"thought\":\"截图识别无结果，已按纯文本处理\"}"
                                                : "{\"thought\":\"截图识别完成\"}",
                                        clientDisconnected);
                            } catch (Exception e) {
                                //带异常类型，便于区分超时 / 鉴权 / 参数错误
                                log.warn("截图识别失败，降级为纯文本对话 [type={}]: {}",
                                        e.getClass().getSimpleName(), e.getMessage());
                                sendEventQuietly(emitter, "thinking",
                                        "{\"thought\":\"截图识别失败，已降级为纯文本\"}", clientDisconnected);
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
                        sendEventQuietly(emitter, "thinking",
                                "{\"thought\":\"已引用历史会话\"}", clientDisconnected);
                    }
                } catch (Exception e) {
                    log.warn("解析点名会话失败，跳过引用: {}", e.getMessage());
                }

                agentService.execute(realApiKey, policy, prompt, finalHistory, userId, projectId, chatSessionId, turnId, useRag, finalFileContext,
                        imageContext,
                        contextSummary,
                        sessionReferenceContext,
                        clientDisconnected,
                        new ReActAgentService.StreamCallback() {
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
                                    sendEvent(emitter, "sources", mapper.writeValueAsString(list));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
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
                                    sendEvent(emitter, "thinking", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
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
                                    sendEvent(emitter, "tool_call", mapper.writeValueAsString(data));
                                    // P4-04：工具调用落事件表，断线后客户端可凭 runId + sinceSeq 重放
                                    Map<String, Object> persisted = new LinkedHashMap<>();
                                    persisted.put("tool", toolName);
                                    persisted.put("args", truncateForTrace(arguments, 1000));
                                    appendRunEvent(runId,
                                            com.capsule.agentkey.entity.AgentRunEvent.EventType.TOOL_CALL,
                                            persisted);
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
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
                                    sendEvent(emitter, "tool_result", mapper.writeValueAsString(data));
                                    // P4-04：工具结果只落摘要，避免整份文件把事件表撑大
                                    Map<String, Object> persisted = new LinkedHashMap<>();
                                    persisted.put("tool", toolName);
                                    persisted.put("success", result.isSuccess());
                                    persisted.put("content", truncateForTrace(result.getContent(), 1000));
                                    persisted.put("error", truncateForTrace(result.getError(), 500));
                                    appendRunEvent(runId,
                                            com.capsule.agentkey.entity.AgentRunEvent.EventType.TOOL_RESULT,
                                            persisted);
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
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
                                    sendEvent(emitter, "file_change", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
                                    log.error("发送 file_change 事件失败（客户端可能已断开）", e);
                                }
                            }

                            @Override
                            public void onPlan(List<WorkspaceContext.PlanStep> steps) {
                                try {
                                    // 下发任务计划：前端凭 plan 事件实时刷新计划面板
                                    List<Map<String, Object>> list = new ArrayList<>();
                                    for (WorkspaceContext.PlanStep step : steps) {
                                        Map<String, Object> item = new HashMap<>();
                                        item.put("title", step.title());
                                        item.put("status", step.status());
                                        list.add(item);
                                    }
                                    Map<String, Object> data = new HashMap<>();
                                    data.put("steps", list);
                                    data.put("updatedAt", System.currentTimeMillis());
                                    tracePlan.set(data);
                                    sendEvent(emitter, "plan", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
                                    log.error("发送 plan 事件失败（客户端可能已断开）", e);
                                }
                            }

                            @Override
                            public void onApprovalRequired(String approvalId, String tool,
                                                           com.capsule.agentkey.dto.ApprovalPreview preview,
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
                                    sendEvent(emitter, "approval_required", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
                                    log.error("发送 approval_required 事件失败（客户端可能已断开）", e);
                                }
                            }

                            @Override
                            public void onApprovalResolved(String approvalId, String decision,
                                                           String source, String batchId) {
                                agentRunService.resumeAfterApproval(runId, decision, source, batchId);
                                try {
                                    Map<String, Object> data = new HashMap<>();
                                    data.put("approvalId", approvalId);
                                    data.put("decision", decision);
                                    // P5-10-2：前端据此展示来源；批量审批带同一 batchId
                                    data.put("source", source);
                                    if (batchId != null) data.put("batchId", batchId);
                                    sendEvent(emitter, "approval_resolved", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
                                    log.error("发送 approval_resolved 事件失败（客户端可能已断开）", e);
                                }
                            }

                            @Override
                            public void onUsage(long promptTokens, long completionTokens) {
                                // P4-07：模型多次调用会分多次上报，这里只累加，终态时统一落库
                                turnPromptTokens.addAndGet(promptTokens);
                                turnCompletionTokens.addAndGet(completionTokens);
                                // P5-10-3：每轮实时下发预算进度，前端不需要等到结束才知道用了多少
                                turnRounds.incrementAndGet();
                                sendUsageEvent(emitter, turnPromptTokens, turnCompletionTokens,
                                        turnRounds.get(), policy.reasoning().maxToolRounds(), traceStartedAt);
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
                                    sendEvent(emitter, "content", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
                                    log.error("写入 content 失败（客户端可能已断开）", e);
                                }
                                // P5-10：按固定间隔把「累计正文」落成检查点，
                                // 前端断线/刷新后可凭 runId 重放到断点位置，而不是整段重答
                                long last = lastContentCheckpointAt.get();
                                if ((last == 0 || now - last >= CONTENT_CHECKPOINT_INTERVAL_MS)
                                        && lastContentCheckpointAt.compareAndSet(last, now)) {
                                    Map<String, Object> checkpoint = new LinkedHashMap<>();
                                    checkpoint.put("text", truncateForTrace(fullResponse.toString(), CONTENT_CHECKPOINT_LIMIT));
                                    checkpoint.put("length", fullResponse.length());
                                    appendRunEvent(runId,
                                            com.capsule.agentkey.entity.AgentRunEvent.EventType.MODEL_CHUNK,
                                            checkpoint);
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
                                    runResultJson.set(mapper.writeValueAsString(data));
                                    appendRunEvent(runId,
                                            com.capsule.agentkey.entity.AgentRunEvent.EventType.COMPLETION,
                                            data);
                                    sendEvent(emitter, "completion", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
                                    log.error("发送 completion 事件失败（客户端可能已断开）", e);
                                }
                            }

                            @Override
                            public void onDiagnostics(
                                    com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics) {
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
                                    var ctx = com.capsule.agentkey.tool.WorkspaceContext.get();
                                    data.put("fileVersion", ctx == null ? "" : ctx.diagnosticsFileVersion());
                                    List<Map<String, Object>> items = new ArrayList<>();
                                    for (com.capsule.agentkey.diagnostics.Diagnostic item
                                            : diagnostics.diagnostics()) {
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
                                    appendRunEvent(runId,
                                            com.capsule.agentkey.entity.AgentRunEvent.EventType.DIAGNOSTICS,
                                            data);
                                    sendEvent(emitter, "diagnostics", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
                                    log.error("发送 diagnostics 事件失败（客户端可能已断开）", e);
                                }
                            }

                            @Override
                            public void onVerify(com.capsule.agentkey.verify.VerifyResult verify) {
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
                                    for (com.capsule.agentkey.verify.VerifyResult.CommandOutcome item
                                            : verify.commands()) {
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
                                    appendRunEvent(runId,
                                            com.capsule.agentkey.entity.AgentRunEvent.EventType.VERIFY,
                                            data);
                                    sendEvent(emitter, "verify", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
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
                                    appendRunEvent(runId,
                                            com.capsule.agentkey.entity.AgentRunEvent.EventType.NO_PROGRESS,
                                            data);
                                    sendEvent(emitter, "no_progress", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
                                    log.error("发送 no_progress 事件失败（客户端可能已断开）", e);
                                }
                            }

                            @Override
                            public void onComplete() {
                                //客户端已断开 → 不发 done 事件也不持久化半截回复，避免污染历史
                                if (clientDisconnected.get()) {
                                    persistRunUsage(runId, turnPromptTokens, turnCompletionTokens, usagePersisted);
                                    // 断开时补写最后一个正文检查点，把最后不到 1s 的内容也保住
                                    if (fullResponse.length() > 0) {
                                        Map<String, Object> checkpoint = new LinkedHashMap<>();
                                        checkpoint.put("text", truncateForTrace(fullResponse.toString(), CONTENT_CHECKPOINT_LIMIT));
                                        checkpoint.put("length", fullResponse.length());
                                        appendRunEvent(runId,
                                                com.capsule.agentkey.entity.AgentRunEvent.EventType.MODEL_CHUNK,
                                                checkpoint);
                                    }
                                    markRunCancelled(runId, "client disconnected");
                                    log.info("客户端已断开，丢弃未完成回复 [sessionId={}]", chatSessionId);
                                    //仍要结束异步请求，否则要挂到超时（默认 180s）才释放
                                    emitter.complete();
                                    return;
                                }
                                try {
                                    sendUsageEvent(emitter, turnPromptTokens, turnCompletionTokens,
                                            turnRounds.get(), policy.reasoning().maxToolRounds(), traceStartedAt);
                                    try {
                                        sendEvent(emitter, "done", "[DONE]");
                                    } catch (IOException e) {
                                        clientDisconnected.set(true);
                                        throw e;
                                    }
                                    if (fullResponse.length() > 0) {
                                        // 轨迹只随完整回答落库；断线丢弃半截回复时也不留残缺轨迹
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
                                        chatService.saveMessage(userId, chatSessionId,
                                                ChatMessage.MessageRole.ASSISTANT,
                                                fullResponse.toString(), null,
                                                mapper.writeValueAsString(trace));
                                        // 批次 B2：会话级自动记忆——每次助手消息后判断是否到了触发点
                                        try {
                                            memoryService.onAssistantMessage(
                                                    chatSessionId, userId, realApiKey, policy.model());
                                        } catch (Exception ignore) {
                                            // 提炼失败不影响主流程，Service 内部已经 log
                                        }
                                    }
                                } catch (Exception e) {
                                    log.error("SSE 完成处理失败", e);
                                } finally {
                                    persistRunUsage(runId, turnPromptTokens, turnCompletionTokens, usagePersisted);
                                    Map<String, Object> completion = traceCompletion.get();
                                    boolean agentCancelled = completion != null
                                            && "CANCELLED".equals(completion.get("reason"));
                                    if (clientDisconnected.get() || agentCancelled) {
                                        markRunCancelled(runId, agentCancelled ? "agent cancelled" : "client disconnected");
                                    } else {
                                        markRunSucceeded(runId, runResultJson.get());
                                    }
                                    emitter.complete();
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                persistRunUsage(runId, turnPromptTokens, turnCompletionTokens, usagePersisted);
                                boolean budgetExceeded = t instanceof com.capsule.agentkey.exception.TokenBudgetExceededException;
                                markRunFailed(runId,
                                        budgetExceeded ? "TOKEN_BUDGET_EXCEEDED" : "AGENT_EXECUTION_FAILED",
                                        t.getMessage());
                                //真实异常只进日志；下发给用户的是一句能看懂的话
                                log.error("Agent 执行错误", t);
                                try {
                                    sendEvent(emitter, "error",
                                            budgetExceeded ? t.getMessage() : "这次回答没有完成，请稍后再试一次");
                                } catch (Exception ignore) {
                                    //连接已断，无需再发
                                } finally {
                                    emitter.complete();
                                }
                            }
                        });
            } catch (Exception e) {
                persistRunUsage(runId, turnPromptTokens, turnCompletionTokens, usagePersisted);
                boolean budgetExceeded = e instanceof com.capsule.agentkey.exception.TokenBudgetExceededException;
                markRunFailed(runId,
                        budgetExceeded ? "TOKEN_BUDGET_EXCEEDED" : "AGENT_EXECUTION_FAILED",
                        e.getMessage());
                log.error("流式聊天执行异常", e);
                try {
                    sendEvent(emitter, "error",
                            budgetExceeded ? e.getMessage() : "这次回答没有完成，请稍后再试一次");
                } catch (Exception ignore) {
                    //连接已断
                } finally {
                    emitter.complete();
                }
            } finally {
                generationRegistry.unregister(chatSessionId, clientDisconnected);
            }
        });

        return emitter;
    }

    /** 运行落库失败不能覆盖原始聊天错误，也不能把终态再次迁移。 */
    private void markRunSucceeded(Long runId, String resultJson) {
        try {
            agentRunService.findById(runId).ifPresent(run -> {
                if (!com.capsule.agentkey.entity.AgentRun.isTerminal(run.getState())) {
                    agentRunService.succeed(runId, resultJson);
                }
            });
        } catch (Exception e) {
            log.error("更新运行成功状态失败: runId={}", runId, e);
        }
    }

    private void markRunFailed(Long runId, String code, String message) {
        try {
            agentRunService.findById(runId).ifPresent(run -> {
                if (!com.capsule.agentkey.entity.AgentRun.isTerminal(run.getState())) {
                    agentRunService.fail(runId, code, message);
                }
            });
        } catch (Exception e) {
            log.error("更新运行失败状态失败: runId={}", runId, e);
        }
    }

    private void markRunCancelled(Long runId, String reason) {
        try {
            agentRunService.findById(runId).ifPresent(run -> {
                if (!com.capsule.agentkey.entity.AgentRun.isTerminal(run.getState())) {
                    agentRunService.cancel(runId, reason);
                }
            });
        } catch (Exception e) {
            log.error("更新运行取消状态失败: runId={}", runId, e);
        }
    }

    /** P4-07：用量只落库一次；失败只告警，不影响对话结果 */
    private void persistRunUsage(Long runId, AtomicLong prompt, AtomicLong completion,
                                 AtomicBoolean once) {
        if (!once.compareAndSet(false, true)) {
            return;
        }
        try {
            agentRunService.recordUsage(runId, prompt.get(), completion.get());
        } catch (Exception e) {
            log.warn("记录 token 用量失败 [runId={}]: {}", runId, e.getMessage());
        }
    }

    /**
     * P4-07 / P5-10-3：把本轮用量与预算进度下发给前端。
     * round / maxRounds / maxTokens / elapsedMs / maxElapsedMs 为 P5-10-3 新增字段，
     * 旧前端忽略即可；旧后端不发这些字段时前端按「不限/—」降级。
     */
    private void sendUsageEvent(SseEmitter emitter, AtomicLong prompt, AtomicLong completion,
                                int round, int maxRounds, long startedAt) {
        long p = prompt.get();
        long c = completion.get();
        if (p <= 0 && c <= 0) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("promptTokens", p);
            data.put("completionTokens", c);
            data.put("totalTokens", p + c);
            data.put("round", round);
            data.put("maxRounds", maxRounds);
            data.put("maxTokens", maxTokensPerRun);
            data.put("elapsedMs", Math.max(0L, System.currentTimeMillis() - startedAt));
            data.put("maxElapsedMs", maxSecondsPerRun > 0 ? maxSecondsPerRun * 1000L : 0L);
            sendEvent(emitter, "usage", mapper.writeValueAsString(data));
        } catch (Exception e) {
            log.debug("下发 usage 事件失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /** 运行事件落库失败不能中断聊天流，只记录告警 */
    private void appendRunEvent(Long runId,
                                com.capsule.agentkey.entity.AgentRunEvent.EventType type,
                                Map<String, Object> payload) {
        try {
            agentRunService.appendEvent(runId, type, payload);
        } catch (Exception e) {
            log.warn("写入运行事件失败 [runId={}, type={}]: {}", runId, type, e.getMessage());
        }
    }

    /**
     * P4-04：断线续传入口。
     * 客户端带着最后收到的 seq 重连，只取增量事件；同时拿到运行终态，
     * 若运行已结束，可直接用现有消息接口拉取完整回答。
     */
    @GetMapping("/runs/{runId}/events")
    public ApiResponse<Map<String, Object>> runEvents(@PathVariable Long runId,
                                                      @RequestParam(value = "sinceSeq", required = false) Integer sinceSeq) {
        Long userId = getCurrentUserId();
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
        result.put("events", events);
        return ApiResponse.success(result);
    }

    /**
     * P5-10：刷新恢复入口。
     * 页面刷新后前端不知道当前还有哪一轮在跑，先查这里拿到 runId，再用上面的接口重放事件。
     */
    @GetMapping("/runs/running")
    public ApiResponse<List<Map<String, Object>>> runningRuns() {
        Long userId = getCurrentUserId();
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
     * 停止生成：把该会话的生成标记置位。
     * 前端会 abort 自己的 SSE 请求，但长命令执行期间没有 SSE 写出、后端感知不到断连，
     * 所以需要这个接口让 ReAct 循环与运行中的子进程尽快停下。
     */
    @PostMapping("/chat/stop")
    public ApiResponse<Void> stopGeneration(@RequestParam(value = "sessionId", required = false) Long sessionId) {
        Long userId = getCurrentUserId();
        if (sessionId != null) {
            //校验归属，避免用别人的 sessionId 打断别人的生成
            chatService.getOwnedSession(sessionId, userId);
            generationRegistry.cancel(sessionId);
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
    public ApiResponse<Void> respondApproval(@PathVariable String approvalId,
                                             @RequestBody ApprovalDecisionRequest request) {
        Long userId = getCurrentUserId();
        boolean remember = "SESSION".equalsIgnoreCase(request.getRemember());
        if (!toolApprovalRegistry.resolve(approvalId, userId, request.getDecision(), remember)) {
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
    public ApiResponse<Map<String, Object>> respondApprovalBatch(@RequestBody ApprovalBatchRequest request) {
        Long userId = getCurrentUserId();
        Long sessionId = request.getSessionId();
        if (sessionId == null) {
            throw new BusinessException("缺少会话 id");
        }
        // 先校验会话归属，避免拿别人的 sessionId 批量批准
        chatService.getOwnedSession(sessionId, userId);
        com.capsule.agentkey.service.ToolApprovalRegistry.BatchResult result =
                toolApprovalRegistry.resolveBatch(userId, sessionId, request.getApprovalIds());
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
    public ApiResponse<List<ApprovalHistoryItem>> sessionApprovals(@PathVariable Long sessionId) {
        Long userId = getCurrentUserId();
        chatService.getOwnedSession(sessionId, userId);
        List<ApprovalHistoryItem> items = toolApprovalRegistry.history(sessionId, userId).stream()
                .map(saved -> new ApprovalHistoryItem(
                        saved.getApprovalId(),
                        saved.getToolName(),
                        saved.getRisk(),
                        saved.getState(),
                        saved.getDecisionSource(),
                        saved.getDecisionTime(),
                        saved.getCreateTime(),
                        saved.isRememberSession()))
                .toList();
        return ApiResponse.success(items);
    }

    /**
     * 发送 SSE 事件。
     * SseEmitter 内部已按 SSE 协议格式化多行 data，无需像手写流那样逐行加 "data: " 前缀。
     */
    private void sendEvent(SseEmitter emitter, String event, String data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data == null ? "" : data));
    }

    /** 轨迹落库前截断：工具返回可能是整份文件，避免把 chat_message 撑到几 MB */
    private static String truncateForTrace(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max) + "…（已截断）";
    }

    private static boolean equalsIgnoreCase(Object value, String expected) {
        return value != null && expected != null && expected.equalsIgnoreCase(value.toString());
    }

    /** 静默发送：失败不抛异常（用于错误已经发生、尽力通知前端的场景） */
    private void sendEventQuietly(SseEmitter emitter, String event, String data, AtomicBoolean disconnectedFlag) {
        try {
            sendEvent(emitter, event, data);
        } catch (Exception e) {
            disconnectedFlag.set(true);
            log.warn("SSE 事件发送失败 [{}]: {}", event, e.getMessage());
        }
    }

    /**
     * P4-04：自动带自增事件 id 的 SseEmitter。
     *
     * <p>SSE 协议允许客户端用 Last-Event-ID / 自身记录的 seq 续传；
     * 这里统一在发送时注入 id，业务侧不需要逐个事件手工编号。
     * 心跳用注释帧（不会被当成消息），也不占用事件 id。
     */
    private static final class TrackedEmitter extends SseEmitter {

        private final AtomicLong eventId = new AtomicLong();

        private TrackedEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            super.send(builder.id(String.valueOf(eventId.incrementAndGet())));
        }

        /** 注释帧保活：部分网关/代理会丢弃注释，但不会影响事件 id 的连续性 */
        private void heartbeat() throws IOException {
            super.send(SseEmitter.event().comment("ping"));
        }
    }

    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> listSessions(
            @RequestParam(value = "projectId", required = false) Long projectId) {
        Long userId = getCurrentUserId();
        List<ChatSession> sessions = chatService.listSessions(
                userId, projectService.resolveProjectId(userId, projectId));
        // P5-12-2：带上每个会话最近一轮的状态，侧栏据此显示状态点
        Map<Long, String> lastStates = agentRunService.lastRunStatesBySession(
                userId, sessions.stream().map(ChatSession::getId).toList());
        List<Map<String, Object>> list = sessions.stream().map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("projectId", s.getProjectId());
            item.put("title", s.getTitle());
            item.put("model", s.getModel());
            item.put("reasoning", s.getReasoning());
            item.put("permission", s.getPermission());
            item.put("createTime", s.getCreateTime());
            item.put("updateTime", s.getUpdateTime());
            item.put("lastRunState", lastStates.get(s.getId()));
            return item;
        }).toList();
        return ApiResponse.success(list);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessage>> getMessages(@PathVariable Long sessionId) {
        return ApiResponse.success(chatService.getHistory(sessionId, getCurrentUserId()));
    }

    /** 消息全文搜索：在当前用户的会话正文里找关键字，按会话归并返回命中片段 */
    @GetMapping("/messages/search")
    public ApiResponse<List<MessageSearchHit>> searchMessages(
            @RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.success(chatService.searchMessages(getCurrentUserId(), keyword));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId) {
        chatService.deleteSession(sessionId, getCurrentUserId());
        return ApiResponse.success(null);
    }

    /**
     * 第五十四批：前端侧边栏"会话追踪"面板的聚合统计接口。
     * 数据源：ChatMessage.trace（每次 assistant 消息的执行轨迹 JSON）。
     * 不新建表——按需 parse + 聚合。
     */
    @GetMapping("/sessions/{sessionId}/stats")
    public ApiResponse<ChatSessionStats> sessionStats(@PathVariable Long sessionId) {
        Long userId = getCurrentUserId();
        ChatSessionStats stats = chatService.aggregateStats(sessionId, userId);
        // P4-07：token 用量来自 agent_run，与 trace 聚合分开merge
        stats.setUsage(agentRunService.usageBySession(sessionId, userId));
        return ApiResponse.success(stats);
    }

    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<Void> renameSession(@PathVariable Long sessionId,
                                           @RequestBody RenameSessionRequest request) {
        chatService.renameSession(sessionId, getCurrentUserId(), request == null ? null : request.title());
        return ApiResponse.success(null);
    }

    public record RenameSessionRequest(String title) {
    }

    /** 会话头切换工作权限：立即持久化到会话，避免切走再切回被旧值覆盖 */
    @PatchMapping("/sessions/{sessionId}/permission")
    public ApiResponse<Void> updateSessionPermission(@PathVariable Long sessionId,
                                                     @RequestBody SessionPermissionRequest request) {
        chatService.updateSessionPermission(sessionId, getCurrentUserId(),
                request == null ? null : request.permission());
        return ApiResponse.success(null);
    }

    public record SessionPermissionRequest(String permission) {
    }

    @PostMapping("/chat/attachment")
    public ApiResponse<Map<String, Object>> uploadChatAttachment(
            //MultipartFile 是 Spring 的封装接口，文件上传对象，提供 getInputStream(), getBytes(), transferTo() 等方法
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "description", required = false) String description) {

        Long userId = getCurrentUserId();//从security上下文获取当前登录的userid

        if (file == null || file.isEmpty()) {
            log.warn("附件上传失败: 文件为空 [userId={}]", userId);
            return ApiResponse.fail(ErrorCode.PARAM_INVALID, "请选择要上传的文件");
        }
        try {
            ChatAttachment att = chatAttachmentService.uploadAttachment(userId, file, description);
            Map<String, Object> map = new HashMap<>();
            map.put("id", att.getId());
            map.put("fileName", att.getFileName());
            map.put("fileType", att.getFileType());
            map.put("fileSize", att.getFileSize());

            String preview = "";
            if (att.getContentText() != null && !att.getContentText().isEmpty()) {
                int len = Math.min(200, att.getContentText().length());
                preview = att.getContentText().substring(0, len) + (att.getContentText().length() > 200 ? "..." : "");
            }
            map.put("preview", preview);

            return ApiResponse.success(map);
        } catch (BusinessException e) {
            // 直接透传业务异常，避免被全局兜底的 500 覆盖
            log.warn("附件上传业务异常 [userId={}]: {}", userId, e.getMessage());
            return ApiResponse.fail(e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.error("上传聊天附件系统异常 [userId={}]", userId, e);
            return ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "上传失败: " + e.getMessage());
        }
    }

    /** P5-11-2：当前用户的体验指标；Run/审批使用最近 100 条窗口，工具 Timer 为进程累计。 */
    @GetMapping("/metrics/experience")
    public ApiResponse<AgentExperienceMetricsService.ExperienceMetrics> experienceMetrics() {
        return ApiResponse.success(experienceMetricsService.getMetrics(getCurrentUserId()));
    }

    /**
     * D3 可观测性前端面板：返回当前应用累计的 Agent 工具调用统计。
     * 聚合 MeterRegistry 里的 agent.tool.execution Timer，按 tool 分组输出调用次数、P50/P95/P99 耗时（毫秒）、成功率。
     * 前端工作台"监控"tab 直接调这个 endpoint 展示。
     */
    @GetMapping("/metrics")
    public ApiResponse<Map<String, Object>> metrics(
            @RequestParam(value = "projectId", required = false) Long projectId) {
        Long userId = getCurrentUserId();
        if (projectId != null) {
            projectService.getOwned(userId, projectId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, Object>> tools = new LinkedHashMap<>();
        long totalCalls = 0;
        long totalSuccess = 0;
        long totalFail = 0;

        for (var timer : meterRegistry.find("agent.tool.execution").timers()) {
            // 无归属标签的旧指标及其他用户/项目的指标一律不可见
            if (!userId.toString().equals(timer.getId().getTag("userId"))) continue;
            if (projectId != null && !projectId.toString().equals(timer.getId().getTag("projectId"))) continue;
            String toolName = timer.getId().getTag("tool");
            if (toolName == null) toolName = "(unknown)";
            String source = timer.getId().getTag("source");
            String outcome = timer.getId().getTag("result");

            totalCalls += timer.count();
            if ("success".equals(outcome)) totalSuccess += timer.count();
            else totalFail += timer.count();

            Map<String, Object> bucket = tools.computeIfAbsent(toolName, k -> new LinkedHashMap<>());
            bucket.put("calls", ((Number) bucket.getOrDefault("calls", 0L)).longValue() + timer.count());
            bucket.put("count", ((Number) bucket.getOrDefault("count", 0L)).longValue() + timer.count());
            bucket.put("p50Ms", Math.round(timer.percentile(0.5, TimeUnit.MILLISECONDS)));
            bucket.put("p95Ms", Math.round(timer.percentile(0.95, TimeUnit.MILLISECONDS)));
            bucket.put("p99Ms", Math.round(timer.percentile(0.99, TimeUnit.MILLISECONDS)));
            bucket.put("maxMs", Math.round(timer.max(TimeUnit.MILLISECONDS)));
            // source tag 合并（可能有多个 source 打到同一个 tool）
            @SuppressWarnings("unchecked")
            java.util.Set<String> srcs = (java.util.Set<String>) bucket.computeIfAbsent("sources", k -> new java.util.LinkedHashSet<String>());
            if (source != null) srcs.add(source);
            // result 分桶累计（success/fail）
            @SuppressWarnings("unchecked")
            Map<String, Long> outcomes = (Map<String, Long>) bucket.computeIfAbsent("outcomes", k -> new HashMap<String, Long>());
            outcomes.merge(outcome == null ? "unknown" : outcome, timer.count(), Long::sum);
        }

        // 计算成功率（每个 tool 自己算）
        for (var entry : tools.entrySet()) {
            Map<String, Object> b = entry.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Long> outcomes = (Map<String, Long>) b.get("outcomes");
            long succ = outcomes.getOrDefault("success", 0L);
            long tot = succ + outcomes.getOrDefault("fail", 0L) + outcomes.getOrDefault("error", 0L) + outcomes.getOrDefault("unknown", 0L);
            b.put("successRate", tot == 0 ? 0.0 : Math.round(succ * 10000.0 / tot) / 100.0);
        }

        result.put("generatedAt", java.time.Instant.now().toString());
        result.put("userId", userId);
        if (projectId != null) result.put("projectId", projectId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCalls", totalCalls);
        summary.put("uniqueTools", tools.size());
        summary.put("successCalls", totalSuccess);
        summary.put("failCalls", totalFail);
        summary.put("successRate", totalCalls == 0 ? 0.0 : Math.round(totalSuccess * 10000.0 / totalCalls) / 100.0);
        result.put("summary", summary);
        result.put("tools", tools);

        return ApiResponse.success(result);
    }
}
