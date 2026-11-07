package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.Document;
import com.capsule.agentkey.entity.DocumentChunk;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.DocumentChunkRepository;
import com.capsule.agentkey.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档异步处理服务：解析 -> 分块 -> Embedding -> 入向量库
 * 独立成类，避免 DocumentService 内部自调用导致 @Async 失效（Spring AOP 代理只对外部调用生效）。
 */
@Service
@Slf4j
public class DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentParserService parserService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final ApiKeyService apiKeyService;

    @Value("${embedding.api.key:}")
    private String globalEmbeddingKey;

    public DocumentProcessingService(DocumentRepository documentRepository,
                                     DocumentChunkRepository chunkRepository,
                                     DocumentParserService parserService,
                                     EmbeddingService embeddingService,
                                     VectorStoreService vectorStoreService,
                                     ApiKeyService apiKeyService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.parserService = parserService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.apiKeyService = apiKeyService;
    }

    /**
     * 异步处理文档：解析 -> 分块 -> Embedding -> 入库
     * 由 DocumentService.upload 跨类调用，@Async 才能生效（不阻塞上传请求）。
     *
     * B7 重试策略：整条管线最多尝试 3 次，失败后指数退避（5s → 15s）再重试。
     * 瞬时故障（Embedding API 抖动、网络超时、数据库瞬断）通常第二次就能通过，
     * 自动重试省去用户删掉重传的操作；重试前清理上次残留的 chunk，防止重复入库。
     */
    @Async("taskExecutor")
    public void processDocumentAsync(Long userId, Long documentId) {
        final int MAX_ATTEMPTS = 3;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                runPipeline(userId, documentId);
                return;//成功即结束
            } catch (Exception e) {
                log.error("文档处理失败 [docId={}, attempt={}/{}]", documentId, attempt, MAX_ATTEMPTS, e);
                if (attempt >= MAX_ATTEMPTS) {
                    markStatus(documentId, Document.Status.ERROR);//最终失败才置 ERROR
                    return;
                }
                //重试前清理本次尝试残留的分块数据，避免下一轮 saveAll 造成重复
                chunkRepository.deleteByDocumentId(documentId);
                markStatus(documentId, Document.Status.PENDING);//回到"待处理"，前端可看到仍在处理
                try {
                    Thread.sleep(attempt == 1 ? 5_000L : 15_000L);//指数退避：5s → 15s
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();//恢复中断标记，交给线程池处理
                    markStatus(documentId, Document.Status.ERROR);
                    return;
                }
            }
        }
    }

    /**
     * 单次完整管线：解析 -> 分块 -> 存 chunk -> Embedding -> 入向量库 -> READY。
     * 抛出异常由调用方（重试循环）处理，自身不捕获。
     */
    private void runPipeline(Long userId, Long documentId) throws Exception {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException("文档不存在"));

        if (!doc.getUserId().equals(userId)) {
            throw new BusinessException("无权操作");
        }

        // 1. 解析
        doc.setStatus(Document.Status.PARSING);
        documentRepository.save(doc);
        String fullText = parserService.parse(Paths.get(doc.getStoragePath()), doc.getFileType());
        if (fullText == null || fullText.isBlank()) {
            throw new BusinessException("文档解析后内容为空，可能是扫描件/图片型 PDF，暂不支持");
        }
        log.info("文档解析完成 [docId={}, chars={}]", documentId, fullText.length());

        // 2. 分块
        doc.setStatus(Document.Status.CHUNKING);
        documentRepository.save(doc);
        List<String> chunks = parserService.chunk(fullText, 800, 100);
        if (chunks.isEmpty()) {
            throw new BusinessException("文档分块结果为空");
        }
        log.info("文档分块完成 [docId={}, chunks={}]", documentId, chunks.size());

        // 3. 保存 Chunk 元数据
        List<DocumentChunk> chunkEntities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk c = new DocumentChunk();
            c.setUserId(userId);
            c.setDocumentId(documentId);
            c.setChunkIndex(i);
            c.setContent(chunks.get(i));
            c.setTokenCount(parserService.estimateTokens(chunks.get(i)));
            chunkEntities.add(c);
        }
        chunkRepository.saveAll(chunkEntities);

        // 4. Embedding（向量化）
        doc.setStatus(Document.Status.EMBEDDING);
        documentRepository.save(doc);

        String apiKey = resolveEmbeddingKey(userId);
        float[][] embeddings = embeddingService.embed(apiKey, chunks);

        // 5. 存入向量库
        List<VectorStoreService.VectorRecord> records = new ArrayList<>();
        for (int i = 0; i < chunkEntities.size(); i++) {
            records.add(new VectorStoreService.VectorRecord(
                    chunkEntities.get(i).getId(),
                    documentId,
                    userId,
                    chunks.get(i),
                    embeddings[i],
                    doc.getProjectId()
            ));
        }
        vectorStoreService.addVectors(records);

        // 6. 完成
        doc.setStatus(Document.Status.READY);
        doc.setChunkCount(chunks.size());
        documentRepository.save(doc);
        log.info("文档处理完成 [docId={}, chunks={}]", documentId, chunks.size());
    }

    /** 安全地更新文档状态（记录可能已被用户删除） */
    private void markStatus(Long documentId, Document.Status status) {
        documentRepository.findById(documentId).ifPresent(d -> {
            d.setStatus(status);
            documentRepository.save(d);
        });
    }

    /**
     * 解析 Embedding API Key：优先用用户配置的 provider=SILICONFLOW 密钥，兜底用全局配置。
     */
    String resolveEmbeddingKey(Long userId) {
        try {
            return apiKeyService.getDecryptedKey(userId, "SILICONFLOW");
        } catch (Exception e) {
            if (globalEmbeddingKey != null && !globalEmbeddingKey.isBlank()) {
                return globalEmbeddingKey.trim();
            }
            throw new BusinessException("未配置 Embedding API Key，请在「API Keys」页添加 provider=SILICONFLOW 的密钥，或在配置 embedding.api.key 中设置");
        }
    }
}
