package com.capsule.agentkey.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析服务：支持 PDF / Word(docx) / Excel / TXT / Markdown
 * 注意：旧版 .doc 格式请转换为 .docx 后上传
 * 依赖：pdfbox 3.x, poi, poi-ooxml
 */
@Service
@Slf4j
public class DocumentParserService {

    public String parse(Path filePath, String fileType) throws IOException {
        String ext = fileType.toLowerCase();
        return switch (ext) {
            case "pdf" -> parsePdf(filePath);
            case "doc", "docx" -> parseDocx(filePath);
            case "xls", "xlsx" -> parseExcel(filePath);
            case "txt", "md", "json", "csv", "java", "py", "js", "html", "xml", "sql", "yml", "yaml" -> parseText(filePath);
            default -> throw new IllegalArgumentException("不支持的文件类型: " + ext + "，请转换为 PDF 或 DOCX 后重试");
        };
    }

    private String parsePdf(Path path) throws IOException {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String parseDocx(Path path) throws IOException {
        try (FileInputStream fis = new FileInputStream(path.toFile());
             XWPFDocument doc = new XWPFDocument(fis)) {

            StringBuilder sb = new StringBuilder();

            // 1. 提取普通段落
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.trim().isEmpty()) {
                    sb.append(text).append("\n");
                }
            }

            // 2. 【关键修复】提取表格内容
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

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    private String parseText(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public List<String> chunk(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        String[] paragraphs = text.split("\n\\s*\n");
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;
            if (current.length() + para.length() + 1 > chunkSize) {
                if (current.length() > 0) chunks.add(current.toString().trim());
                if (overlap > 0 && current.length() > overlap) {
                    String overlapText = current.substring(Math.max(0, current.length() - overlap));
                    current = new StringBuilder(overlapText).append("\n").append(para);
                } else {
                    current = new StringBuilder(para);
                }
            } else {
                if (current.length() > 0) current.append("\n");
                current.append(para);
            }
        }
        if (current.length() > 0) chunks.add(current.toString().trim());
        return chunks;
    }

    public int estimateTokens(String text) {
        int chinese = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') chinese++;
            else if (!Character.isWhitespace(c)) other++;
        }
        return chinese + (int) (other * 0.3);
    }
}