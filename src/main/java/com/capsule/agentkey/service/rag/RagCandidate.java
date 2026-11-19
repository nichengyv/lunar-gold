package com.capsule.agentkey.service.rag;

/**
 * P5-07 混合检索候选：同一切片可能同时被向量召回和词法召回命中。
 *
 * <p>两个分数分开保留，便于门槛判断与前端展示；多路排名融合由 {@link RankFusion} 负责。</p>
 */
public record RagCandidate(
        Long chunkId,
        Long documentId,
        String content,
        float vectorScore,
        float lexicalScore) {

    /** 对外展示分数：优先向量相似度，纯词法命中时用词法分 */
    public float displayScore() {
        return vectorScore > 0f ? vectorScore : lexicalScore;
    }

    /** 去重键：知识库切片按 chunkId，附件等无 chunkId 的记录退化为文档 + 内容 */
    public String key() {
        if (chunkId != null) {
            return "c:" + chunkId;
        }
        return "d:" + documentId + ":" + Integer.toHexString(content == null ? 0 : content.hashCode());
    }
}
