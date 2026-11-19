package com.capsule.agentkey.controller;

import com.capsule.agentkey.dto.ApiResponse;
import com.capsule.agentkey.dto.DocumentSummary;
import com.capsule.agentkey.entity.Document;
import com.capsule.agentkey.service.DocumentService;
import com.capsule.agentkey.util.CurrentUser;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * 上传文档
     */
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,//绑定表单里字段名为file的文件数据到这个变量
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "description", required = false) String description) throws Exception {
        Long userId = CurrentUser.requireId();
        Document doc = documentService.upload(userId, projectId, file, description);
        Map<String, Object> map = new HashMap<>();
        map.put("id", doc.getId());
        map.put("fileName", doc.getFileName());
        map.put("status", doc.getStatus().name());
        map.put("message", "上传成功，正在后台处理中...");
        return ApiResponse.success(map);
    }

    /**
     * 列出当前项目内的资料；不传 projectId 时回落到默认项目
     * 返回 DocumentSummary 而非实体：隐藏 storagePath 等服务器内部信息
     */
    @GetMapping("/documents")
    public ApiResponse<List<DocumentSummary>> listDocuments(
            @RequestParam(value = "projectId", required = false) Long projectId) {
        return ApiResponse.success(documentService.listByUserId(CurrentUser.requireId(), projectId)
                .stream().map(DocumentSummary::from).toList());
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/documents/{id}")
    //@PathVariable从URL路径取出占位符的值
    public ApiResponse<Void> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(CurrentUser.requireId(), id);//前者是从上下文拿到的userid，后者是文档id
        return ApiResponse.success(null);
    }

    /**
     * B7：重试处理失败的文档（自动重试 3 次仍为 ERROR 后，用户可手动再触发一轮）
     */
    @PostMapping("/documents/{id}/retry")
    public ApiResponse<Void> retryDocument(@PathVariable Long id) {
        documentService.retryDocument(CurrentUser.requireId(), id);
        return ApiResponse.success(null);
    }
}