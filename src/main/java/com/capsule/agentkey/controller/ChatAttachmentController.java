package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.entity.ChatAttachment;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import com.capsule.agentkey.service.ChatAttachmentService;
import com.capsule.agentkey.util.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 对话附件上传：从 AgentChatController 拆出，URL 与行为保持不变（/api/agent/chat/attachment）。
 */
@RestController
@RequestMapping("/api/agent")
@Slf4j
public class ChatAttachmentController {

    private final ChatAttachmentService chatAttachmentService;

    public ChatAttachmentController(ChatAttachmentService chatAttachmentService) {
        this.chatAttachmentService = chatAttachmentService;
    }

    @PostMapping("/chat/attachment")
    public ApiResponse<Map<String, Object>> uploadChatAttachment(
            //MultipartFile 是 Spring 的封装接口，文件上传对象，提供 getInputStream(), getBytes(), transferTo() 等方法
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "description", required = false) String description) {

        Long userId = CurrentUser.requireId();//从security上下文获取当前登录的userid

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
