package com.capsule.agentkey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

    /** 一条待审批记录 */
    public record Pending(String approvalId,
                          Long userId,
                          Long sessionId,
                          String tool,
                          String arguments,
                          String risk,
                          long expiresAt,
                          CompletableFuture<Decision> future) {
    }

    /** 审批等待上限：先于 SSE 180 秒超时，保证能正常回写 tool 结果并继续对话 */
    public static final long TIMEOUT_MS = 120_000L;
    /** 等待轮询间隔：用户点停止时不立刻感知断连，靠这个间隔尽快退出 */
    private static final long POLL_MS = 500L;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** 会话级「总是允许」：sessionId -> 工具名集合，仅进程内存，重启即失效 */
    private final Map<Long, Set<String>> sessionAllowed = new ConcurrentHashMap<>();

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
        boolean completed = record.future().complete(value);
        if (!completed) {
            log.info("审批记录已完成，忽略重复提交: id={}", approvalId);
        }
        return completed;
    }

    /**
     * 阻塞等待审批结果。每 {@link #POLL_MS} 毫秒检查一次客户端是否已断开，
     * 超时或断开都按拒绝处理，并在 finally 中清理记录，避免内存泄漏。
     */
    public Decision await(Pending record, AtomicBoolean cancelled) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (cancelled != null && cancelled.get()) {
                    log.info("客户端已断开，审批自动拒绝: id={}", record.approvalId());
                    return Decision.REJECT;
                }
                try {
                    return record.future().get(POLL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 继续轮询：不是真的超时，只是等用户点击
                }
            }
            log.info("审批超时，自动拒绝: id={}, tool={}", record.approvalId(), record.tool());
            return Decision.REJECT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.REJECT;
        } catch (ExecutionException e) {
            log.warn("审批等待异常，按拒绝处理: id={}", record.approvalId(), e);
            return Decision.REJECT;
        } finally {
            pending.remove(record.approvalId());
        }
    }

    /** 用户停止生成：该会话所有待审批直接拒绝，唤醒阻塞线程 */
    public void cancelSession(Long sessionId) {
        if (sessionId == null) return;
        List<String> ids = new ArrayList<>();
        pending.forEach((id, record) -> {
            if (sessionId.equals(record.sessionId())) {
                record.future().complete(Decision.REJECT);
                ids.add(id);
            }
        });
        ids.forEach(pending::remove);
        if (!ids.isEmpty()) {
            log.info("会话 {} 停止生成，取消 {} 条待审批", sessionId, ids.size());
        }
    }
}
