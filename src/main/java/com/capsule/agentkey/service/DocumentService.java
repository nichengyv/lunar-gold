package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.Document;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.DocumentChunkRepository;
import com.capsule.agentkey.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * 文档服务：上传 -> （异步）解析 -> 分块 -> Embedding -> 存入向量库
 * 异步处理逻辑拆分到 DocumentProcessingService，避免 @Async 自调用失效。
 */
@Service
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final DocumentProcessingService processingService;

    // 文件存储目录（生产环境建议改为 OSS 或独立存储服务）
    private final Path uploadDir = Paths.get(System.getProperty("user.dir"), "uploads", "documents");

    public DocumentService(DocumentRepository documentRepository,
                           DocumentChunkRepository chunkRepository,
                           EmbeddingService embeddingService,
                           VectorStoreService vectorStoreService,
                           DocumentProcessingService processingService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.processingService = processingService;
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            log.error("创建上传目录失败", e);
        }
    }

    /**
     * 上传文档（同步保存文件，异步处理解析）
     */
    @Transactional//保证数据操作的原子性
    public Document upload(Long userId, MultipartFile file, String description) throws IOException {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.contains(".")) {
            throw new BusinessException("文件名格式不正确");
        }

        String ext = originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase();
        String fileName = UUID.randomUUID() + "." + ext;
        Path targetPath = uploadDir.resolve(fileName);
        file.transferTo(targetPath);

        Document doc = new Document();
        doc.setUserId(userId);
        doc.setFileName(originalName);
        doc.setFileType(ext);
        doc.setFileSize(file.getSize());
        doc.setStoragePath(targetPath.toString());
        doc.setDescription(description);
        doc.setStatus(Document.Status.PENDING);
        doc.setChunkCount(0);

        Document saved = documentRepository.save(doc);
        log.info("文档上传成功 [userId={}, docId={}, name={}]", userId, saved.getId(), originalName);

        // 事务提交后再触发异步处理：否则 @Async 线程会读不到未提交的文档记录（findById 返回空 → “文档不存在”）
        Long documentId = saved.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                processingService.processDocumentAsync(userId, documentId);
            }
        });
        return saved;
    }

    public List<Document> listByUserId(Long userId) {
        return documentRepository.findByUserIdOrderByCreateTimeDesc(userId);
    }

    @Transactional
    public void deleteDocument(Long userId, Long documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException("文档不存在"));
        if (!doc.getUserId().equals(userId)) {
            throw new BusinessException("无权操作");
        }
        // 删除向量
        vectorStoreService.deleteByDocumentId(userId, documentId);
        // 删除 chunk 记录
        chunkRepository.deleteByDocumentId(documentId);
        // 删除服务器存储的文件
        try {
            Files.deleteIfExists(Paths.get(doc.getStoragePath()));
        } catch (IOException e) {
            log.warn("删除文件失败: {}", doc.getStoragePath());
        }
        // 删除文档主表记录
        documentRepository.delete(doc);
        log.info("文档删除成功 [docId={}]", documentId);
    }

    /**
     * RAG 检索：根据用户问题检索相关知识
     */
    public String retrieveContext(Long userId, String query, int topK) throws Exception {
        String apiKey = processingService.resolveEmbeddingKey(userId);
        float[] queryVec = embeddingService.embedSingle(apiKey, query);

        List<VectorStoreService.SearchResult> results = vectorStoreService.search(userId, queryVec, topK);

        if (results.isEmpty()) return "";

        log.info("RAG 命中片段相关度: {}", results.stream()
                .map(r -> String.format("%.2f", r.score()))
                .collect(java.util.stream.Collectors.joining(", ")));

        StringBuilder sb = new StringBuilder("【知识库检索结果】\n");
        for (int i = 0; i < results.size(); i++) {
            VectorStoreService.SearchResult r = results.get(i);
            sb.append("--- 片段 ").append(i + 1).append(" (相关度: ")
              .append(String.format("%.2f", r.score())).append(") ---\n")
              .append(r.content()).append("\n\n");
        }
        return sb.toString();
    }
}
