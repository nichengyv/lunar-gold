package com.capsule.agentkey.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class VectorStoreService {

    //存入内存的数据本体
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
    //record类型让编译器为类里变量自动生成包含这些字段，构造器，getter，setter，equal，hashcode，tostring方法的类
    public record SearchResult(
            Long chunkId,
            Long documentId,
            String content,//切片后的原文
            float score//余弦相似度计算结果
    ) {}

    //内存存储容器    k-用户id  v-该用户全部切片向量集合（因为是内存存储，所以当服务器重启，就会全部清空）
    private final Map<Long, List<VectorRecord>> userVectors = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("✅ 内存向量存储引擎已启动（生产环境建议迁移至 pgvector）");
    }

    //把新向量记录r，归类存放进对应用户列表
    public void addVectors(List<VectorRecord> records) {
        for (VectorRecord r : records) {//遍历recrds，对于每条r，获取其userid
            //k代表key字段，也就是这里的r.userid。根据这里的方法，取去map查找，若k不存在，则返回后面的代码
            // ——也就是创建新list。若存在，则什么都不做，直接把当前的VectorRecord，add进现有的user的map
            userVectors.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);
        }
    }

    public void deleteByDocumentId(Long userId, Long documentId) {
        deleteByDocumentId(userId, documentId, "knowledge");
    }

    public void deleteByDocumentId(Long userId, Long documentId, String sourceType) {
        List<VectorRecord> list = userVectors.get(userId);
        if (list != null) {
            list.removeIf(r -> r.documentId().equals(documentId) && r.sourceType().equals(sourceType));
        }
    }

    public void deleteByUserId(Long userId) {
        userVectors.remove(userId);
    }

    public List<SearchResult> search(Long userId, float[] queryVec, int topK) {
        return search(userId, queryVec, topK, null, null);
    }

    public List<SearchResult> search(Long userId, float[] queryVec, int topK,
                                     Long documentId, String sourceType) {
        //getOrDefault（参数，兜底默认值），根据参数在map里查找对应数据，没有就返回后面的默认值
        List<VectorRecord> candidates = userVectors.getOrDefault(userId, Collections.emptyList());
        if (candidates.isEmpty() || queryVec == null || queryVec.length == 0) {
            return Collections.emptyList();
        }

        //流式处理
        return candidates.stream()
                //filter，保留括号内为判断真的参数   变量->条件
                //若documentId不为空，则检索该文档，null则检索该用户全部文档
                //若sourcetype不为空，则限定数据类型，null则不限定
                .filter(r -> (documentId == null || r.documentId().equals(documentId))
                        && (sourceType == null || r.sourceType().equals(sourceType)))
                //map（原对象->新对象）
                .map(r -> {
                    //cosineSimilarity拿查询向量quervec和当前向量做计算，计算余弦相似度，算出分数存入score
                    float score = cosineSimilarity(queryVec, r.vector());
                    //这里新建Search并返回，所以从这里开始数据的类型换了
                    return new SearchResult(r.chunkId(), r.documentId(), r.content(), score);
                })
                .filter(r -> r.score() > 0.5f)//相似度阈值过滤：分数小于等于0.5直接丢弃
                .sorted((a, b) -> Float.compare(b.score(), a.score()))//排序：从高到低，相似度降序
                .limit(topK)//截取前topk条
                .collect(Collectors.toList());//收集封装成list返回
    }

    public int countByDocumentId(Long userId, Long documentId) {
        List<VectorRecord> list = userVectors.get(userId);
        if (list == null) return 0;
        return (int) list.stream()
                .filter(r -> r.documentId().equals(documentId))
                .count();
    }

    //计算相似度score
    //参数a是提问向量，参数b是文档切片向量r.vector
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0f;//校验两向量维度是否不一样，若不一样，则返回相似度：0（必须是同一个Embedding模型生成的，维度才会相等）
        //dot：点积，分子   normA：向量a各项平方累加   normB：同a
        //循环遍历向量每一位，对应位置相乘累加，求出点积
        //normA，B：各自分量平方求和
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        //若向量全是0，无有效数据，返回相似度0，防止除0报错
        if (normA == 0 || normB == 0) return 0f;
        //余弦公式  点积/两向量模长乘积  返回值范围-1~1，业务上只关系0~1，靠近1则语义相似
        return dot / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }
}