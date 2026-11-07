package com.capsule.agentkey.util;

import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import org.apache.tika.Tika;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * 文件上传统一校验：扩展名白名单 + Tika 内容嗅探。
 *
 * 背景：上传接口原先只校验"文件名必须含点"，扩展名既可以是 exe/jsp，
 * 也完全相信用户给的扩展名（把 evil.exe 改名 evil.pdf 就能混进来）。
 * 虽然当前只做解析不做执行/预览，直接 RCE 风险低，但：
 *  1) 下游 POI/PDFBox 解析到不支持的类型会以不友好的堆栈错误抛给用户；
 *  2) 若未来加文件预览/下载，恶意 HTML/SVG 会造成 XSS 与钓鱼；
 *  3) 用户被骗上传可执行文件后，本地误点也是风险。
 * 因此在入口处就把类型卡死。
 *
 * 校验策略：扩展名必须在白名单内，且 Tika 按内容嗅探出的 MIME 必须与扩展名匹配，
 * 两者任一不满足即拒绝。
 */
public final class FileUploadValidator {

    private FileUploadValidator() {}

    private static final Tika TIKA = new Tika();

    /**
     * 允许的扩展名 → 期望的 Tika MIME 集合。
     * 值是 Set 是因为同一扩展名可能对应多个合法 MIME（如 txt/md 都是 text/plain，
     * jpg/jpeg 都是 image/jpeg），且部分版本的 Tika 对 OOXML 会返回 zip 父类型。
     */
    private static final Map<String, Set<String>> EXT_TO_MIMES = Map.ofEntries(
            Map.entry("pdf",  Set.of("application/pdf")),
            Map.entry("docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                     "application/x-tika-ooxml")),
            Map.entry("doc",  Set.of("application/msword")),
            Map.entry("xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                     "application/x-tika-ooxml")),
            Map.entry("xls",  Set.of("application/vnd.ms-excel")),
            Map.entry("pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                     "application/x-tika-ooxml")),
            Map.entry("ppt",  Set.of("application/vnd.ms-powerpoint")),
            Map.entry("txt",  Set.of("text/plain")),
            Map.entry("md",   Set.of("text/plain", "text/markdown", "text/x-web-markdown")),
            Map.entry("jpg",  Set.of("image/jpeg")),
            Map.entry("jpeg", Set.of("image/jpeg")),
            Map.entry("png",  Set.of("image/png")),
            Map.entry("gif",  Set.of("image/gif")),
            Map.entry("bmp",  Set.of("image/bmp", "image/x-ms-bmp")),
            Map.entry("webp", Set.of("image/webp"))
    );

    /** 知识库文档：仅允许可解析的文本类文档（不允许图片，解析管线不处理） */
    private static final Set<String> DOCUMENT_EXTS = Set.of(
            "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "txt", "md");

    /** 聊天附件：文档 + 图片（图片走元数据描述路径，不做 OCR） */
    private static final Set<String> ATTACHMENT_EXTS = Set.of(
            "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "txt", "md",
            "jpg", "jpeg", "png", "gif", "bmp", "webp");

    /**
     * 校验知识库文档上传。
     * @return 通过校验后的扩展名（小写）
     */
    public static String validateDocument(MultipartFile file) {
        return doValidate(file, DOCUMENT_EXTS);
    }

    /**
     * 校验聊天附件上传（支持文档 + 图片）。
     * @return 通过校验后的扩展名（小写）
     */
    public static String validateAttachment(MultipartFile file) {
        return doValidate(file, ATTACHMENT_EXTS);
    }

    private static String doValidate(MultipartFile file, Set<String> allowedExts) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "上传文件不能为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.contains(".")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "文件名格式不正确");
        }
        String ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase();

        if (!allowedExts.contains(ext)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的文件类型 ." + ext + "，仅支持：" + String.join(", ", allowedExts));
        }

        Set<String> expectedMimes = EXT_TO_MIMES.get(ext);
        if (expectedMimes == null) {
            //白名单与 MIME 映射不一致是编程错误，直接失败避免绕过
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件类型映射缺失: " + ext);
        }

        String detected;
        try (InputStream in = file.getInputStream()) {
            detected = TIKA.detect(in, originalName);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "无法识别文件内容，文件可能已损坏");
        }
        if (!expectedMimes.contains(detected)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "文件内容与扩展名不符（检测为 " + detected + "），请确认文件未损坏且未改名");
        }
        return ext;
    }
}
