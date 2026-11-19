package com.capsule.agentkey.eval.rag;

import java.util.List;

/**
 * P5-07-2：由 concept 生成确定性 1024 维单位向量，模拟 embedding 的语义召回。
 *
 * <p>只用于离线评测向量路径与词法路径的融合收益，不测量 bge-m3 本身：
 * 同一个 concept 的切片与查询得到相同分量，因此语义相关但用词不同的查询能命中；
 * 编号、函数名等精确词不会体现在向量里，由 pg_trgm 词法路径召回。</p>
 */
final class SimulatedEmbedding implements EmbeddingProvider {

    static final int DIMENSION = 1024;
    private static final int DIMS_PER_CONCEPT = 3;

    @Override
    public float[] embed(String text, List<String> concepts) {
        return vector(concepts);
    }

    static float[] vector(List<String> concepts) {
        float[] vector = new float[DIMENSION];
        if (concepts == null || concepts.isEmpty()) {
            return vector;
        }
        for (String concept : concepts) {
            long base = fnv1a(concept);
            for (int i = 0; i < DIMS_PER_CONCEPT; i++) {
                long mixed = mix(base + i * 0x9E3779B97F4A7C15L);
                int dimension = Math.floorMod(mixed, DIMENSION);
                vector[dimension] += ((mixed >>> 40) & 1L) == 0L ? 1f : -1f;
            }
        }
        double norm = 0d;
        for (float value : vector) {
            norm += (double) value * value;
        }
        if (norm == 0d) {
            return vector;
        }
        float inverse = (float) (1d / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inverse;
        }
        return vector;
    }

    private static long fnv1a(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix(long value) {
        long z = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
