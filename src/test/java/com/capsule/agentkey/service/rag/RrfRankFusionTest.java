package com.capsule.agentkey.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P5-07：RRF 融合的排序、去重与截断。 */
class RrfRankFusionTest {

    private final RrfRankFusion rankFusion = new RrfRankFusion(60);

    @Test
    void fusionPromotesCandidateHitByBothPaths() {
        RagCandidate a = candidate(1L, 0.90f, 0f);
        RagCandidate b = candidate(2L, 0.80f, 0f);
        RagCandidate c = candidate(3L, 0.70f, 0f);
        RagCandidate cLexical = candidate(3L, 0f, 0.90f);
        RagCandidate d = candidate(4L, 0f, 0.60f);

        List<RagCandidate> ranked = rankFusion.fuse(
                List.of(a, b, c), List.of(cLexical, d), 10);

        assertEquals(4, ranked.size());
        assertEquals(List.of(3L, 1L, 2L, 4L),
                ranked.stream().map(RagCandidate::chunkId).toList());
        assertEquals(0.70f, ranked.get(0).vectorScore());
        assertEquals(0.90f, ranked.get(0).lexicalScore());
    }

    @Test
    void deduplicatesByChunkId() {
        List<RagCandidate> ranked = rankFusion.fuse(
                List.of(candidate(7L, 0.8f, 0f)),
                List.of(candidate(7L, 0f, 0.9f)),
                10);

        assertEquals(1, ranked.size());
        assertEquals(0.8f, ranked.get(0).vectorScore());
        assertEquals(0.9f, ranked.get(0).lexicalScore());
    }

    @Test
    void keepsLexicalOrderWhenVectorPathMisses() {
        List<RagCandidate> ranked = rankFusion.fuse(
                List.of(),
                List.of(candidate(1L, 0f, 0.6f), candidate(2L, 0f, 0.5f)),
                10);

        assertEquals(List.of(1L, 2L), ranked.stream().map(RagCandidate::chunkId).toList());
    }

    @Test
    void respectsTopK() {
        List<RagCandidate> ranked = rankFusion.fuse(
                List.of(candidate(1L, 0.9f, 0f), candidate(2L, 0.8f, 0f)),
                List.of(candidate(2L, 0f, 0.8f)),
                1);

        assertEquals(1, ranked.size());
        assertEquals(2L, ranked.get(0).chunkId());
    }

    @Test
    void returnsEmptyForNonPositiveTopK() {
        assertTrue(rankFusion.fuse(List.of(candidate(1L, 0.9f, 0f)), List.of(), 0).isEmpty());
    }

    private static RagCandidate candidate(Long chunkId, float vectorScore, float lexicalScore) {
        return new RagCandidate(chunkId, 100L + chunkId, "content-" + chunkId,
                vectorScore, lexicalScore);
    }
}
