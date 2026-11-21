package com.capsule.agentkey.agent.stream;

import com.capsule.agentkey.client.AgentClient;
import com.capsule.agentkey.client.AgentStreamCallback;
import com.capsule.agentkey.exception.TokenBudgetExceededException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一轮模型流式调用的发布器（R4）。
 *
 * <p>重构前，主循环里两处 {@code streamChat} 各自维护 StringBuilder、tool_calls 累积、
 * usage 统计、错误与超预算判定，重复且容易漂移。本类只做一件事：
 * 发起一次流式调用，把 chunk/usage 原样转发给上层，同时聚合本轮文本、工具调用与 token。
 *
 * <p>行为保持：错误仍然在流结束后以 {@link RuntimeException} 抛出；
 * token 超预算仍然抛 {@link TokenBudgetExceededException}，触发时机与重构前一致。
 */
public final class StreamPublisher {

    /**
     * 一轮流式调用的聚合结果。
     *
     * @param text           本轮输出的完整文本（已逐片转发给上层）
     * @param toolCalls      本轮累积的工具调用
     * @param promptTokens   本轮 prompt token
     * @param completionTokens 本轮 completion token
     */
    public record Turn(String text, List<ObjectNode> toolCalls,
                       long promptTokens, long completionTokens) {
    }

    public interface ChunkSink {
        void onChunk(String chunk);
    }

    public interface UsageSink {
        void onUsage(long promptTokens, long completionTokens);
    }

    /**
     * 发起一次流式调用并返回聚合结果。
     *
     * @param promptTotal     本 run 累计 prompt token；由调用方持有，跨轮累加
     * @param completionTotal 本 run 累计 completion token
     * @param maxTokens       单 run token 预算；<=0 表示不限制
     */
    public Turn stream(AgentClient client, String apiKey, String model,
                       List<ObjectNode> messages, List<ToolCallback> tools, String memorySummary,
                       ChunkSink chunks, UsageSink usage,
                       AtomicLong promptTotal, AtomicLong completionTotal, long maxTokens) {
        StringBuilder text = new StringBuilder();
        List<ObjectNode> toolCalls = new ArrayList<>();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        AtomicLong overBudgetTokens = new AtomicLong();

        client.streamChat(apiKey, model, messages, tools, memorySummary,
                new AgentStreamCallback() {
                    @Override
                    public void onChunk(String chunk) {
                        text.append(chunk);
                        chunks.onChunk(chunk);
                    }

                    @Override
                    public void onToolCalls(List<ObjectNode> calls) {
                        toolCalls.addAll(calls);
                    }

                    @Override
                    public void onComplete() {
                        // 一轮结束由调用方决定：继续工具循环还是收尾
                    }

                    @Override
                    public void onUsage(long promptTokens, long completionTokens) {
                        long total = promptTotal.addAndGet(promptTokens)
                                + completionTotal.addAndGet(completionTokens);
                        usage.onUsage(promptTokens, completionTokens);
                        if (maxTokens > 0 && total > maxTokens) {
                            overBudgetTokens.set(total);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        streamError.set(t);
                    }
                });

        if (streamError.get() != null) {
            throw new RuntimeException(streamError.get());
        }
        if (overBudgetTokens.get() > 0) {
            throw new TokenBudgetExceededException(overBudgetTokens.get(), maxTokens);
        }
        return new Turn(text.toString(), List.copyOf(toolCalls),
                promptTotal.get(), completionTotal.get());
    }
}
