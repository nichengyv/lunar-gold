package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.AgentRun;
import com.capsule.agentkey.entity.AgentRun.RunState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    /** 同用户 + 同幂等键最多一条；返回它以判断是否重复提交 */
    Optional<AgentRun> findByUserIdAndClientRequestId(Long userId, String clientRequestId);

    /** P5-11-2：体验指标只读取当前用户最近 100 个 Run */
    List<AgentRun> findTop100ByUserIdOrderByCreateTimeDescIdDesc(Long userId);

    /** 列出用户指定状态的运行（前端历史面板用） */
    List<AgentRun> findByUserIdAndStateOrderByCreateTimeDesc(Long userId, RunState state);

    /** P5-14：任务级 ChangeSet 按会话取全部 Run，新的在前 */
    List<AgentRun> findByUserIdAndSessionIdOrderByCreateTimeDescIdDesc(Long userId, Long sessionId);

    /** 启动时扫描所有未完成 run，清理已随旧进程消失的执行线程 */
    List<AgentRun> findByStateIn(List<RunState> states);

    /** 桌面重启后扫描未完成的 run，恢复 WAITING_APPROVAL / RUNNING */
    List<AgentRun> findByUserIdAndStateIn(Long userId, List<RunState> states);

    /** P5-12-2：会话列表的状态点——每个会话取最近一条 run */
    @Query("SELECT r FROM AgentRun r WHERE r.userId = :userId AND r.sessionId IN :sessionIds "
            + "AND r.id = (SELECT MAX(r2.id) FROM AgentRun r2 "
            + "WHERE r2.sessionId = r.sessionId AND r2.userId = :userId)")
    List<AgentRun> findLatestByUserIdAndSessionIdIn(@Param("userId") Long userId,
                                                    @Param("sessionIds") List<Long> sessionIds);

    // ---- P4-07：会话维度的 token 汇总 ----

    @Query("SELECT COALESCE(SUM(r.promptTokens), 0) FROM AgentRun r "
            + "WHERE r.sessionId = :sessionId AND r.userId = :userId")
    Long sumPromptTokensBySession(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(r.completionTokens), 0) FROM AgentRun r "
            + "WHERE r.sessionId = :sessionId AND r.userId = :userId")
    Long sumCompletionTokensBySession(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(r.totalTokens), 0) FROM AgentRun r "
            + "WHERE r.sessionId = :sessionId AND r.userId = :userId")
    Long sumTotalTokensBySession(@Param("sessionId") Long sessionId, @Param("userId") Long userId);
}
