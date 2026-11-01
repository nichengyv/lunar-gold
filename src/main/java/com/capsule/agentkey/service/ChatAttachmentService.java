package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.ChatAttachment;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.ChatAttachmentRepository;
import com.capsule.agentkey.util.FileUploadValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatAttachmentService {

    private final ChatAttachmentRepository attachmentRepository;
    private final DocumentParserService parserService;
    private final ApiKeyService apiKeyService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final Path uploadDir;

    /** 单轮最多送给视觉模型的图片数，避免一次请求过大 */
    private static final int MAX_VISION_IMAGES = 4;
    /** 单张图片原始字节上限（DeepSeek 单图上限 32MiB，这里更保守，base64 会膨胀 4/3） */
    private static final long MAX_VISION_IMAGE_BYTES = 8L * 1024 * 1024;
    /** 一轮图片原始字节总量上限 */
    private static final long MAX_VISION_TOTAL_BYTES = 12L * 1024 * 1024;

    /** 视觉模型只支持 JPEG/PNG/GIF/WebP，bmp 要转换成 PNG 再发 */
    private static final Map<String, String> VISION_MIME = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp"
    );

    public ChatAttachmentService(ChatAttachmentRepository attachmentRepository,
                                 DocumentParserService parserService,
                                 ApiKeyService apiKeyService,
                                 EmbeddingService embeddingService,
                                 VectorStoreService vectorStoreService) {
        this.attachmentRepository = attachmentRepository;
        this.parserService = parserService;
        this.apiKeyService = apiKeyService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.uploadDir = Paths.get(System.getProperty("user.dir"), "uploads", "chat-attachments");
        ensureUploadDirExists();
    }

    private void ensureUploadDirExists() {
        try {
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
                log.info("创建聊天附件目录: {}", uploadDir.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("创建聊天附件目录失败: {}", uploadDir.toAbsolutePath(), e);
            throw new RuntimeException("上传目录初始化失败: " + e.getMessage(), e);
        }
    }

    public ChatAttachment uploadAttachment(Long userId, MultipartFile file, String description) throws IOException {
        //扩展名白名单 + Tika 内容嗅探；不通过直接抛 400，且附带可读错误消息
        String ext = FileUploadValidator.validateAttachment(file);
        String originalName = file.getOriginalFilename();

        //保证上传文件夹存在，若目录不存在，就创建文件夹，防止后面文件IO报错
        ensureUploadDirExists();

        //生成服务器侧存储文件名（生成随机文件名）
        String fileName = UUID.randomUUID() + "." + ext;
        //uploadDir是Path对象，表示目标文件夹   resolve(fileName)把文件名拼接在文件夹路径后
        Path targetPath = uploadDir.resolve(fileName);
        //transferTo是真正的IO操作，把MultipartFile的二进制流写入服务器磁盘文件
        file.transferTo(targetPath);

        //构建实体
        ChatAttachment attachment = new ChatAttachment();
        attachment.setUserId(userId);
        attachment.setFileName(originalName);
        attachment.setFileType(ext);
        attachment.setFileSize(file.getSize());
        attachment.setStoragePath(targetPath.toString());
        attachment.setDescription(description);
        //extractContent解析文件内容
        String contentText = extractContent(targetPath, ext, originalName);
        //提取出的文本存入实体contentText字段用于生成预览，这个字段是数据库里的一个字段，存储文件提取出的文本内容
        attachment.setContentText(contentText);


        //操作数据库入库
        ChatAttachment saved = attachmentRepository.save(attachment);
        log.info("聊天附件上传成功 [userId={}, id={}, name={}, chars={}]",
                userId, saved.getId(), originalName, contentText.length());

        //若文本超一定字符，异步执行其他操作
        if (contentText != null && contentText.length() > 500) {
            embedAttachmentAsync(saved.getId(), userId, contentText);
        }

        //把已经拥有数据库id的实体对象返回给controller
        return saved;
    }

    //异步向量化
    //作用：把上传附件里的文档文本变成向量存入向量数据库，让AI聊天是可以读懂
    //异步注解，调用这个方法时，不会在当前上传HTTP请求线程执行，会交给线程池taskExcutor新开后台线程运行
    //也就是接口正常响应，但是向量库在后台慢慢跑
    @Async("taskExecutor")
    public void embedAttachmentAsync(Long attachmentId, Long userId, String contentText) {
        try {
            log.info("开始附件向量化 [attachmentId={}, chars={}]", attachmentId, contentText.length());
            //文本分块chunkSize规定每块最大字符，overlap规定块与块重叠字符
            //重叠目的：防止语义被切在句子中间丢失上下文
            //输出：把文档切割成多个字符串片段集合
            List<String> chunks = parserService.chunk(contentText, 800, 100);
            //若切割后没有有效文本，直接结束方法
            if (chunks.isEmpty()) return;

            //根据用户id直接取出数据库加密的大模型密钥，内部做AES解密，拿到明文
            String apiKey = apiKeyService.getDecryptedKey(userId, "DEEPSEEK");
            //用apikey调用embadding模型，调用embeddings接口，
            ///把文本块批量转换为向量，返回二维浮点数数组
            float[][] embeddings = embeddingService.embed(apiKey, chunks);

            //把每一条文本块和对应向量打包成记录，准备批量写入向量数据库（这里是存入内存空间）
            List<VectorStoreService.VectorRecord> records = new ArrayList<>();//创建列表，装待入库的向量记录
            for (int i = 0; i < chunks.size(); i++) {//遍历每个文本块
                long chunkId = UUID.randomUUID().getMostSignificantBits();//随机生成id（128位，且有唯一性），最后的方法用来取高64位
                // ✅ 修复：必须加入 records
                //组装记录
                records.add(new VectorStoreService.VectorRecord(
                        chunkId, //文本块唯一id
                        attachmentId, //所属附件id
                        userId, //所属用户id
                        chunks.get(i), //文本内容（第i段）就是content
                        embeddings[i],//对应向量（第i个）
                        "attachment"//（来源类型标记）
                ));
            }
            vectorStoreService.addVectors(records);
            log.info("附件向量化完成 [attachmentId={}, chunks={}]", attachmentId, chunks.size());
        } catch (Exception e) {
            log.error("附件向量化失败 [attachmentId={}]", attachmentId, e);
        }
    }

    public String buildSmartContext(Long userId, List<Long> attachmentIds, String query) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return "";

        List<ChatAttachment> attachments = attachmentRepository.findAllById(attachmentIds);
        if (attachments.isEmpty()) return "";

        long totalChars = attachments.stream()
                .mapToLong(a -> a.getContentText() != null ? a.getContentText().length() : 0)
                .sum();

        if (totalChars < 6000) {
            log.info("附件总字数{}，直接全文读取", totalChars);
            return buildFileContext(attachments, 15000);
        }

        boolean vectorsReady = attachmentIds.stream().anyMatch(attId ->
                vectorStoreService.countByDocumentId(userId, attId) > 0);
        if (!vectorsReady && totalChars >= 6000) {
            log.info("向量化尚未完成，直接全文");
            return buildFileContext(attachments, 15000);
        }

        if (query != null && !query.trim().isEmpty()) {
            try {
                String ragContext = retrieveAttachmentContext(userId, attachmentIds, query, 5);
                if (!ragContext.isEmpty()) {
                    log.info("附件总字数{}，走 RAG 检索", totalChars);
                    return ragContext;
                }
            } catch (Exception e) {
                log.warn("附件 RAG 检索失败，降级全文: {}", e.getMessage());
            }
        }

        log.info("附件总字数{}，RAG 不可用，降级为全文截断", totalChars);
        return buildFileContext(attachments, 15000);
    }

    public String retrieveAttachmentContext(Long userId, List<Long> attachmentIds,
                                            String query, int topK) throws Exception {
        String apiKey = apiKeyService.getDecryptedKey(userId, "DEEPSEEK");
        float[] queryVec = embeddingService.embedSingle(apiKey, query);

        List<VectorStoreService.SearchResult> allResults = new ArrayList<>();
        for (Long attId : attachmentIds) {
            List<VectorStoreService.SearchResult> results =
                    vectorStoreService.search(userId, queryVec, topK, attId, "attachment");
            allResults.addAll(results);
        }

        if (allResults.isEmpty()) return "";

        allResults.sort((a, b) -> Float.compare(b.score(), a.score()));
        if (allResults.size() > topK) {
            allResults = allResults.subList(0, topK);
        }

        StringBuilder sb = new StringBuilder("【附件检索结果】\n");
        for (int i = 0; i < allResults.size(); i++) {
            VectorStoreService.SearchResult r = allResults.get(i);
            sb.append("--- 片段 ").append(i + 1)
                    .append(" (相关度: ").append(String.format("%.2f", r.score())).append(") ---\n")
                    .append(r.content()).append("\n\n");
        }
        sb.append("【请基于以上检索到的附件片段回答用户问题】\n");
        return sb.toString();
    }

    public String buildFileContext(List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return "";
        return buildFileContext(attachmentRepository.findAllById(attachmentIds), 4000);
    }

    public String buildFileContext(List<ChatAttachment> attachments, int maxLenPerFile) {
        if (attachments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【用户上传了 ").append(attachments.size()).append(" 个文件，内容如下】\n");
        for (int i = 0; i < attachments.size(); i++) {
            ChatAttachment att = attachments.get(i);
            sb.append("--- 文件 ").append(i + 1).append(": ").append(att.getFileName())
                    .append(" (").append(formatFileSize(att.getFileSize())).append(") ---\n");
            if (att.getContentText() != null && !att.getContentText().isEmpty()) {
                String content = att.getContentText();
                if (content.length() > maxLenPerFile) {
                    content = content.substring(0, maxLenPerFile)
                            + "\n... [文件内容较长，已截断。如需分析后续内容，请告诉我继续阅读]";
                }
                sb.append(content).append("\n");
            }
            sb.append("\n");
        }
        sb.append("【文件内容结束，请基于以上文件内容回答用户问题】\n");
        return sb.toString();
    }

    public void linkAttachmentsToMessage(List<Long> attachmentIds, Long messageId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return;
        List<ChatAttachment> attachments = attachmentRepository.findAllById(attachmentIds);
        for (ChatAttachment att : attachments) {
            att.setMessageId(messageId);
        }
        attachmentRepository.saveAll(attachments);
    }

    public void deleteAttachment(Long id, Long userId) {
        ChatAttachment att = attachmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException("附件不存在"));
        if (!att.getUserId().equals(userId)) {
            throw new BusinessException("无权操作");
        }
        vectorStoreService.deleteByDocumentId(userId, id, "attachment");
        try {
            Files.deleteIfExists(Paths.get(att.getStoragePath()));
        } catch (IOException e) {
            log.warn("删除附件文件失败: {}", att.getStoragePath());
        }
        attachmentRepository.delete(att);
    }

    /**
     * 把图片附件转成视觉模型可用的 base64 data URL。
     * 只处理 jpg/jpeg/png/gif/webp；bmp/svg 这类视觉模型不支持的格式直接跳过。
     */
    public List<ImageInput> buildImageInputs(Long userId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return List.of();
        List<ImageInput> result = new ArrayList<>();
        long totalBytes = 0;
        for (ChatAttachment att : attachmentRepository.findAllById(attachmentIds)) {
            if (!Objects.equals(att.getUserId(), userId)) continue;
            String ext = att.getFileType() == null ? "" : att.getFileType().toLowerCase();
            String mime = VISION_MIME.get(ext);
            if (mime == null) continue;//非图片，或视觉模型不支持的图片格式
            if (result.size() >= MAX_VISION_IMAGES) {
                log.warn("图片数量超过 {} 张，多余部分跳过视觉输入 [attachmentId={}]", MAX_VISION_IMAGES, att.getId());
                break;
            }
            if (att.getFileSize() != null && att.getFileSize() > MAX_VISION_IMAGE_BYTES) {
                log.warn("图片超过 {}MB，跳过视觉输入 [{}]", MAX_VISION_IMAGE_BYTES / 1024 / 1024, att.getFileName());
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(att.getStoragePath()));
                if (totalBytes + bytes.length > MAX_VISION_TOTAL_BYTES) {
                    log.warn("图片总量超过上限，跳过视觉输入 [{}]", att.getFileName());
                    continue;
                }
                totalBytes += bytes.length;
                String b64 = Base64.getEncoder().encodeToString(bytes);
                result.add(new ImageInput(att.getFileName(), mime, "data:" + mime + ";base64," + b64));
            } catch (IOException e) {
                log.warn("读取图片失败，跳过视觉输入 [{}]: {}", att.getFileName(), e.getMessage());
            }
        }
        return result;
    }

    /** 视觉模型输入：文件名 + MIME + 可直接放进 image_url.url 的 data URL */
    public record ImageInput(String fileName, String mimeType, String dataUrl) {}

    private String extractContent(Path filePath, String ext, String originalName) {
        try {
            //isImage判断后缀是不是图片
            if (isImage(ext)) {
                return extractImageInfo(filePath, originalName);//这里不是ORC，不能拿到图片里的文字
            }
            //若非图片，交给解析服务parserService，根据后缀解析文档内部文本
            return parserService.parse(filePath, ext);
        } catch (Exception e) {
            log.warn("文件内容提取失败 [{}]: {}", originalName, e.getMessage());
            return "[文件内容提取失败: " + e.getMessage() + "]";
        }
    }

    private boolean isImage(String ext) {
        return Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg").contains(ext);
    }

    private String extractImageInfo(Path filePath, String originalName) {
        StringBuilder sb = new StringBuilder();
        sb.append("[图片文件: ").append(originalName).append("]\n");
        try {
            BufferedImage image = ImageIO.read(filePath.toFile());
            if (image != null) {
                sb.append("尺寸: ").append(image.getWidth()).append("x").append(image.getHeight()).append(" 像素\n");
            }
        } catch (Exception e) {
            log.warn("读取图片元数据失败: {}", originalName);
        }
        sb.append("说明：这是用户上传的图片，内容已交给视觉模型转写后并入对话上下文。");
        return sb.toString();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}