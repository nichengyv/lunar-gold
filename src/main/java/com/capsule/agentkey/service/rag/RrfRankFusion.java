package com.capsule.agentkey.service.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P5-07：Reciprocal Rank Fusion（RRF）融合向量与词法召回。
 *
 * <p>两路分数不可直接比较（余弦相似度 vs pg_trgm 词法分），RRF 只使用排名：
 * {@code score = Σ 1 / (k + rank)}。同一片段被两路同时命中时得分叠加，
 * 从而让“语义相近且精确词命中”的证据排在单路结果之前。</p>
 *
 * <p>k 默认 60，与常见 RRF 实践一致。本类只做排名融合，不是内容精排模型。</p>
 */
@Component
public class RrfRankFusion implements RankFusion {

    private final int k;

    public RrfRankFusion(@Value("${rag.rrf-k:60}") int k) {
        this.k = Math.max(1, k);
    }

    @Override
    public List<RagCandidate> fuse(List<RagCandidate> vectorHits,
                                   List<RagCandidate> lexicalHits,
                                   int topK) {
        if (topK < 1) {
            return List.of();
        }
        Map<String, Entry> merged = new LinkedHashMap<>();
        accumulate(merged, vectorHits, true);
        accumulate(merged, lexicalHits, false);

        Comparator<Entry> order = Comparator
                .comparingDouble(Entry::fusion).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (Entry e) -> e.candidate.displayScore()).reversed())
                .thenComparing(e -> e.candidate.documentId(),
                        Comparator.nullsLast(Long::compareTo))
                .thenComparing(e -> e.candidate.chunkId(),
                        Comparator.nullsLast(Long::compareTo));

        List<RagCandidate> ranked = new ArrayList<>(merged.size());
        merged.values().stream()
                .sorted(order)
                .limit(topK)
                .forEach(entry -> ranked.add(entry.toCandidate()));
        return ranked;
    }

    private void accumulate(Map<String, Entry> merged, List<RagCandidate> hits, boolean vectorRank) {
        if (hits == null) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            RagCandidate hit = hits.get(i);
            if (hit == null) {
                continue;
            }
            Entry entry = merged.computeIfAbsent(hit.key(), key -> new Entry(hit));
            if (vectorRank) {
                entry.candidate = new RagCandidate(entry.candidate.chunkId(),
                        entry.candidate.documentId(), entry.candidate.content(),
                        Math.max(entry.candidate.vectorScore(), hit.vectorScore()),
                        entry.candidate.lexicalScore());
            } else {
                entry.candidate = new RagCandidate(entry.candidate.chunkId(),
                        entry.candidate.documentId(), entry.candidate.content(),
                        entry.candidate.vectorScore(),
                        Math.max(entry.candidate.lexicalScore(), hit.lexicalScore()));
            }
            entry.fusion += 1.0 / (k + i + 1);
        }
    }

    private static final class Entry {
        private RagCandidate candidate;
        private double fusion;

        private Entry(RagCandidate candidate) {
            this.candidate = candidate;
        }

        private double fusion() {
            return fusion;
        }

        private RagCandidate toCandidate() {
            return new RagCandidate(candidate.chunkId(), candidate.documentId(),
                    candidate.content(), candidate.vectorScore(), candidate.lexicalScore());
        }
    }
}
