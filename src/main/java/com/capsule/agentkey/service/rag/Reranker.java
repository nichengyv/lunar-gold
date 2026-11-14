package com.capsule.agentkey.service.rag;

import java.util.List;

/**
 * P5-07：混合检索结果融合的可插拔抽象。
 *
 * <p>v1 使用 RRF 融合向量与词法排名；后续接入模型 reranker 时新增实现即可，
 * 文档检索与 prompt 拼装不需要改。</p>
 */
public interface Reranker {

    /**
     * @param vectorHits  向量召回结果，按相似度降序
     * @param lexicalHits 词法召回结果，按词法分降序
     * @param topK        最终保留条数
     */
    List<RagCandidate> rerank(List<RagCandidate> vectorHits,
                              List<RagCandidate> lexicalHits,
                              int topK);
}
