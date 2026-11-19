package com.capsule.agentkey.controller.support;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6-06：SSE 通道的断流标记与取消标记必须互不影响。
 *
 * <p>客户端 RST 在 Tomcat 上表现为发送时的 {@link IOException}（已由
 * {@code RunDisconnectHttpAcceptanceTest} 真实覆盖）；容器的 onError / onTimeout
 * 回调在当前配置下无法由真实客户端触发（emitter 超时为 0，不会超时），
 * 因此这里直接调用注册在 emitter 上的那两个回调，验证它们同样只标记断流、不置取消。
 */
class ChatSseChannelTest {

    @Test
    void 显式停止不会误置断流标记() {
        ChatSseChannel channel = ChatSseChannel.open(1L);
        try {
            assertFalse(channel.detached().get());
            assertFalse(channel.isCancelRequested());

            channel.requestCancel();

            assertTrue(channel.isCancelRequested());
            assertFalse(channel.detached().get(), "取消不能把连接状态改成断开");
        } finally {
            channel.emitter().complete();
        }
    }

    @Test
    void 断流不会置位取消信号且后续发送静默() {
        ChatSseChannel channel = ChatSseChannel.open(1L);
        try {
            channel.detached().set(true);

            assertDoesNotThrow(() -> channel.send("content", "{\"text\":\"x\"}"));
            assertDoesNotThrow(() -> channel.sendQuietly("done", "[DONE]"));

            assertFalse(channel.isCancelRequested(), "断流不能让 Run 进入取消");
        } finally {
            channel.emitter().complete();
        }
    }

    @Test
    void 容器报错只标记断流不置取消() throws Exception {
        ChatSseChannel channel = ChatSseChannel.open(1L);
        try {
            fireError(channel, new IOException("连接错误"));

            assertTrue(channel.detached().get(), "onError 必须标记断流");
            assertFalse(channel.isCancelRequested(), "onError 不得让 Run 进入取消");
        } finally {
            channel.emitter().complete();
        }
    }

    @Test
    void 容器超时只标记断流不置取消() throws Exception {
        ChatSseChannel channel = ChatSseChannel.open(1L);
        try {
            fireTimeout(channel);

            assertTrue(channel.detached().get(), "onTimeout 必须标记断流");
            assertFalse(channel.isCancelRequested(), "onTimeout 不得让 Run 进入取消");
        } finally {
            channel.emitter().complete();
        }
    }

    /** 触发 ChatSseChannel 注册在 emitter 上的 onError 回调（与容器回调同一实例） */
    @SuppressWarnings("unchecked")
    private static void fireError(ChatSseChannel channel, Throwable error) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("errorCallback");
        field.setAccessible(true);
        Consumer<Throwable> callback = (Consumer<Throwable>) field.get(channel.emitter());
        callback.accept(error);
    }

    /** 触发 ChatSseChannel 注册在 emitter 上的 onTimeout 回调 */
    private static void fireTimeout(ChatSseChannel channel) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("timeoutCallback");
        field.setAccessible(true);
        ((Runnable) field.get(channel.emitter())).run();
    }
}
