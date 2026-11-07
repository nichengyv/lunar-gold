package com.capsule.agentkey.util;

import com.capsule.agentkey.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileUploadValidator 纯单元测试：扩展名白名单 + Tika 内容嗅探。
 * 覆盖：合法 txt/pdf 放行、危险扩展名拒绝、伪装扩展名（exe 改名 txt）拒绝、空文件拒绝。
 */
class FileUploadValidatorTest {

    @Test
    void validateDocument_plainTextTxt_accepted() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain",
                "hello world".getBytes(StandardCharsets.UTF_8));
        String ext = FileUploadValidator.validateDocument(file);
        assertEquals("txt", ext);
    }

    @Test
    void validateDocument_markdown_accepted() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "readme.md", "text/markdown",
                "# title\nsome content".getBytes(StandardCharsets.UTF_8));
        assertEquals("md", FileUploadValidator.validateDocument(file));
    }

    @Test
    void validateDocument_realPdf_accepted() {
        //构造最小 PDF 文件头：%PDF-1.4
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n"
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", pdfBytes);
        assertEquals("pdf", FileUploadValidator.validateDocument(file));
    }

    @Test
    void validateDocument_exeExtension_rejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.exe", "application/octet-stream",
                "MZ".getBytes(StandardCharsets.UTF_8));
        BusinessException e = assertThrows(BusinessException.class,
                () -> FileUploadValidator.validateDocument(file));
        assertTrue(e.getMessage().contains("不支持的文件类型"));
    }

    @Test
    void validateDocument_disguisedExecutable_rejectedByMimeSniffing() {
        //exe 改名成 txt：扩展名在白名单内，但内容是 PE 头（MZ 开头），
        //Tika 嗅探会识别为 application/x-msdownload 而不是 text/plain，应被拒绝
        byte[] peHeader = new byte[64];
        peHeader[0] = 'M'; peHeader[1] = 'Z';
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.txt", "text/plain", peHeader);
        BusinessException e = assertThrows(BusinessException.class,
                () -> FileUploadValidator.validateDocument(file));
        assertTrue(e.getMessage().contains("文件内容与扩展名不符"),
                "伪装文件应被 Tika 嗅探识别并拒绝，实际消息: " + e.getMessage());
    }

    @Test
    void validateDocument_emptyFile_rejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);
        assertThrows(BusinessException.class, () -> FileUploadValidator.validateDocument(file));
    }

    @Test
    void validateDocument_noExtension_rejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "noext", "text/plain",
                "content".getBytes(StandardCharsets.UTF_8));
        assertThrows(BusinessException.class, () -> FileUploadValidator.validateDocument(file));
    }

    @Test
    void validateAttachment_imageJpeg_accepted() {
        //构造最小 JPEG：SOI + APP0 标记
        byte[] jpegBytes = new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00,
                0x00, 0x01, 0x00, 0x01, 0x00, 0x00
        };
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", jpegBytes);
        assertEquals("jpg", FileUploadValidator.validateAttachment(file));
    }

    @Test
    void validateDocument_imageRejected_forKnowledgeBase() {
        //知识库不允许图片（解析管线不处理），但聊天附件允许
        byte[] jpegBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", jpegBytes);
        assertThrows(BusinessException.class, () -> FileUploadValidator.validateDocument(file));
    }
}
