package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 文档分块（Chunk）
 * 向量数据存储在内存 VectorStore 中，这里只存元数据
 */
@Data
@Entity
@Table(name = "document_chunk", indexes = {
    @Index(name = "idx_doc_id", columnList = "documentId"),
    @Index(name = "idx_user_doc", columnList = "userId,documentId")
})
@EntityListeners(AuditingEntityListener.class)
public class DocumentChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private Integer chunkIndex; // 第几个块

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content; // 纯文本内容

    @Column(nullable = false)
    private Integer tokenCount; // 估算token数

    @CreatedDate
    private LocalDateTime createTime;
}