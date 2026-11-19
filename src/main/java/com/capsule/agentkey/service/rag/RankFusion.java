package com.capsule.agentkey.service.rag;

import java.util.List;

/**
 * P5-07：多路召回结果的融合（fusion）抽象。
 *
 * <p>本层只合并向量与词法排名，不读取切片内容、不调用模型；v1 使用 RRF。
 * 行业语境中的模型 reranker 属于融合之后的内容精排，不是本接口的实现方向；
 * 若未来引入，应作为独立环节接收本层输出的单一候选列表。</p>
 */
public interface RankFusion {

    /**
     * @param vectorHits  向量召回结果，按相似度降序
     * @param lexicalHits 词法召回结果，按词法分降序
     * @param topK        最终保留条数
     */
    List<RagCandidate> fuse(List<RagCandidate> vectorHits,
                            List<RagCandidate> lexicalHits,
                            int topK);
}
