package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.AgentRunEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentRunEventRepository extends JpaRepository<AgentRunEvent, Long> {

    /** 断线续传：给定 run 和 lastSeq，拉取后续事件 */
    List<AgentRunEvent> findByRunIdAndSeqGreaterThanOrderBySeqAsc(Long runId, Integer lastSeq);

    /** 完整事件流 */
    List<AgentRunEvent> findByRunIdOrderBySeqAsc(Long runId);

    /** 写入下一条事件前查当前最大 seq；空表返回 NULL，应用层从 1 开始 */
    @Query("SELECT MAX(e.seq) FROM AgentRunEvent e WHERE e.runId = :runId")
    Integer findMaxSeqByRunId(Long runId);

    /** P5-11-2：数据库内按 Run 聚合首条模型 chunk 时间，不加载事件正文 */
    @Query("SELECT e.runId AS runId, MIN(e.createTime) AS firstChunkTime FROM AgentRunEvent e "
            + "WHERE e.runId IN :runIds "
            + "AND e.eventType = com.capsule.agentkey.entity.AgentRunEvent.EventType.MODEL_CHUNK "
            + "GROUP BY e.runId")
    List<FirstModelChunkTime> findFirstModelChunkTimes(@Param("runIds") List<Long> runIds);

    interface FirstModelChunkTime {
        Long getRunId();

        LocalDateTime getFirstChunkTime();
    }
}
