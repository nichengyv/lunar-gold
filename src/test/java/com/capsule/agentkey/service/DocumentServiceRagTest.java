package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.Document;
import com.capsule.agentkey.entity.DocumentChunk;
import com.capsule.agentkey.repository.DocumentChunkRepository;
import com.capsule.agentkey.repository.DocumentRepository;
import com.capsule.agentkey.service.rag.RrfRankFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P5-07：混合检索编排、门槛与引用字段。 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceRagTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentChunkRepository chunkRepository;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private DocumentProcessingService processingService;
    @Mock
    private ProjectService projectService;

    private DocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DocumentService(documentRepository, chunkRepository, embeddingService,
                vectorStoreService, processingService, projectService, new RrfRankFusion(60));
        ReflectionTestUtils.setField(service, "minScore", 0.5f);
        ReflectionTestUtils.setField(service, "gateScore", 0.55f);
        ReflectionTestUtils.setField(service, "hybridEnabled", true);
        ReflectionTestUtils.setField(service, "lexicalMinScore", 0.3f);
        ReflectionTestUtils.setField(service, "lexicalGateScore", 0.35f);

        when(processingService.resolveEmbeddingKey(1L)).thenReturn("key");
        when(embeddingService.embedSingle(eq("key"), anyString())).thenReturn(new float[]{0.1f, 0.2f});
    }

    @Test
    void lexicalHitRescuesQueryThatVectorMissed() throws Exception {
        when(vectorStoreService.searchForProject(eq(1L), eq(2L), any(float[].class), eq(3), anyFloat()))
                .thenReturn(List.of());
        when(vectorStoreService.searchLexicalForProject(eq(1L), eq(2L), eq("ACME-7741 的规格"),
                eq(3), anyFloat(), isNull()))
                .thenReturn(List.of(new VectorStoreService.SearchResult(
                        11L, 21L, "规格: ACME-7741，尺寸 10x20", 0.62f)));
        when(documentRepository.findAllById(List.of(21L)))
                .thenReturn(List.of(document(21L, "spec.pdf")));
        when(chunkRepository.findAllById(List.of(11L)))
                .thenReturn(List.of(chunk(11L, 2)));

        DocumentService.RagResult result = service.retrieveContext(1L, 2L, "ACME-7741 的规格", 3);

        assertTrue(result.context().contains("ACME-7741"), result.context());
        assertEquals(1, result.sources().size());
        DocumentService.Source source = result.sources().get(0);
        assertEquals(21L, source.documentId());
        assertEquals(11L, source.chunkId());
        assertEquals(2, source.chunkIndex());
        assertEquals("spec.pdf", source.fileName());
        assertEquals(0.62f, source.score());
    }

    @Test
    void weakVectorAndWeakLexicalAreRejectedByGate() throws Exception {
        when(vectorStoreService.searchForProject(eq(1L), eq(2L), any(float[].class), eq(3), anyFloat()))
                .thenReturn(List.of(new VectorStoreService.SearchResult(1L, 2L, "weak", 0.40f)));
        when(vectorStoreService.searchLexicalForProject(eq(1L), eq(2L), eq("question"),
                eq(3), anyFloat(), isNull()))
                .thenReturn(List.of(new VectorStoreService.SearchResult(3L, 4L, "weak lexical", 0.10f)));

        DocumentService.RagResult result = service.retrieveContext(1L, 2L, "question", 3);

        assertTrue(result.context().isEmpty());
        assertTrue(result.sources().isEmpty());
    }

    @Test
    void targetedDocumentsSkipOverallGate() throws Exception {
        when(vectorStoreService.searchForProject(eq(1L), eq(2L), any(float[].class),
                eq(3), anyFloat(), eq(List.of(21L))))
                .thenReturn(List.of());
        when(vectorStoreService.searchLexicalForProject(eq(1L), eq(2L), eq("question"),
                eq(3), anyFloat(), eq(List.of(21L))))
                .thenReturn(List.of(new VectorStoreService.SearchResult(
                        11L, 21L, "点名的资料片段", 0.20f)));
        when(documentRepository.findAllById(List.of(21L)))
                .thenReturn(List.of(document(21L, "named.md")));
        when(chunkRepository.findAllById(List.of(11L)))
                .thenReturn(List.of(chunk(11L, 0)));

        DocumentService.RagResult result = service.retrieveContext(
                1L, 2L, "question", 3, List.of(21L));

        assertFalse(result.sources().isEmpty());
        assertEquals("named.md", result.sources().get(0).fileName());
    }

    @Test
    void hybridDisabledKeepsVectorOnlyBehavior() throws Exception {
        ReflectionTestUtils.setField(service, "hybridEnabled", false);
        when(vectorStoreService.searchForProject(eq(1L), eq(2L), any(float[].class), eq(3), anyFloat()))
                .thenReturn(List.of());

        DocumentService.RagResult result = service.retrieveContext(1L, 2L, "question", 3);

        assertTrue(result.context().isEmpty());
        verify(vectorStoreService, never()).searchLexicalForProject(
                anyLong(), anyLong(), anyString(), eq(3), anyFloat(), any());
    }

    private static Document document(Long id, String fileName) {
        Document document = new Document();
        document.setId(id);
        document.setFileName(fileName);
        return document;
    }

    private static DocumentChunk chunk(Long id, int chunkIndex) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(id);
        chunk.setChunkIndex(chunkIndex);
        return chunk;
    }
}
