package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.Document;
import com.capsule.agentkey.entity.DocumentChunk;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.DocumentChunkRepository;
import com.capsule.agentkey.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文档服务：上传 -> 解析 -> 分块 -> Embedding -> 存入向量库
 */
@Service
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentParserService parserService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final ApiKeyService apiKeyService;

    // 文件存储目录（生产环境建议改为 OSS 或独立存储服务）
    private final Path uploadDir = Paths.get(System.getProperty("user.dir"), "uploads", "documents");

    public DocumentService(DocumentRepository documentRepository,
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
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            log.error("创建上传目录失败", e);
        }
    }

    /**
     * 上传文档（同步保存文件，异步处理解析）
     */
    @Transactional//保证数据操作的原子性（要么全部成功，要么全部失败，一旦抛出异常，框架会自动回滚整个事务）
    public Document upload(Long userId, MultipartFile file, String description) throws IOException {
        //获取原始文件名
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.contains(".")) {
            throw new BusinessException("文件名格式不正确");
        }

        //截取后缀，全部转为小写
        String ext = originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase();
        //生成全局唯一UUUID文件名，避免不同用户上传同名文件互相覆盖
        String fileName = UUID.randomUUID() + "." + ext;
        //拼接完整存储路径，uploadDir为配置好的上传根目录
        Path targetPath = uploadDir.resolve(fileName);
        //把接收的文件写入本地服务器磁盘
        file.transferTo(targetPath);

        //构建数据库实体Document，对应知识库文档数据表
        Document doc = new Document();
        doc.setUserId(userId);
        doc.setFileName(originalName);
        doc.setFileType(ext);
        doc.setFileSize(file.getSize());
        doc.setStoragePath(targetPath.toString());
        doc.setDescription(description);
        doc.setStatus(Document.Status.PENDING);
        doc.setChunkCount(0);

        //写入数据库，生成这条文档记录
        Document saved = documentRepository.save(doc);
        log.info("文档上传成功 [userId={}, docId={}, name={}]", userId, saved.getId(), originalName);

        // 异步处理解析和 Embedding（主线程不阻塞，立刻返回结果给前端）
        processDocumentAsync(userId, saved.getId());
        return saved;
    }

    /**
     * 异步处理文档：解析 -> 分块 -> Embedding -> 入库
     */
    @Async("taskExecutor")
    public void processDocumentAsync(Long userId, Long documentId) {
        try {
            Document doc = documentRepository.findById(documentId)
                    .orElseThrow(() -> new BusinessException("文档不存在"));

            if (!doc.getUserId().equals(userId)) {
                throw new BusinessException("无权操作");
            }

            // 1. 解析
            doc.setStatus(Document.Status.PARSING);
            documentRepository.save(doc);
            String fullText = parserService.parse(Paths.get(doc.getStoragePath()), doc.getFileType());
            log.info("文档解析完成 [docId={}, chars={}]", documentId, fullText.length());

            // 2. 分块
            doc.setStatus(Document.Status.CHUNKING);
            documentRepository.save(doc);
            List<String> chunks = parserService.chunk(fullText, 800, 100);
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

            // 4. Embedding
            doc.setStatus(Document.Status.EMBEDDING);
            documentRepository.save(doc);

            String apiKey = apiKeyService.getDecryptedKey(userId, "DEEPSEEK");
            float[][] embeddings = embeddingService.embed(apiKey, chunks);

            // 5. 存入向量库
            List<VectorStoreService.VectorRecord> records = new ArrayList<>();
            for (int i = 0; i < chunkEntities.size(); i++) {
                records.add(new VectorStoreService.VectorRecord(
                        chunkEntities.get(i).getId(),
                        documentId,
                        userId,
                        chunks.get(i),
                        embeddings[i]
                ));
            }
            vectorStoreService.addVectors(records);

            // 6. 完成
            doc.setStatus(Document.Status.READY);
            doc.setChunkCount(chunks.size());
            documentRepository.save(doc);
            log.info("文档处理完成 [docId={}, chunks={}]", documentId, chunks.size());

        } catch (Exception e) {
            log.error("文档处理失败 [docId={}]", documentId, e);
            Document doc = documentRepository.findById(documentId).orElse(null);
            if (doc != null) {
                doc.setStatus(Document.Status.ERROR);
                documentRepository.save(doc);
            }
        }
    }

    public List<Document> listByUserId(Long userId) {
        return documentRepository.findByUserIdOrderByCreateTimeDesc(userId);
    }

    @Transactional//该注解作用：下面所有数据库操作要么全部成功，要么全部回滚
    public void deleteDocument(Long userId, Long documentId) {
        //根据文档id查询文档记录
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException("文档不存在"));
        //对比文档所属userid和当前userid
        if (!doc.getUserId().equals(userId)) {
            throw new BusinessException("无权操作");
        }
        // 删除向量
        vectorStoreService.deleteByDocumentId(userId, documentId);
        // 删除 chunk 记录
        chunkRepository.deleteByDocumentId(documentId);
        // 删除服务器影片存储的文件
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
    //query就是用户提问文本
    public String retrieveContext(Long userId, String query, int topK) throws Exception {
        String apiKey = apiKeyService.getDecryptedKey(userId, "DEEPSEEK");//根据id取出密钥，用于调用Embedding接口
        float[] queryVec = embeddingService.embedSingle(apiKey, query);//把用户提问文本转换为浮点型向量数组（提问向量）

        //向量库检索，拿着向量取向量数据库比对，返回相似度考前的topk条结果
        List<VectorStoreService.SearchResult> results = vectorStoreService.search(userId, queryVec, topK);

        if (results.isEmpty()) return "";//如果没有匹配片段，就返回空字符串

        StringBuilder sb = new StringBuilder("【知识库检索结果】\n");
        for (int i = 0; i < results.size(); i++) {//循环遍历所有片段
            VectorStoreService.SearchResult r = results.get(i);//record类型的类自带get等方法
            sb.append("--- 片段 ").append(i + 1).append(" (相关度: ")
              .append(String.format("%.2f", r.score())).append(") ---\n")
              .append(r.content()).append("\n\n");//
        }
        return sb.toString();
    }
}