package com.capsule.agentkey.controller;

import com.capsule.agentkey.entity.AgentRule;
import com.capsule.agentkey.entity.AgentRule.RuleScope;
import com.capsule.agentkey.repository.AgentRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent 规则 / Instruction CRUD（对标 Cursor .cursorrules + Trae @Rules）
 *
 * 三层 scope：
 *   GLOBAL  — 用户级（所有项目/会话生效），由后端 SecurityContext 自动取 userId，前端无需传
 *   PROJECT — 项目级（该项目所有会话生效），前端传 projectId
 *   SESSION — 会话级（仅当前会话生效），前端传 sessionId
 */
@RestController
@RequestMapping("/api/agent/rules")
@RequiredArgsConstructor
@Slf4j
public class AgentRuleController {

    private final AgentRuleRepository ruleRepo;

    /** 与其他 Controller 统一：从 SecurityContext 拿当前登录用户 */
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        return null;
    }

    /**
     * 拉规则列表。优先级：sessionId > projectId > 当前用户的全局规则。
     * 前端不拉全局规则时不传任何参数也能拿到当前用户的 GLOBAL 规则。
     * GLOBAL 分组额外包含系统内置规则（userId=null），builtin=true 不可删。
     */
    @GetMapping
    public ResponseEntity<List<AgentRule>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long sessionId
    ) {
        if (sessionId != null) {
            return ResponseEntity.ok(ruleRepo.findBySessionIdAndScope(sessionId, RuleScope.SESSION));
        }
        if (projectId != null) {
            return ResponseEntity.ok(ruleRepo.findByProjectIdAndScope(projectId, RuleScope.PROJECT));
        }
        // GLOBAL 分组 = 系统内置 + 当前用户自定义
        List<AgentRule> globals = new java.util.ArrayList<>();
        globals.addAll(ruleRepo.findByScopeAndBuiltin(RuleScope.GLOBAL, true));
        Long userId = getCurrentUserId();
        if (userId != null) {
            globals.addAll(ruleRepo.findByUserIdAndScope(userId, RuleScope.GLOBAL));
        }
        return ResponseEntity.ok(globals);
    }

    /** 构建 system prompt 时用：一次性拉齐三层 enabled 规则，按 GLOBAL→PROJECT→SESSION 排序 */
    @GetMapping("/effective")
    public ResponseEntity<List<AgentRule>> effective(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long sessionId
    ) {
        return ResponseEntity.ok(ruleRepo.findEffectiveRules(userId, projectId, sessionId));
    }

    /** 新建规则。scope 由哪一个 id 非空自动推断；全局规则自动注入当前用户 */
    @PostMapping
    @Transactional
    public ResponseEntity<AgentRule> create(@RequestBody AgentRule rule) {
        if (rule.getScope() == null) {
            if (rule.getSessionId() != null) rule.setScope(RuleScope.SESSION);
            else if (rule.getProjectId() != null) rule.setScope(RuleScope.PROJECT);
            else {
                // 全局规则：自动绑定当前登录用户
                rule.setScope(RuleScope.GLOBAL);
                rule.setUserId(getCurrentUserId());
            }
        }
        rule.setBuiltin(false);
        rule.setEnabled(true);
        return ResponseEntity.ok(ruleRepo.save(rule));
    }

    /** 改规则内容 / enabled 开关 */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<AgentRule> update(@PathVariable Long id, @RequestBody AgentRule patch) {
        return ruleRepo.findById(id).map(r -> {
            if (r.isBuiltin()) {
                // 内置规则：允许改 enabled，不允许改其他字段
                if (patch.isEnabled() != r.isEnabled()) r.setEnabled(patch.isEnabled());
            } else {
                if (patch.getName() != null) r.setName(patch.getName());
                if (patch.getDescription() != null) r.setDescription(patch.getDescription());
                if (patch.getContent() != null) r.setContent(patch.getContent());
                // enabled 允许改
                r.setEnabled(patch.isEnabled());
            }
            return ResponseEntity.ok(ruleRepo.save(r));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** 切开关（单独一个 endpoint 给前端 toggle 调用，避免传整个 body） */
    @PatchMapping("/{id}/toggle")
    @Transactional
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", true);
        return ruleRepo.findById(id).<ResponseEntity<Map<String, Object>>>map(r -> {
            r.setEnabled(enabled);
            ruleRepo.save(r);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = (Map) Map.of("id", id, "enabled", enabled);
            return ResponseEntity.ok(resp);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** 删除（builtin 规则不允许删） */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return ruleRepo.findById(id).map(r -> {
            if (r.isBuiltin()) {
                return ResponseEntity.status(403).<Void>build();
            }
            ruleRepo.delete(r);
            return ResponseEntity.ok().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }
}
