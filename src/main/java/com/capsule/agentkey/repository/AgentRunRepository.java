package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRun.RunState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    /** 同用户 + 同幂等键最多一条；返回它以判断是否重复提交 */
    Optional<AgentRun> findByUserIdAndClientRequestId(Long userId, String clientRequestId);

    /** P5-11-2：体验指标只读取当前用户最近 100 个顶层 Run（子 Run 不计入） */
    List<AgentRun> findTop100ByUserIdAndParentRunIdIsNullOrderByCreateTimeDescIdDesc(Long userId);

    /** 列出用户指定状态的顶层运行（前端历史面板用） */
    List<AgentRun> findByUserIdAndParentRunIdIsNullAndStateOrderByCreateTimeDesc(Long userId, RunState state);

    /** P5-14：任务级 ChangeSet 按会话取全部顶层 Run，新的在前 */
    List<AgentRun> findByUserIdAndParentRunIdIsNullAndSessionIdOrderByCreateTimeDescIdDesc(
            Long userId, Long sessionId);

    /** 启动时扫描所有未完成 run（含子 Run），清理已随旧进程消失的执行线程 */
    List<AgentRun> findByStateIn(List<RunState> states);

    // ---- R7c：父子 Run ----

    /** 父 Run 下的全部子 Run，按创建顺序返回 */
    List<AgentRun> findByParentRunIdOrderByIdAsc(Long parentRunId);

    /** 委派重试/续跑复用同一子 Run，避免重复创建 */
    Optional<AgentRun> findByParentRunIdAndDelegationId(Long parentRunId, String delegationId);

    long countByParentRunId(Long parentRunId);

    // ---- R1：事件序号与计划快照 ----

    /**
     * 追加事件前锁住 Run 行。
     *
     * <p>事件 seq 仍是「当前最大值 + 1」，但必须在同一事务里先拿到行级排他锁，
     * 否则两个并发追加会读到同一个 max(seq)，后写入者触发 uk_run_event_seq 唯一约束。
     * 锁持有到事务提交，并发追加自然串行；唯一约束保留为兜底不变量。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.transaction.annotation.Transactional
    @Query("SELECT r FROM AgentRun r WHERE r.id = :runId")
    Optional<AgentRun> lockForEventAppend(@Param("runId") Long runId);

    /**
     * 只更新计划快照列。
     *
     * <p>不能用实体 save()：Hibernate 默认整行 UPDATE，会把并发状态迁移刚写入的 state 覆盖回旧值。
     */
    @Modifying(flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AgentRun r SET r.planJson = :planJson WHERE r.id = :runId")
    int updatePlanJson(@Param("runId") Long runId, @Param("planJson") String planJson);

    /** R7b：只更新断点快照列，理由同 {@link #updatePlanJson}——避免覆盖并发写入的 state。 */
    @Modifying(flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AgentRun r SET r.checkpointJson = :checkpointJson WHERE r.id = :runId")
    int updateCheckpointJson(@Param("runId") Long runId, @Param("checkpointJson") String checkpointJson);

    /** 桌面重启后扫描未完成的顶层 run，恢复 WAITING_APPROVAL / RUNNING */
    List<AgentRun> findByUserIdAndParentRunIdIsNullAndStateIn(Long userId, List<RunState> states);

    /** P5-12-2：会话列表的状态点——每个会话取最近一条顶层 run */
    @Query("SELECT r FROM AgentRun r WHERE r.userId = :userId AND r.sessionId IN :sessionIds "
            + "AND r.parentRunId IS NULL "
            + "AND r.id = (SELECT MAX(r2.id) FROM AgentRun r2 "
            + "WHERE r2.sessionId = r.sessionId AND r2.userId = :userId AND r2.parentRunId IS NULL)")
    List<AgentRun> findLatestByUserIdAndSessionIdIn(@Param("userId") Long userId,
                                                    @Param("sessionIds") List<Long> sessionIds);

    // ---- P4-07：会话维度的 token 汇总（只累计顶层 Run，子用量已汇总进父 Run） ----

    @Query("SELECT COALESCE(SUM(r.promptTokens), 0) FROM AgentRun r "
            + "WHERE r.sessionId = :sessionId AND r.userId = :userId AND r.parentRunId IS NULL")
    Long sumPromptTokensBySession(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(r.completionTokens), 0) FROM AgentRun r "
            + "WHERE r.sessionId = :sessionId AND r.userId = :userId AND r.parentRunId IS NULL")
    Long sumCompletionTokensBySession(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(r.totalTokens), 0) FROM AgentRun r "
            + "WHERE r.sessionId = :sessionId AND r.userId = :userId AND r.parentRunId IS NULL")
    Long sumTotalTokensBySession(@Param("sessionId") Long sessionId, @Param("userId") Long userId);
}
