package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.PendingFileChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PendingFileChangeRepository extends JpaRepository<PendingFileChange, Long> {
    List<PendingFileChange> findByProjectIdAndStatusOrderByCreateTimeDesc(Long projectId, String status);

    List<PendingFileChange> findByProjectIdOrderByCreateTimeDesc(Long projectId);

    /** 第六十八批 A3：按会话 + 轮次取待审改动（新的在前，保证同文件多次修改先回滚最新一次） */
    List<PendingFileChange> findBySessionIdAndTurnIdAndStatusOrderByCreateTimeDesc(
            Long sessionId, String turnId, String status);

    /** 批量保留/撤销：按 projectId 改所有 PENDING */
    long countByProjectIdAndStatus(Long projectId, String status);
}
