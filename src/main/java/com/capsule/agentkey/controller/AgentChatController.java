package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.ChatRequest;
import com.capsule.agentkey.entity.ChatAttachment;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.entity.ChatSession;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.ApiKeyService;
import com.capsule.agentkey.service.ChatAttachmentService;
import com.capsule.agentkey.service.ChatService;
import com.capsule.agentkey.service.ReActAgentService;
import com.capsule.agentkey.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@Slf4j
public class AgentChatController {

    private final ApiKeyService apiKeyService;
    private final ReActAgentService agentService;
    private final ChatService chatService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatAttachmentService chatAttachmentService;

    public AgentChatController(ApiKeyService apiKeyService,
                               ReActAgentService agentService,
                               ChatService chatService,
                               ChatAttachmentService chatAttachmentService) {
        this.apiKeyService = apiKeyService;
        this.agentService = agentService;
        this.chatService = chatService;
        this.chatAttachmentService = chatAttachmentService;
    }

    //从security上下文取出（princcipal就是取出第一个参数，这里是userid）
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {//instanceof x确认前面是不是x类型
            return (Long) auth.getPrincipal();
        }
        throw new BusinessException("用户未认证");
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamChat(@RequestBody ChatRequest request, HttpServletResponse response) {
        String prompt = request.getPrompt();
        Long sessionId = request.getSessionId();
        boolean useRag = request.isUseRag();
        List<Long> attachmentIds = request.getAttachmentIds();
        //获取当前用户id
        Long userId = getCurrentUserId();

        //规定sse流
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);//告诉客户端这是sse流
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());//指定编码，防止中文乱码
        response.setHeader("Cache-Control", "no-cache");//禁止浏览器缓存事件
        response.setHeader("X-Accel-Buffering", "no");//（Nginx专属）关闭代理缓存/缓冲

        Long finalSessionId = sessionId;
        if (finalSessionId == null) {
            try {
                String title = (prompt != null && !prompt.isEmpty())
                        ? prompt.substring(0, Math.min(20, prompt.length()))
                        : "文件分析";
                //如果没有会话，创建一个
                ChatSession session = chatService.createSession(userId, title);
                finalSessionId = session.getId();
            } catch (Exception e) {
                log.error("创建会话失败", e);
                writeError(response, "创建会话失败: " + e.getMessage());
                return;
            }
        }
        final Long chatSessionId = finalSessionId;

        String fileContext = "";
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            try {
                fileContext = chatAttachmentService.buildSmartContext(userId, attachmentIds, prompt);
            } catch (Exception e) {
                log.warn("构建附件智能上下文失败，降级为普通上下文", e);
                fileContext = chatAttachmentService.buildFileContext(attachmentIds);
            }
        }

        // 先加载历史（此时还不包含当前这条用户消息），再保存当前消息
        // 否则历史里已含当前消息，buildMessages 又会追加一次 userPrompt → 大模型看到重复两遍的用户消息
        List<ChatMessage> history;
        try {
            history = chatService.getHistory(chatSessionId, userId);
        } catch (Exception e) {
            log.error("加载历史消息失败", e);
            history = null;
        }

        try {
            String msgContent = (prompt != null && !prompt.isEmpty()) ? prompt : "[文件提问]";
            //保存用户消息到数据库
            ChatMessage savedMsg = chatService.saveMessage(userId, chatSessionId,
                    ChatMessage.MessageRole.USER, msgContent, null);
            if (attachmentIds != null && !attachmentIds.isEmpty()) {
                chatAttachmentService.linkAttachmentsToMessage(attachmentIds, savedMsg.getId());
            }
        } catch (Exception e) {
            log.error("保存用户消息失败", e);
        }

        try {
            String realApiKey = apiKeyService.getDecryptedKey(userId, "DEEPSEEK");

            OutputStream out = response.getOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            StringBuilder fullResponse = new StringBuilder();

            writeEvent(writer, out, "session_id", String.valueOf(chatSessionId));



            //调用agent大脑开始执行
            //这里用了匿名内部类
            agentService.execute(realApiKey, prompt, history, userId, useRag, fileContext,
                    new ReActAgentService.StreamCallback() {
                //当ai在后台进行推理，反思，或者思考下一步怎么做，会触发这个回调
                        //把思考内容thouhgt打包成一个map对象
                        @Override
                        public void onThinking(String thought) {
                            try {
                                Map<String, String> data = new HashMap<>();
                                data.put("thought", thought);

                                //writeValueAsString把map转为json字符串
                                writeEvent(writer, out, "thinking", mapper.writeValueAsString(data));
                            } catch (IOException e) {
                                log.error("发送 thinking 事件失败", e);
                            }
                        }

                        //监听工具事件
                        //当AI发现靠自己不行，需要外部资料和调用API时，，触发此回调，把工具名，参数，状态打包
                        @Override
                        public void onToolCall(String toolName, String arguments) {
                            try {
                                Map<String, Object> data = new HashMap<>();
                                data.put("tool", toolName);
                                data.put("args", arguments);
                                data.put("status", "calling");
                                //向sse流写入一个事件名为tool_call的数据
                                writeEvent(writer, out, "tool_call", mapper.writeValueAsString(data));
                            } catch (IOException e) {
                                log.error("发送 tool_call 事件失败", e);
                            }
                        }

                        //处理AI调用外部工具后的返回结果，并通知前端
                        //AI调用工具后返回result对象，这里把工具名，是否成功，返回的内容，错误信息，耗时等详细信息打包放进HashMap

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
                                //通过这个方法，发送一个event:"tool_result"的事件给前端
                                writeEvent(writer, out, "tool_result", mapper.writeValueAsString(data));
                            } catch (IOException e) {
                                log.error("发送 tool_result 事件失败", e);
                            }
                        }

                        //接收AI生成的一句完整回复的每个切片，并实时推送给前端（流水一般的文字浮现）
                        //大模型生成文本并非一个一个字，而是一段一段，这里chunk参数就是一段
                        @Override
                        public void onChunk(String chunk) {
                            //fullResponse用于拼接片段，等说完就放进数据库
                            fullResponse.append(chunk);
                            try {
                                //发送一个event:"content"的事件给前端，前端把chunk追加到事件上
                                writeEvent(writer, out, "content", chunk);
                            } catch (IOException e) {
                                log.error("写入 content 失败", e);
                            }
                        }

                        //结局（正常结束）：AI的话说完后触发此方法
                        //1.data:"[DONE]"正式结束SSE流
                        @Override
                        public void onComplete() {
                            try {
                                writeEvent(writer, out, "done", "[DONE]");
                                writer.close();//关闭输出字符流，释放连接资源，防止前端以为服务器还在传输数据

                                //持久化 对话记录
                                //这里判断AI确实说了话，就存数据库
                                if (fullResponse.length() > 0) {
                                    //这里就是把AI的完整回复以“ASSISTANT”的身份，连同用户id和会话id一起保存下来
                                    chatService.saveMessage(userId, chatSessionId,
                                            ChatMessage.MessageRole.ASSISTANT,
                                            fullResponse.toString(), null);
                                }
                            } catch (Exception e) {
                                log.error("SSE 完成处理失败", e);
                            }
                        }

                        //异常结局
                        @Override
                        public void onError(Throwable t) {
                            log.error("Agent 执行错误", t);
                            try {
                                writeEvent(writer, out, "error", t.getMessage());
                                writer.close();
                            } catch (Exception e) {
                                log.error("发送 error 事件失败", e);
                            }
                        }
                    });

        } catch (Exception e) {
            log.error("流式聊天处理失败", e);
            writeError(response, e.getMessage());
        }
    }

    //解决SSE里data内容自动换行的问题
    private void writeEvent(OutputStreamWriter writer, OutputStream out,
                            String event, String data) throws IOException {
        //写入事件类型
        writer.write("event: " + event + "\n");
        if (data != null) {
            //把data按换行符切分成字符串数组

            String[] lines = data.split("\n", -1); // -1 保留末尾空行
            for (String line : lines) {
                // SSE 规范：多行 data 必须每行加 "data: " 前缀
                writer.write("data: " + line + "\n");
            }
        } else {
            //data为null也要写一行空的data：
            writer.write("data: \n");
        }
        //空行：再写一个\n，组合起来就是\n\n，代表本条事件结束
        writer.write("\n"); // 空行表示事件结束
        writer.flush();//强制把缓冲区的数据立刻推送到网络，让前端马上收到
        out.flush();
    }

    private void writeError(HttpServletResponse response, String msg) {
        try {
            response.setStatus(500);
            OutputStreamWriter writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
            writer.write("event: error\n");
            if (msg != null) {
                String[] lines = msg.split("\n", -1);
                for (String line : lines) {
                    writer.write("data: " + line + "\n");
                }
            } else {
                writer.write("data: \n");
            }
            writer.write("\n");
            writer.flush();
            writer.close();
        } catch (IOException e) {
            log.error("写入错误响应失败", e);
        }
    }

    @GetMapping("/sessions")
    public ApiResponse<List<ChatSession>> listSessions() {
        //List<ChatSession>规定了查询结果是列表
        return ApiResponse.success(chatService.listSessions(getCurrentUserId()));
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

    @PostMapping("/chat/attachment")
    public ApiResponse<Map<String, Object>> uploadChatAttachment(
            //MultipartFile 是 Spring 的封装接口，文件上传对象，提供 getInputStream(), getBytes(), transferTo() 等方法
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "description", required = false) String description) {

        Long userId = getCurrentUserId();//从security上下文获取当前登录的userid

        if (file == null || file.isEmpty()) {
            log.warn("附件上传失败: 文件为空 [userId={}]", userId);
            return ApiResponse.fail(400, "请选择要上传的文件");
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
            return ApiResponse.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("上传聊天附件系统异常 [userId={}]", userId, e);
            return ApiResponse.fail(500, "上传失败: " + e.getMessage());
        }
    }
}