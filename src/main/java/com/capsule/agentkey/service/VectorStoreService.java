package com.capsule.agentkey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 PostgreSQL + pgvector 的向量存储实现（替代原"内存 Map + JSON 快照"方案）。
 *
 * 为什么放弃内存存储：
 * 1. 应用重启即丢向量（快照只是补救措施），数据库元数据显示"可用"但检索为空
 * 2. 无法水平扩展：多实例部署时各实例内存中的向量互不一致
 * 3. 向量与业务表同库后，删除文档时元数据与向量在同一事务中删除，杜绝脏数据
 *
 * 检索原理：
 * - embedding <=> ? 是 pgvector 的余弦距离运算符（0=方向完全相同，2=完全相反）
 * - 用 1 - 距离 转换成相似度 score（1=完全相同，-1=完全相反），与原内存版
 *   cosineSimilarity 的取值语义一致，调用方无感知
 * - 建表 SQL：CREATE TABLE vector_record (..., embedding vector(1024), ...)，
 *   并建 HNSW 索引 CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)
 */
@Service
@Slf4j
public class VectorStoreService {

    // JdbcTemplate 由 Spring 根据 datasource 自动装配，用于执行带 pgvector 专有运算符的原生 SQL
    private final JdbcTemplate jdbc;

    public VectorStoreService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    //存入内存的数据本体（对外数据结构保持不变，调用方无需修改）
    public record VectorRecord(
            Long chunkId,//切片编号
            Long documentId,//所属文档id
            Long userId,//所属用户id
            String content,//切片原文文本
            float[] vector,//向量浮点数组（用于数学计算）
            String sourceType//数据来源标记，默认knwledge
    ) {

        public VectorRecord(Long chunkId, Long documentId, Long userId, String content, float[] vector) {
            this(chunkId, documentId, userId, content, vector, "knowledge");
        }
    }

    //检索之后对外返回结果
    public record SearchResult(
            Long chunkId,
            Long documentId,
            String content,//切片后的原文
            float score//余弦相似度计算结果
    ) {}

    //把新向量记录r，批量写入 vector_record 表
    public void addVectors(List<VectorRecord> records) {
        if (records == null || records.isEmpty()) return;

        // ?::vector 是 PostgreSQL 的类型强转语法：把参数从文本转成 vector 类型。
        // float[] 先序列化成 pgvector 的文本格式 "[0.1,0.2,...]"，入库时由 PG 解析
        String sql = """
                INSERT INTO vector_record (user_id, document_id, chunk_id, content, source_type, embedding)
                VALUES (?, ?, ?, ?, ?, ?::vector)
                """;
        jdbc.batchUpdate(sql, records, records.size(), (ps, r) -> {
            ps.setLong(1, r.userId());
            ps.setLong(2, r.documentId());
            ps.setObject(3, r.chunkId());//chunkId 允许为 null
            ps.setString(4, r.content());
            ps.setString(5, r.sourceType());
            ps.setString(6, toVectorLiteral(r.vector()));
        });
        log.info("✅ {} 条向量已写入 PostgreSQL", records.size());
    }

    public void deleteByDocumentId(Long userId, Long documentId) {
        deleteByDocumentId(userId, documentId, "knowledge");
    }

    //删除指定文档的全部向量。向量与业务表同库后，调用方可把此删除与文档元数据删除放在同一事务中
    public void deleteByDocumentId(Long userId, Long documentId, String sourceType) {
        jdbc.update("DELETE FROM vector_record WHERE user_id = ? AND document_id = ? AND source_type = ?",
                userId, documentId, sourceType);
    }

    public void deleteByUserId(Long userId) {
        jdbc.update("DELETE FROM vector_record WHERE user_id = ?", userId);
    }

    public List<SearchResult> search(Long userId, float[] queryVec, int topK) {
        return search(userId, queryVec, topK, null, null);
    }

    //余弦相似度检索：由 pgvector 的 HNSW 索引加速，替代原内存版的 O(N) 暴力扫描
    public List<SearchResult> search(Long userId, float[] queryVec, int topK,
                                     Long documentId, String sourceType) {
        if (queryVec == null || queryVec.length == 0) return Collections.emptyList();

        //动态拼接过滤条件：documentId / sourceType 为 null 时不限定（对应原内存版的 filter 逻辑）
        StringBuilder sql = new StringBuilder("""
                SELECT chunk_id, document_id, content,
                       1 - (embedding <=> CAST(? AS vector)) AS score
                FROM vector_record
                WHERE user_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(toVectorLiteral(queryVec));//查询向量同样以文本形式传入并强转
        params.add(userId);
        if (documentId != null) {
            sql.append(" AND document_id = ?");
            params.add(documentId);
        }
        if (sourceType != null) {
            sql.append(" AND source_type = ?");
            params.add(sourceType);
        }
        //按相似度从高到低取前 topK 条（score 是 SELECT 别名，PG 支持按别名排序）
        sql.append(" ORDER BY score DESC LIMIT ?");
        params.add(topK);

        return jdbc.query(sql.toString(), (rs, i) -> new SearchResult(
                        rs.getObject("chunk_id", Long.class),
                        rs.getLong("document_id"),
                        rs.getString("content"),
                        rs.getFloat("score")),
                params.toArray())
                .stream()
                //相似度阈值过滤：分数小于等于0.3直接丢弃（语义检索阈值不宜过高，否则相关但表述不同的内容会被误杀）
                .filter(r -> r.score() > 0.3f)
                .collect(Collectors.toList());
    }

    public int countByDocumentId(Long userId, Long documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vector_record WHERE user_id = ? AND document_id = ?",
                Integer.class, userId, documentId);
        return count != null ? count : 0;
    }

    //把 float[] 序列化成 pgvector 的文本格式 "[0.1,0.2,...]"（入库与查询都用这个格式）
    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
