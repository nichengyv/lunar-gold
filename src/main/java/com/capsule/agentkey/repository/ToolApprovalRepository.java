package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.ToolApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ToolApprovalRepository extends JpaRepository<ToolApproval, Long> {
    Optional<ToolApproval> findByApprovalId(String approvalId);
    List<ToolApproval> findByState(String state);
    List<ToolApproval> findByStateAndRememberSessionTrue(String state);
    /** P5-11-2：当前用户最近 100 条已决策审批，不区分手动、批量或自动来源 */
    List<ToolApproval> findTop100ByUserIdAndDecisionTimeIsNotNullOrderByCreateTimeDescIdDesc(Long userId);
    /** P5-10-2：会话内已处理审批（历史），按创建时间倒序 */
    List<ToolApproval> findBySessionIdAndUserIdAndStateNotOrderByCreateTimeDesc(
            Long sessionId, Long userId, String state);
}
