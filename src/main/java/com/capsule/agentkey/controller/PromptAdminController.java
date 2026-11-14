package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.PromptCatalog;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.Map;

/**
 * P5-01：提示词热重载。仅允许本机调用——桌面端后端只监听 localhost，
 * 避免远端会话触发重载后把有问题的提示词带上线。
 */
@RestController
@RequestMapping("/api/admin/prompts")
@RequiredArgsConstructor
@Slf4j
public class PromptAdminController {

    private final PromptCatalog promptCatalog;

    @PostMapping("/reload")
    public ApiResponse<Map<String, String>> reload(HttpServletRequest request) {
        if (!isLocalRequest(request)) {
            throw new BusinessException("仅允许本机重载提示词");
        }
        promptCatalog.reload();
        Map<String, String> versions = promptCatalog.describe();
        log.info("提示词已手动重载: {}", versions);
        return ApiResponse.success(versions);
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        try {
            String remote = request.getRemoteAddr();
            return remote != null && InetAddress.getByName(remote).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
