package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.Document;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.DocumentChunkRepository;
import com.capsule.agentkey.repository.DocumentRepository;
import com.capsule.agentkey.util.FileUploadValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ProjectService projectService;

    // 文件存储目录（生产环境建议改为 OSS 或独立存储服务）
    private final Path uploadDir = Paths.get(System.getProperty("user.dir"), "uploads", "documents");

    // 知识库相关度门槛：片段级下限与"整轮是否使用资料"的门槛，可按实际语料在配置里调
    @Value("${rag.min-score:0.5}")
    private float minScore;

    @Value("${rag.gate-score:0.55}")
    private float gateScore;

    public DocumentService(DocumentRepository documentRepository,
                           DocumentChunkRepository chunkRepository,
                           EmbeddingService embeddingService,
                           VectorStoreService vectorStoreService,
                           DocumentProcessingService processingService,
                           ProjectService projectService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.processingService = processingService;
        this.projectService = projectService;
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            log.error("创建上传目录失败", e);
        }
    }

    /**
     * 上传文档（同步保存文件，异步处理解析）
     * projectId 为空时归入当前用户的默认项目，老前端/老数据无需改造。
     */
    @Transactional//保证数据操作的原子性
    public Document upload(Long userId, Long projectId, MultipartFile file, String description) throws IOException {
        String originalName = file.getOriginalFilename();
        //扩展名白名单 + Tika 内容嗅探，双重校验防绕过；不通过直接抛 400
        String ext = FileUploadValidator.validateDocument(file);
        //先校验项目归属，再落盘，避免项目非法时留下孤儿文件
        Long resolvedProjectId = projectService.resolveProjectId(userId, projectId);

        String fileName = UUID.randomUUID() + "." + ext;
        Path targetPath = uploadDir.resolve(fileName);
        file.transferTo(targetPath);

        Document doc = new Document();
        doc.setUserId(userId);
        doc.setProjectId(resolvedProjectId);
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

    public List<Document> listByUserId(Long userId, Long projectId) {
        return documentRepository.findByUserIdAndProjectIdOrderByCreateTimeDesc(
                userId, projectService.resolveProjectId(userId, projectId));
    }

    /**
     * B7：手动重试处理失败的文档。
     * 前置校验：本人操作 + 仅 ERROR 状态可重试；重试前清理残留 chunk 与半截向量，
     * 状态重置为 PENDING，再跨类触发异步管线（@Async 自调用失效，必须经 processingService 代理）。
     */
    public void retryDocument(Long userId, Long documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException("文档不存在"));
        if (!doc.getUserId().equals(userId)) {
            throw new BusinessException("无权操作");
        }
        if (doc.getStatus() != Document.Status.ERROR) {
            throw new BusinessException("仅处理失败的文档可以重试");
        }
        //清理上次失败可能残留的数据，保证重试是干净的一次全量重建
        chunkRepository.deleteByDocumentId(documentId);
        vectorStoreService.deleteByDocumentId(userId, documentId);
        doc.setStatus(Document.Status.PENDING);
        doc.setChunkCount(0);
        documentRepository.save(doc);
        log.info("文档重试已触发 [userId={}, docId={}]", userId, documentId);
        processingService.processDocumentAsync(userId, documentId);
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
     * 引用的资料：只暴露用户能理解的字段，前端用于在回答下方展示出处
     */
    public record Source(Long documentId, String fileName, float score) {
    }

    /**
     * RAG 检索结果：注入模型的上下文 + 给用户看的出处列表
     */
    public record RagResult(String context, List<Source> sources) {
        public static RagResult empty() {
            return new RagResult("", List.of());
        }
    }

    /**
     * RAG 检索：只检索当前项目内的知识库，避免不同项目的资料互相串味。
     * 两道门槛避免"答非所问却标出处"：
     * 1) 单片段相关度低于 minScore 丢弃；
     * 2) 最高分都低于 gateScore 时，判定本次问题与资料无关，整体不使用知识库。
     */
    public RagResult retrieveContext(Long userId, Long projectId, String query, int topK) throws Exception {
        String apiKey = processingService.resolveEmbeddingKey(userId);
        float[] queryVec = embeddingService.embedSingle(apiKey, query);

        List<VectorStoreService.SearchResult> results =
                vectorStoreService.searchForProject(userId, projectId, queryVec, topK, minScore);

        if (results.isEmpty()) return RagResult.empty();

        float topScore = results.get(0).score();
        if (topScore < gateScore) {
            log.info("知识库最高相关度 {} 未达门槛 {}，本轮不引用资料", String.format("%.2f", topScore), gateScore);
            return RagResult.empty();
        }

        log.info("RAG 命中片段相关度: {}", results.stream()
                .map(r -> String.format("%.2f", r.score()))
                .collect(java.util.stream.Collectors.joining(", ")));

        // 片段里没有文件名，按 documentId 回查一次拿展示用的出处
        List<Long> docIds = results.stream().map(VectorStoreService.SearchResult::documentId).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        documentRepository.findAllById(docIds)
                .forEach(d -> names.put(d.getId(), d.getFileName()));

        StringBuilder sb = new StringBuilder("【知识库检索结果】\n");
        for (int i = 0; i < results.size(); i++) {
            VectorStoreService.SearchResult r = results.get(i);
            sb.append("--- 片段 ").append(i + 1).append(" (相关度: ")
              .append(String.format("%.2f", r.score())).append(") ---\n")
              .append(r.content()).append("\n\n");
        }

        //同一份资料可能命中多个片段，出处按文档去重并保留最高分
        Map<Long, Source> byDoc = new LinkedHashMap<>();
        for (VectorStoreService.SearchResult r : results) {
            byDoc.merge(r.documentId(),
                    new Source(r.documentId(), names.getOrDefault(r.documentId(), "未知资料"), r.score()),
                    (a, b) -> a.score() >= b.score() ? a : b);
        }
        return new RagResult(sb.toString(), new ArrayList<>(byDoc.values()));
    }
}
