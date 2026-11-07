package com.capsule.agentkey.dto;

import com.capsule.agentkey.entity.Document;

import java.time.LocalDateTime;

/**
 * 知识库文档对外视图（A4 安全修复）：
 * 实体里的 storagePath 暴露服务器磁盘目录结构（如 d:/uploads/xxx.pdf），
 * 列表等对外接口一律返回本 DTO，不带任何服务器内部路径。
 */
public record DocumentSummary(
        Long id,
        String fileName,
        Long fileSize,
        String fileType,
        String status,
        Integer chunkCount,
        String description,
        LocalDateTime createTime) {

    public static DocumentSummary from(Document d) {
        return new DocumentSummary(
                d.getId(),
                d.getFileName(),
                d.getFileSize(),
                d.getFileType(),
                d.getStatus() != null ? d.getStatus().name() : null,
                d.getChunkCount(),
                d.getDescription(),
                d.getCreateTime());
    }
}
