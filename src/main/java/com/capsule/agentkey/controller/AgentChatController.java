package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.dto.ApprovalDecisionRequest;
import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.ChatRequest;
import com.capsule.agentkey.dto.ChatSessionStats;
import com.capsule.agentkey.dto.MessageSearchHit;
import com.capsule.agentkey.entity.ChatAttachment;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ContextSummaryService contextSummaryService;
    private final AgentPolicyResolver policyResolver;
    /** 第六十八批 A2：工具审批登记表 */
    private final com.capsule.agentkey.service.ToolApprovalRegistry toolApprovalRegistry;
    /** 批次 B2：会话级自动记忆 */
    private final com.capsule.agentkey.service.SessionMemoryExtractionService memoryService;
    private final Executor agentStreamExecutor;

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

        //Nginx 默认会缓冲 SSE，置 no 让事件实时下发
        response.setHeader("X-Accel-Buffering", "no");

        // SSE 不设固定总时长：多轮审批可能超过 180 秒，审批本身由 ToolApprovalRegistry 独立超时。
        // 客户端断开仍通过写出异常或 /chat/stop 置位，不会放任工具继续执行。
        SseEmitter emitter = new SseEmitter(0L);

        //B3：断连标记——任一 SSE 写出抛异常即置位，ReAct 循环在每轮开始前检查该标记并中止
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);

        emitter.onTimeout(() -> {
            clientDisconnected.set(true);
            log.warn("SSE 超时，中止 LLM 调用 [userId={}]", userId);
        });
        emitter.onError(t -> {
            clientDisconnected.set(true);
            log.warn("SSE 连接错误 [userId={}]: {}", userId, t.getMessage());
        });

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
            StringBuilder traceThinking = new StringBuilder();
            List<Map<String, Object>> traceTools = new ArrayList<>();
            List<Map<String, Object>> traceSources = new ArrayList<>();
            List<Map<String, Object>> traceChanges = new ArrayList<>();
            // 第六十八批 A1：本轮任务计划的最新快照，随助手消息 trace 落库
            java.util.concurrent.atomic.AtomicReference<Map<String, Object>> tracePlan =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // 第六十八批 A3：本轮执行 id，本轮所有文件改动共享，前端据此「撤回本轮」
            String turnId = "turn-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            try {
                //先告诉前端 sessionId，前端用它把后续流式消息挂到正确的会话上
                sendEvent(emitter, "session_id", String.valueOf(chatSessionId));
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
                            public void onApprovalResolved(String approvalId, String decision) {
                                try {
                                    Map<String, Object> data = new HashMap<>();
                                    data.put("approvalId", approvalId);
                                    data.put("decision", decision);
                                    sendEvent(emitter, "approval_resolved", mapper.writeValueAsString(data));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
                                    log.error("发送 approval_resolved 事件失败（客户端可能已断开）", e);
                                }
                            }

                            @Override
                            public void onChunk(String chunk) {
                                // 第五十六批：记录回答阶段起始时间
                                if (firstChunkAt.get() == 0) firstChunkAt.set(System.currentTimeMillis());
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
                            }

                            @Override
                            public void onComplete() {
                                //客户端已断开 → 不发 done 事件也不持久化半截回复，避免污染历史
                                if (clientDisconnected.get()) {
                                    log.info("客户端已断开，丢弃未完成回复 [sessionId={}]", chatSessionId);
                                    //仍要结束异步请求，否则要挂到超时（默认 180s）才释放
                                    emitter.complete();
                                    return;
                                }
                                try {
                                    sendEvent(emitter, "done", "[DONE]");
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
                                    emitter.complete();
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                //真实异常只进日志；下发给用户的是一句能看懂的话
                                log.error("Agent 执行错误", t);
                                try {
                                    sendEvent(emitter, "error", "这次回答没有完成，请稍后再试一次");
                                } catch (Exception ignore) {
                                    //连接已断，无需再发
                                } finally {
                                    emitter.complete();
                                }
                            }
                        });
            } catch (Exception e) {
                log.error("流式聊天执行异常", e);
                try {
                    sendEvent(emitter, "error", "这次回答没有完成，请稍后再试一次");
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

    @GetMapping("/sessions")
    public ApiResponse<List<ChatSession>> listSessions(
            @RequestParam(value = "projectId", required = false) Long projectId) {
        //List<ChatSession>规定了查询结果是列表
        Long userId = getCurrentUserId();
        return ApiResponse.success(chatService.listSessions(
                userId, projectService.resolveProjectId(userId, projectId)));
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
        return ApiResponse.success(chatService.aggregateStats(sessionId, getCurrentUserId()));
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
