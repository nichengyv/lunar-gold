package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.DocumentSummary;
import com.capsule.agentkey.entity.Document;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.service.DocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@Slf4j
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new BusinessException("用户未认证");
    }

    /**
     * 上传文档
     */
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,//绑定表单里字段名为file的文件数据到这个变量
            @RequestParam(value = "description", required = false) String description) throws Exception {
        Long userId = getCurrentUserId();
        Document doc = documentService.upload(userId, file, description);
        Map<String, Object> map = new HashMap<>();
        map.put("id", doc.getId());
        map.put("fileName", doc.getFileName());
        map.put("status", doc.getStatus().name());
        map.put("message", "上传成功，正在后台处理中...");
        return ApiResponse.success(map);
    }

    /**
     * 列出用户的所有文档
     * 返回 DocumentSummary 而非实体：隐藏 storagePath 等服务器内部信息
     */
    @GetMapping("/documents")
    public ApiResponse<List<DocumentSummary>> listDocuments() {
        return ApiResponse.success(documentService.listByUserId(getCurrentUserId())
                .stream().map(DocumentSummary::from).toList());
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/documents/{id}")
    //@PathVariable从URL路径取出占位符的值
    public ApiResponse<Void> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(getCurrentUserId(), id);//前者是从上下文拿到的userid，后者是文档id
        return ApiResponse.success(null);
    }
}