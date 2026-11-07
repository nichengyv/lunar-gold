package com.capsule.agentkey.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进行中的生成任务登记表：sessionId → 断连/停止标记。
 *
 * 前端点「停止」时调用 /api/agent/chat/stop，这里把对应标记置位；
 * ReAct 循环在下一轮开始前检查标记并中止，run_command 会在 200ms 内杀掉子进程。
 * 之前只有 SSE 写出失败才能感知断连，而长命令执行期间没有写出，
 * 用户点了停止也要等到命令跑完才生效。
 */
@Component
public class GenerationRegistry {

    private final Map<Long, AtomicBoolean> flags = new ConcurrentHashMap<>();

    public void register(Long sessionId, AtomicBoolean flag) {
        if (sessionId != null) {
            flags.put(sessionId, flag);
        }
    }

    /** 用值匹配移除，避免旧任务收尾时误删同会话的新任务标记 */
    public void unregister(Long sessionId, AtomicBoolean flag) {
        if (sessionId != null) {
            flags.remove(sessionId, flag);
        }
    }

    public boolean cancel(Long sessionId) {
        AtomicBoolean flag = flags.get(sessionId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }
}
