package com.capsule.agentkey.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析服务：支持 PDF / Word(doc,docx) / Excel / TXT / Markdown
 * 依赖：pdfbox 3.x, poi, poi-ooxml, poi-scratchpad(旧版 .doc)
 */
@Service
@Slf4j
public class DocumentParserService {

    public String parse(Path filePath, String fileType) throws IOException {
        String ext = fileType.toLowerCase();//转小写
        return switch (ext) {//判断文件类型来决定具体调用哪个方法
            case "pdf" -> parsePdf(filePath);
            // .doc(OLE2 旧版) 用 HWPFDocument，.docx(OOXML) 用 XWPFDocument，二者实现不同
            case "doc" -> parseDoc(filePath);
            case "docx" -> parseDocx(filePath);
            case "xls", "xlsx" -> parseExcel(filePath);
            case "txt", "md", "json", "csv", "java", "py", "js", "html", "xml", "sql", "yml", "yaml" -> parseText(filePath);
            default -> throw new IllegalArgumentException("不支持的文件类型: " + ext + "，请转换为 PDF 或 DOCX 后重试");
        };
    }

    //解析pdf文档，提取全部文本
    private String parsePdf(Path path) throws IOException {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    /**
     * 解析旧版 .doc（OLE2 二进制格式），使用 HWPFDocument + WordExtractor
     */
    private String parseDoc(Path path) throws IOException {
        try (FileInputStream fis = new FileInputStream(path.toFile());
             HWPFDocument doc = new HWPFDocument(fis);
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText().trim();
        }
    }

    private String parseDocx(Path path) throws IOException {
        try (FileInputStream fis = new FileInputStream(path.toFile());//字节输入流
             XWPFDocument doc = new XWPFDocument(fis)) {

            StringBuilder sb = new StringBuilder();//拼接全部文本

            // 1. 提取普通段落
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.trim().isEmpty()) {
                    sb.append(text).append("\n");
                }
            }

            // 2. 提取表格内容
            List<XWPFTable> tables = doc.getTables();
            if (!tables.isEmpty()) {
                sb.append("\n");
                int tableIdx = 1;
                for (XWPFTable table : tables) {
                    sb.append("【表格 ").append(tableIdx++).append("】\n");
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String cellText = cell.getText().trim();
                            if (!cellText.isEmpty()) {
                                sb.append(cellText).append(" | ");
                            }
                        }
                        sb.append("\n");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString().trim();
        }
    }

    private String parseExcel(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(path.toFile())) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                sb.append("【Sheet: ").append(sheet.getSheetName()).append("】\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        sb.append(getCellValue(cell)).append("\t");
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 读取单个单元格，根据单元格类型返回文本
     * 公式单元格优先返回缓存的计算结果值，而非公式文本（如返回 "100" 而非 "=SUM(A1:B1)"）
     */
    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        // 公式单元格：取其缓存的计算结果类型，再按对应类型取值
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /**
     * 读取纯文本文件
     * 兼容 UTF-8(含 BOM) 与 GBK(Windows 记事本默认) 编码，避免乱码
     */
    private String parseText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        // 去除 UTF-8 BOM 头
        int offset = 0;
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            offset = 3;
        }
        int len = bytes.length - offset;
        if (len <= 0) return "";
        // 严格 UTF-8 解码，失败则回退 GBK（常见于 Windows 中文 txt）
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes, offset, len)).toString();
        } catch (CharacterCodingException e) {
            log.warn("文件非 UTF-8 编码，回退 GBK 解码: {}", path);
            return new String(bytes, offset, len, Charset.forName("GBK"));
        }
    }

    /**
     * 长文本切分，支持重叠分片
     * @param text 完整文档文本
     * @param chunkSize 单块最大字符串长度
     * @param overlap 前后分块重叠字符数
     * @return 切分后的文本片段集合，后续用来生成向量
     */
    public List<String> chunk(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        // 按双换行分割段落（PDF/Word 段落边界）
        String[] paragraphs = text.split("\n\\s*\n");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;

            // 【关键修复】单个段落就超过 chunkSize：先落盘当前缓冲，再按字符长度强制切分该段落
            // 否则旧逻辑会把整个超长段落当作 1 块，导致切片数极少、单块超长
            if (para.length() > chunkSize) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                // 对超长段落按 chunkSize 滑窗切分（带 overlap），保证不丢上下文
                int step = Math.max(1, chunkSize - overlap);
                for (int start = 0; start < para.length(); start += step) {
                    int end = Math.min(para.length(), start + chunkSize);
                    chunks.add(para.substring(start, end));
                    if (end >= para.length()) break;
                }
                continue;
            }

            // 普通段落：聚合到当前缓冲
            if (current.length() + para.length() + 1 > chunkSize) {
                // 当前缓冲已满，落盘
                if (current.length() > 0) chunks.add(current.toString().trim());
                // 截取末尾 overlap 文本作为下一块开头，保持上下文连续
                if (overlap > 0 && current.length() > overlap) {
                    String overlapText = current.substring(current.length() - overlap);
                    current = new StringBuilder(overlapText).append("\n").append(para);
                } else {
                    current = new StringBuilder(para);
                }
            } else {
                if (current.length() > 0) current.append("\n");
                current.append(para);
            }
        }
        // 循环结束，缓冲区剩余的最后一段文本存入集合
        if (current.length() > 0) chunks.add(current.toString().trim());
        return chunks;
    }

    /**
     * 粗略估算文本 token 数量，用于预判 LLM 输入长度
     * @param text 待估算文本
     * @return 估算 token 数
     */
    public int estimateTokens(String text) {
        int chinese = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') chinese++;
            else if (!Character.isWhitespace(c)) other++;
        }
        return chinese + (int) (other * 0.3);
    }
}
