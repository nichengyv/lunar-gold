package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.ToolApproval;
import com.capsule.agentkey.repository.ToolApprovalRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 第六十八批 A2：工具调用前置审批登记表。
 *
 * ReAct 线程在危险工具执行前创建一条审批记录并把 approvalId 推给前端，
 * 然后在 CompletableFuture 上等待；前端通过 REST 提交批准/拒绝，唤醒等待线程。
 * 超过 {@link #TIMEOUT_MS} 毫秒自动拒绝，避免线程悬挂。
 */
@Component
@Slf4j
public class ToolApprovalRegistry {

    /** 审批决策 */
    public enum Decision { APPROVE, REJECT }

    /** P5-10-2：决策来源，持久化到 tool_approval.decision_source */
    public enum DecisionSource { USER, BATCH, TIMEOUT, SESSION_STOP, SYSTEM }

    /** P5-10-2：一次审批结果，来源与批次随结果一起回传给 ReAct 与前端 */
    public record Resolution(Decision decision, DecisionSource source, String batchId) {
        public static Resolution of(Decision decision, DecisionSource source) {
            return new Resolution(decision, source, null);
        }
    }

    /** 一条待审批记录 */
    public record Pending(String approvalId,
                          Long userId,
                          Long sessionId,
                          String tool,
                          String arguments,
                          String risk,
                          long expiresAt,
                          CompletableFuture<Resolution> future) {
    }

    /** 审批等待上限：先于 SSE 180 秒超时，保证能正常回写 tool 结果并继续对话 */
    public static final long TIMEOUT_MS = 120_000L;
    /** 等待轮询间隔：用户点停止时不立刻感知断连，靠这个间隔尽快退出 */
    private static final long POLL_MS = 500L;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** 会话级「总是允许」：sessionId -> 工具名集合，数据库为权威来源，内存为查询缓存 */
    private final Map<Long, Set<String>> sessionAllowed = new ConcurrentHashMap<>();
    private final ToolApprovalRepository approvalRepository;

    /** 保留给不启动 Spring 的纯内存单元测试。 */
    public ToolApprovalRegistry() {
        this.approvalRepository = null;
    }

    @Autowired
    public ToolApprovalRegistry(ToolApprovalRepository approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    /** 恢复未决审批；已过期记录直接落 REJECTED，避免重启后重新获得完整等待时间。 */
    @PostConstruct
    void restorePersistedApprovals() {
        if (approvalRepository == null) return;
        long now = System.currentTimeMillis();
        for (ToolApproval saved : approvalRepository.findByState("PENDING")) {
            if (saved.getExpiresAt() <= now) {
                // P5-10-2：重启时已过期的未决审批按超时自动拒绝，来源落 TIMEOUT
                markDecision(saved, "REJECTED", false, DecisionSource.TIMEOUT);
                continue;
            }
            Pending record = toPending(saved);
            pending.put(record.approvalId(), record);
        }
        for (ToolApproval saved : approvalRepository.findByStateAndRememberSessionTrue("APPROVED")) {
            if (saved.getSessionId() != null) {
                sessionAllowed.computeIfAbsent(saved.getSessionId(), k -> ConcurrentHashMap.newKeySet())
                        .add(saved.getToolName());
            }
        }
        log.info("审批状态恢复完成：待审批 {} 条，记住会话 {} 个", pending.size(), sessionAllowed.size());
    }

    /** 危险工具的风险等级；返回 null 表示不需要审批 */
    public static String riskOf(String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase(java.util.Locale.ROOT);
        if ("run_command".equals(name)) return "EXECUTE";
        if ("write_file".equals(name) || "edit_file".equals(name)) return "WRITE";
        if ("delete_file".equals(name)) return "WRITE";
        if (name.startsWith("mcp_")) {
            if (name.endsWith("_delete_file") || name.endsWith("_delete_directory")
                    || name.endsWith("_remove_file") || name.endsWith("_remove_directory")) {
                return "DESTRUCTIVE";
            }
            if (name.endsWith("_write_file") || name.endsWith("_edit_file")
                    || name.endsWith("_create_directory") || name.endsWith("_move_file")) {
                return "WRITE";
            }
        }
        return null;
    }

    /**
     * 带入参的风险判定：递归删除目录属于不可逆操作，
     * 即使在自动审批档位也要请用户确认，因此单独标记为 DESTRUCTIVE。
     */
    public static String riskOf(String toolName, String arguments) {
        if ("delete_file".equals(toolName)
                && arguments != null
                && arguments.matches("(?s).*\"recursive\"\\s*:\\s*true.*")) {
            return "DESTRUCTIVE";
        }
        return riskOf(toolName);
    }

    public Pending create(Long userId,
                          Long sessionId,
                          String tool,
                          String arguments,
                          String risk) {
        String approvalId = "ap-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long expiresAt = System.currentTimeMillis() + TIMEOUT_MS;
        Pending record = new Pending(approvalId, userId, sessionId, tool, arguments, risk,
                expiresAt, new CompletableFuture<>());
        pending.put(approvalId, record);
        if (approvalRepository != null) {
            ToolApproval saved = new ToolApproval();
            saved.setApprovalId(approvalId);
            saved.setUserId(userId);
            saved.setSessionId(sessionId);
            saved.setToolName(tool);
            saved.setArguments(arguments);
            saved.setRisk(risk);
            saved.setState("PENDING");
            saved.setExpiresAt(expiresAt);
            approvalRepository.save(saved);
        }
        log.info("等待工具审批: id={}, tool={}, risk={}, sessionId={}", approvalId, tool, risk, sessionId);
        return record;
    }

    /** 本会话是否已对该工具选择「总是允许」 */
    public boolean isSessionAllowed(Long sessionId, String tool) {
        if (sessionId == null) return false;
        Set<String> tools = sessionAllowed.get(sessionId);
        return tools != null && tools.contains(tool);
    }

    /**
     * 前端提交审批结果。
     *
     * @return false 表示记录不存在、已超时或被其他用户提交
     */
    public boolean resolve(String approvalId, Long userId, String decision, boolean rememberSession) {
        Pending record = pending.get(approvalId);
        if (record == null) {
            log.warn("审批记录不存在或已失效: id={}", approvalId);
            return false;
        }
        if (!record.userId().equals(userId)) {
            log.warn("审批记录归属不匹配: id={}, userId={}", approvalId, userId);
            return false;
        }
        Decision value;
        if ("APPROVE".equalsIgnoreCase(decision)) {
            value = Decision.APPROVE;
            if (rememberSession && record.sessionId() != null) {
                sessionAllowed.computeIfAbsent(record.sessionId(), k -> ConcurrentHashMap.newKeySet())
                        .add(record.tool());
                log.info("会话 {} 已记住允许工具 {}", record.sessionId(), record.tool());
            }
        } else if ("REJECT".equalsIgnoreCase(decision)) {
            value = Decision.REJECT;
        } else {
            log.warn("非法审批决策: {}", decision);
            return false;
        }
        boolean completed = record.future().complete(Resolution.of(value, DecisionSource.USER));
        if (!completed) {
            log.info("审批记录已完成，忽略重复提交: id={}", approvalId);
            return false;
        }
        persistDecision(record, value == Decision.APPROVE ? "APPROVED" : "REJECTED",
                rememberSession, DecisionSource.USER);
        return true;
    }

    /**
     * P5-10-2：批量允许的保守白名单。
     * 只放行写/编辑文件（含 MCP 同名工具）；命令、删除、移动、建目录和高危操作仍逐个确认。
     */
    public static boolean isBatchEligible(String toolName, String risk) {
        if (!"WRITE".equals(risk)) return false;
        String name = toolName == null ? "" : toolName.toLowerCase(java.util.Locale.ROOT);
        if ("write_file".equals(name) || "edit_file".equals(name)) return true;
        return name.startsWith("mcp_")
                && (name.endsWith("_write_file") || name.endsWith("_edit_file"));
    }

    /** 批量允许结果：预检失败时 accepted=false 且不批准任何一项 */
    public record BatchResult(boolean accepted, String reason, String batchId, List<String> resolvedIds) {
        static BatchResult rejected(String reason) {
            return new BatchResult(false, reason, null, List.of());
        }
    }

    /**
     * 批量允许当前会话的多个低风险审批。
     *
     * <p>安全边界在这里：所有 ID 必须属于当前用户、当前会话、仍在等待，且命中低风险白名单；
     * 任一不满足整批拒绝，绝不部分批准。预检之后并发失效的项只从 resolvedIds 中剔除。
     */
    public BatchResult resolveBatch(Long userId, Long sessionId, List<String> approvalIds) {
        if (approvalIds == null || approvalIds.isEmpty()) {
            return BatchResult.rejected("没有可批量允许的审批");
        }
        if (sessionId == null) {
            return BatchResult.rejected("缺少会话信息");
        }
        List<Pending> records = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String approvalId : approvalIds) {
            if (approvalId == null || approvalId.isBlank() || !seen.add(approvalId)) {
                return BatchResult.rejected("审批 ID 非法或重复");
            }
            Pending record = pending.get(approvalId);
            if (record == null) {
                return BatchResult.rejected("审批已失效或已处理: " + approvalId);
            }
            if (!record.userId().equals(userId)) {
                return BatchResult.rejected("审批不属于当前用户: " + approvalId);
            }
            if (!sessionId.equals(record.sessionId())) {
                return BatchResult.rejected("审批不属于当前会话: " + approvalId);
            }
            if (!isBatchEligible(record.tool(), record.risk())) {
                return BatchResult.rejected("存在不能批量允许的高危操作: " + approvalId);
            }
            records.add(record);
        }

        String batchId = "bt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<String> resolvedIds = new ArrayList<>();
        for (Pending record : records) {
            // 预检后可能被单条操作或超时抢先完成，只统计真正由本批次完成的结果
            if (record.future().complete(new Resolution(Decision.APPROVE, DecisionSource.BATCH, batchId))) {
                persistDecision(record, "APPROVED", false, DecisionSource.BATCH);
                resolvedIds.add(record.approvalId());
            }
        }
        log.info("批量允许审批: sessionId={}, batchId={}, 请求 {} 条, 实际 {} 条",
                sessionId, batchId, approvalIds.size(), resolvedIds.size());
        return new BatchResult(true, null, batchId, List.copyOf(resolvedIds));
    }

    /** P5-10-2：会话内已处理审批历史，按创建时间倒序；调用方负责先校验会话归属 */
    public List<ToolApproval> history(Long sessionId, Long userId) {
        if (approvalRepository == null || sessionId == null || userId == null) return List.of();
        return approvalRepository.findBySessionIdAndUserIdAndStateNotOrderByCreateTimeDesc(
                sessionId, userId, "PENDING");
    }

    /**
     * 阻塞等待审批结果。每 {@link #POLL_MS} 毫秒检查一次客户端是否已断开，
     * 超时或断开都按拒绝处理，并在 finally 中清理记录，避免内存泄漏。
     */
    public Resolution await(Pending record, AtomicBoolean cancelled) {
        long deadline = record.expiresAt();
        Resolution result = null;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (cancelled != null && cancelled.get()) {
                    // 停止与用户点击可能同时发生，以先完成的结果为准
                    Resolution winner = record.future().getNow(null);
                    if (winner != null) return winner;
                    log.info("客户端已断开，审批自动拒绝: id={}", record.approvalId());
                    result = Resolution.of(Decision.REJECT, DecisionSource.SESSION_STOP);
                    break;
                }
                try {
                    return record.future().get(POLL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 继续轮询：不是真的超时，只是等用户点击
                }
            }
            if (result == null) {
                Resolution winner = record.future().getNow(null);
                if (winner != null) return winner;
                log.info("审批超时，自动拒绝: id={}, tool={}", record.approvalId(), record.tool());
                result = Resolution.of(Decision.REJECT, DecisionSource.TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = Resolution.of(Decision.REJECT, DecisionSource.SYSTEM);
        } catch (ExecutionException e) {
            log.warn("审批等待异常，按拒绝处理: id={}", record.approvalId(), e);
            result = Resolution.of(Decision.REJECT, DecisionSource.SYSTEM);
        } finally {
            pending.remove(record.approvalId());
            if (!record.future().isDone()) {
                record.future().complete(result);
                persistDecision(record, "REJECTED", false, result.source());
            }
        }
        return result;
    }

    /** 用户停止生成：该会话所有待审批直接拒绝，唤醒阻塞线程 */
    public void cancelSession(Long sessionId) {
        if (sessionId == null) return;
        List<String> ids = new ArrayList<>();
        pending.forEach((id, record) -> {
            if (sessionId.equals(record.sessionId())) {
                record.future().complete(Resolution.of(Decision.REJECT, DecisionSource.SESSION_STOP));
                persistDecision(record, "REJECTED", false, DecisionSource.SESSION_STOP);
                ids.add(id);
            }
        });
        ids.forEach(pending::remove);
        if (!ids.isEmpty()) {
            log.info("会话 {} 停止生成，取消 {} 条待审批", sessionId, ids.size());
        }
    }

    private Pending toPending(ToolApproval saved) {
        return new Pending(saved.getApprovalId(), saved.getUserId(), saved.getSessionId(),
                saved.getToolName(), saved.getArguments(), saved.getRisk(), saved.getExpiresAt(),
                new CompletableFuture<>());
    }

    private void persistDecision(Pending record, String state, boolean rememberSession,
                                 DecisionSource source) {
        if (approvalRepository == null) return;
        approvalRepository.findByApprovalId(record.approvalId()).ifPresentOrElse(
                saved -> markDecision(saved, state, rememberSession, source),
                () -> log.warn("审批记录未持久化，无法写入决策: id={}", record.approvalId()));
    }

    private void markDecision(ToolApproval saved, String state, boolean rememberSession,
                              DecisionSource source) {
        saved.setState(state);
        saved.setRememberSession(rememberSession);
        saved.setDecisionTime(LocalDateTime.now());
        saved.setDecisionSource(source == null ? null : source.name());
        approvalRepository.save(saved);
    }
}
