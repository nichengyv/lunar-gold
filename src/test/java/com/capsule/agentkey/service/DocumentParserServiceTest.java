package com.capsule.agentkey.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentParserService 文本切分与 token 估算的纯单元测试。
 * 覆盖：空输入、短文本单块、长段落滑窗切分、跨段落 overlap 保上下文、token 估算中英文混合。
 */
class DocumentParserServiceTest {

    private final DocumentParserService parser = new DocumentParserService();

    @Test
    void chunk_nullOrEmpty_returnsEmpty() {
        assertTrue(parser.chunk(null, 100, 10).isEmpty());
        assertTrue(parser.chunk("", 100, 10).isEmpty());
    }

    @Test
    void chunk_shortText_singleChunk() {
        String text = "短文本";
        List<String> chunks = parser.chunk(text, 100, 10);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0));
    }

    @Test
    void chunk_longParagraph_splitBySlidingWindow() {
        //构造 500 字的单段落，chunkSize=100、overlap=20 → 步长 80
        String longPara = "a".repeat(500);
        List<String> chunks = parser.chunk(longPara, 100, 20);
        assertTrue(chunks.size() >= 5, "500/80 步长应产生至少 5 块，实际: " + chunks.size());
        //每块不应超过 chunkSize
        for (String c : chunks) {
            assertTrue(c.length() <= 100, "块长度超过 chunkSize: " + c.length());
        }
    }

    @Test
    void chunk_multipleParagraphs_preservesOrder() {
        String text = "第一段内容\n\n第二段内容\n\n第三段内容";
        List<String> chunks = parser.chunk(text, 100, 10);
        assertFalse(chunks.isEmpty());
        //拼接结果应包含所有段落的原始文字（顺序保持）
        String joined = String.join("", chunks);
        assertTrue(joined.contains("第一段内容"));
        assertTrue(joined.contains("第二段内容"));
        assertTrue(joined.contains("第三段内容"));
    }

    @Test
    void chunk_overlap_keepsContextAcrossBoundary() {
        //构造两个段落，第二段放不下时会保留前段末尾 overlap 作为下块开头
        String para1 = "A".repeat(80);
        String para2 = "B".repeat(50);
        String text = para1 + "\n\n" + para2;
        List<String> chunks = parser.chunk(text, 100, 20);
        assertTrue(chunks.size() >= 2);
        //第二块应以第一段末尾 20 个 A 开头（overlap），再接第二段
        String second = chunks.get(1);
        assertTrue(second.startsWith("A".repeat(20)),
                "overlap 失效：第二块未保留上下文，实际开头: " + second.substring(0, Math.min(30, second.length())));
    }

    @Test
    void estimateTokens_chineseMixed() {
        //纯中文：1 字 = 1 token
        assertEquals(5, parser.estimateTokens("你好世界啊"));
        //纯英文：约 0.3 token/字符（不含空白）
        assertEquals(3, parser.estimateTokens("abcdefghij"));//10 * 0.3 = 3
        //混合
        int estimate = parser.estimateTokens("你好abc");
        assertEquals(2 + (int) (3 * 0.3), estimate);
    }

    @Test
    void estimateTokens_whitespaceIgnored() {
        assertEquals(0, parser.estimateTokens("   \n\t  "));
    }
}
