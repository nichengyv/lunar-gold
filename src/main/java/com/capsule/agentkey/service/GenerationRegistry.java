package com.capsule.agentkey.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进行中的生成任务登记表：sessionId → 多个 Run 的取消标记。
 *
 * <p>P6-06：客户端断流不再登记为取消，只有 /api/agent/chat/stop 会把标记置位。
 * 断流后 Run 可以在后台继续，同一会话可能短暂存在多个活跃 Run，
 * 因此每个会话保存一组句柄：停止时全部取消，收尾时按句柄精确注销，
 * 避免旧 Run 收尾误删新 Run 的取消标记。
 *
 * <p>用户点「停止」后，ReAct 循环在下一轮开始前检查标记并中止；
 * run_command 会在 200ms 内杀掉子进程，无需等待长命令自然结束。
 */
@Component
public class GenerationRegistry {

    private final Map<Long, Set<AtomicBoolean>> flags = new ConcurrentHashMap<>();

    public void register(Long sessionId, AtomicBoolean flag) {
        if (sessionId == null || flag == null) {
            return;
        }
        flags.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet()).add(flag);
    }

    /** 按句柄精确移除；旧 Run 收尾不会误删同会话新 Run 的标记 */
    public void unregister(Long sessionId, AtomicBoolean flag) {
        if (sessionId == null || flag == null) {
            return;
        }
        flags.computeIfPresent(sessionId, (key, set) -> {
            set.remove(flag);
            return set.isEmpty() ? null : set;
        });
    }

    /** 取消该会话全部活跃 Run；返回是否至少命中一个 */
    public boolean cancel(Long sessionId) {
        if (sessionId == null) {
            return false;
        }
        Set<AtomicBoolean> sessionFlags = flags.get(sessionId);
        if (sessionFlags == null || sessionFlags.isEmpty()) {
            return false;
        }
        for (AtomicBoolean flag : sessionFlags) {
            flag.set(true);
        }
        return true;
    }
}
