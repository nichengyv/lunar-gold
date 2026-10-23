package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.ChatRequest;
import com.capsule.agentkey.entity.ChatAttachment;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.service.ApiKeyService;
import com.capsule.agentkey.service.ChatAttachmentService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.DocumentService;
import com.capsule.agentkey.service.ProjectService;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.tool.ToolResult;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
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
    private final Executor agentStreamExecutor;

    public AgentChatController(ApiKeyService apiKeyService,
                               ReActAgentService agentService,
                               ChatService chatService,
                               ChatAttachmentService chatAttachmentService,
                               ProjectService projectService,
                               @Qualifier("agentStreamExecutor") Executor agentStreamExecutor) {
        this.apiKeyService = apiKeyService;
        this.agentService = agentService;
        this.chatService = chatService;
        this.chatAttachmentService = chatAttachmentService;
        this.projectService = projectService;
        this.agentStreamExecutor = agentStreamExecutor;
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

        //LLM 流式响应可能持续几十秒；超时后容器会自动 complete 并触发 onTimeout 回调
        SseEmitter emitter = new SseEmitter(180_000L);

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
        if (finalSessionId != null) {
            //老会话：以会话自身的项目归属为准，避免切项目后把别的项目资料混进上下文
            effectiveProjectId = chatService.getOwnedSession(finalSessionId, userId).getProjectId();
        } else {
            effectiveProjectId = projectService.resolveProjectId(userId, request.getProjectId());
            String title;
            if (prompt != null && !prompt.isEmpty()) {
                //B6：按 Unicode 码点截断标题，避免 substring 按 UTF-16 切代理对字符成乱码
                int cp = Math.min(20, prompt.codePointCount(0, prompt.length()));
                title = prompt.substring(0, prompt.offsetByCodePoints(0, cp));
            } else {
                title = "文件分析";
            }
            ChatSession session = chatService.createSession(userId, effectiveProjectId, title);
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

        //———— 执行阶段：提交到独立线程池，Controller 立即 return emitter 释放 Tomcat 线程 ————
        agentStreamExecutor.execute(() -> {
            StringBuilder fullResponse = new StringBuilder();
            try {
                //先告诉前端 sessionId，前端用它把后续流式消息挂到正确的会话上
                sendEvent(emitter, "session_id", String.valueOf(chatSessionId));

                agentService.execute(realApiKey, prompt, finalHistory, userId, projectId, useRag, finalFileContext,
                        clientDisconnected,
                        new ReActAgentService.StreamCallback() {
                            @Override
                            public void onSources(List<DocumentService.Source> sources) {
                                try {
                                    //只下发用户能理解的三项：资料 id、文件名、相关度
                                    List<Map<String, Object>> list = sources.stream()
                                            .map(s -> {
                                                Map<String, Object> item = new HashMap<String, Object>();
                                                item.put("id", s.documentId());
                                                item.put("name", s.fileName());
                                                item.put("score", Math.round(s.score() * 100) / 100.0);
                                                return item;
                                            })
                                            .toList();
                                    sendEvent(emitter, "sources", mapper.writeValueAsString(list));
                                } catch (IOException e) {
                                    clientDisconnected.set(true);
                                    log.error("发送 sources 事件失败（客户端可能已断开）", e);
                                }
                            }

                            @Override
                            public void onThinking(String thought) {
                                try {
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
                            public void onChunk(String chunk) {
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
                                        chatService.saveMessage(userId, chatSessionId,
                                                ChatMessage.MessageRole.ASSISTANT,
                                                fullResponse.toString(), null);
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
            }
        });

        return emitter;
    }

    /**
     * 发送 SSE 事件。
     * SseEmitter 内部已按 SSE 协议格式化多行 data，无需像手写流那样逐行加 "data: " 前缀。
     */
    private void sendEvent(SseEmitter emitter, String event, String data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data == null ? "" : data));
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

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId) {
        chatService.deleteSession(sessionId, getCurrentUserId());
        return ApiResponse.success(null);
    }

    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<Void> renameSession(@PathVariable Long sessionId,
                                           @RequestBody RenameSessionRequest request) {
        chatService.renameSession(sessionId, getCurrentUserId(), request == null ? null : request.title());
        return ApiResponse.success(null);
    }

    public record RenameSessionRequest(String title) {
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
}
