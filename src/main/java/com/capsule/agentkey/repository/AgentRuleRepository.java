package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.AgentRule;
import com.capsule.agentkey.entity.AgentRule.RuleScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentRuleRepository extends JpaRepository<AgentRule, Long> {

    /** 用户所有 GLOBAL 规则（所有项目生效） */
    List<AgentRule> findByUserIdAndScope(Long userId, RuleScope scope);

    /** 项目所有 PROJECT 规则（该项目生效） */
    List<AgentRule> findByProjectIdAndScope(Long projectId, RuleScope scope);

    /** 会话所有 SESSION 规则 */
    List<AgentRule> findBySessionIdAndScope(Long sessionId, RuleScope scope);

    /** 会话级规则数量（用于判断是否到了 N 轮触发自动提炼） */
    long countBySessionIdAndScope(Long sessionId, RuleScope scope);

    /** 系统内置 GLOBAL 规则（userId=null，所有用户共享） */
    List<AgentRule> findByScopeAndBuiltin(RuleScope scope, boolean builtin);

    /** 后端构建 system prompt 时用：三层 enabled 规则一次性拉齐 */
    default List<AgentRule> findEffectiveRules(Long userId, Long projectId, Long sessionId) {
        List<AgentRule> result = new java.util.ArrayList<>();
        // ① 系统内置 GLOBAL（最底层，所有权重最低，用户可覆盖）
        result.addAll(findByScopeAndBuiltin(RuleScope.GLOBAL, true));
        // ② 用户自定义 GLOBAL
        if (userId != null) {
            result.addAll(findByUserIdAndScope(userId, RuleScope.GLOBAL));
        }
        // ③ 项目级
        if (projectId != null) {
            result.addAll(findByProjectIdAndScope(projectId, RuleScope.PROJECT));
        }
        // ④ 会话级
        if (sessionId != null) {
            result.addAll(findBySessionIdAndScope(sessionId, RuleScope.SESSION));
        }
        // 按 scope 排序：builtin GLOBAL → user GLOBAL → PROJECT → SESSION
        result.sort((a, b) -> {
            int sa = scopeOrder(a);
            int sb = scopeOrder(b);
            return Integer.compare(sa, sb);
        });
        return result;
    }

    private static int scopeOrder(AgentRule r) {
        if (r.isBuiltin() && r.getScope() == RuleScope.GLOBAL) return 0;
        return switch (r.getScope()) {
            case GLOBAL -> 1;
            case PROJECT -> 2;
            case SESSION -> 3;
        };
    }
}
