package com.capsule.agentkey.eval.rag;

import java.io.IOException;
import java.util.List;

/**
 * P5-07-2：检索评测的向量来源抽象。
 *
 * <p>默认实现为 {@link SimulatedEmbedding}，不测量具体 embedding 模型；
 * 设置 RAG_EVAL_EMBEDDING_KEY 后可切换到真实模型，报告会记录实际使用的模式。</p>
 */
@FunctionalInterface
interface EmbeddingProvider {

    float[] embed(String text, List<String> concepts) throws IOException;
}
