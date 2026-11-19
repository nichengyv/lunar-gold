package com.capsule.agentkey.controller.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一轮聊天的 SSE 通道：事件发送、心跳保活与连接状态标记。
 *
 * <p>从 AgentChatController 中抽离，目的是让 Controller 只关心业务编排，
 * 而「怎么把事件写出去、什么时候判定客户端断开」集中在这里。
 * 事件名、载荷格式与写出顺序保持与抽取前完全一致。
 *
 * <p>P6-06：这里同时维护两个互不影响的信号：
 * <ul>
 *   <li>{@code clientDetached}：SSE 连接断开，只表示事件无法再推送，Run 继续执行；</li>
 *   <li>{@code cancelRequested}：用户显式停止，只有它能让 ReAct、工具与审批等待中止。</li>
 * </ul>
 * 任何断连相关回调都不得写入取消信号，否则窗口重载会重新变成「断流即取消」。
 */
@Slf4j
public final class ChatSseChannel {

    /** P4-04：SSE 心跳间隔；Nginx/网关通常 60s 空闲断连，15s 足够安全 */
    private static final long HEARTBEAT_SECONDS = 15L;

    /** P4-04：心跳线程与业务线程隔离，单线程足够（写 SSE 不阻塞） */
    private static final ScheduledExecutorService HEARTBEAT_POOL =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private final TrackedEmitter emitter = new TrackedEmitter(0L);

    /** P6-06：客户端连接是否已断开；只影响事件能否发送，不参与取消判定 */
    private final AtomicBoolean clientDetached = new AtomicBoolean(false);

    /** P6-06：显式停止标记；仅由 /api/agent/chat/stop 置位，ReAct、工具与审批等待只读它 */
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private final ScheduledFuture<?> heartbeat;

    private ChatSseChannel(Long userId) {
        // P4-04：心跳注释帧保活；连接结束后必须取消，否则会一直往已关闭的 emitter 写。
        // 注意在准备阶段之后才创建（由调用方保证），避免参数校验失败时留下永不取消的定时任务。
        // 用 holder 解决「定时任务内部需要引用自身以便失败时取消」的初始化顺序问题。
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        heartbeat = HEARTBEAT_POOL.scheduleAtFixedRate(() -> {
            try {
                emitter.heartbeat();
            } catch (Exception e) {
                ScheduledFuture<?> task = holder[0];
                if (task != null) {
                    task.cancel(false);
                }
                markDetached(userId, "心跳发送失败");
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        holder[0] = heartbeat;

        emitter.onCompletion(() -> heartbeat.cancel(false));
        emitter.onTimeout(() -> {
            heartbeat.cancel(false);
            markDetached(userId, "SSE 超时");
        });
        emitter.onError(t -> {
            heartbeat.cancel(false);
            markDetached(userId, "SSE 连接错误: " + t.getMessage());
        });
    }

    /** 创建通道并启动心跳；超时时间不设固定值，多轮审批可能远超前端默认超时 */
    public static ChatSseChannel open(Long userId) {
        return new ChatSseChannel(userId);
    }

    public SseEmitter emitter() {
        return emitter;
    }

    /** 连接是否已断开；只用于决定要不要继续写 SSE */
    public AtomicBoolean detached() {
        return clientDetached;
    }

    /** 取消信号句柄；登记到 GenerationRegistry，供 /chat/stop 置位 */
    public AtomicBoolean cancelFlag() {
        return cancelRequested;
    }

    /** 显式停止：只由 /chat/stop 调用，断连回调不得调用 */
    public void requestCancel() {
        cancelRequested.set(true);
    }

    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    /**
     * P6-06：标记连接断开，只停止推送，不终止 Run。
     * 日志只在首次断连时打印一次，避免每次心跳或事件发送都刷屏。
     */
    private void markDetached(Long userId, String reason) {
        if (clientDetached.compareAndSet(false, true)) {
            log.warn("SSE 连接已断开，Run 继续执行，仅停止推送 [userId={}]: {}", userId, reason);
            // 客户端已不在线：立即结束 emitter，触发 onCompletion 取消心跳并释放连接资源
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("断流后关闭 emitter 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 发送 SSE 事件。
     * SseEmitter 内部已按 SSE 协议格式化多行 data，无需像手写流那样逐行加 "data: " 前缀。
     *
     * <p>P6-06：连接断开后变为静默 no-op；写出失败也只标记断连并记录一次日志，
     * 不再向业务线程抛异常，避免调用方跳过事件落库。
     */
    public void send(String event, String data) throws IOException {
        if (clientDetached.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data == null ? "" : data));
        } catch (IOException | IllegalStateException e) {
            markDetached(null, "事件 " + event + " 发送失败: " + e.getMessage());
        }
    }

    /** 静默发送：失败不抛异常（用于错误已经发生、尽力通知前端的场景） */
    public void sendQuietly(String event, String data) {
        try {
            send(event, data);
        } catch (IOException ignored) {
            // send 内部已把断连降级为 no-op，这里仅保留签名兼容
        }
    }

    /**
     * P4-04：自动带自增事件 id 的 SseEmitter。
     *
     * <p>SSE 协议允许客户端用 Last-Event-ID / 自身记录的 seq 续传；
     * 这里统一在发送时注入 id，业务侧不需要逐个事件手工编号。
     * 心跳用注释帧（不会被当成消息），也不占用事件 id。
     */
    private static final class TrackedEmitter extends SseEmitter {

        private final AtomicLong eventId = new AtomicLong();

        private TrackedEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            super.send(builder.id(String.valueOf(eventId.incrementAndGet())));
        }

        /** 注释帧保活：部分网关/代理会丢弃注释，但不会影响事件 id 的连续性 */
        private void heartbeat() throws IOException {
            super.send(SseEmitter.event().comment("ping"));
        }
    }
}
