package com.capsule.agentkey.tool;

import com.capsule.agentkey.service.ReActAgentService.StreamCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P7-06：ask_user_question 的挂起、回答、取消与清理。
 *
 * <p>覆盖点：</p>
 * <ul>
 *   <li>正常回答 → DHS 格式 JSON（selected / custom）</li>
 *   <li>/chat/stop 置位取消标记 → 500ms 分片内返回 CANCELLED</li>
 *   <li>cleanup(runId) → 唤醒等待线程并返回 CANCELLED</li>
 *   <li>runId / questionId 不匹配时 resolve 返回 false，不误放行</li>
 *   <li>SSE 回调携带正确的 questionId / options / multiSelect</li>
 * </ul>
 */
class AskUserQuestionToolTest {

    private final AskUserQuestionTool tool = new AskUserQuestionTool();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void resolvesWithSelectedAndCustom() throws Exception {
        long runId = 101L;
        CountDownLatch asked = new CountDownLatch(1);
        CapturingCallback callback = new CapturingCallback(asked);

        Future<String> result = pool.submit(() -> tool.ask(
                runId, "scope", "选哪个方案？", "改动范围",
                List.of(Map.of("label", "A (Recommended)", "description", "改接口"),
                        Map.of("label", "B", "description", "加包装")),
                false, callback, () -> false));

        assertTrue(asked.await(2, TimeUnit.SECONDS), "应先把问题推给用户");
        assertEquals("scope", callback.questionId);
        assertEquals(2, callback.optionCount);
        assertFalse(callback.multiSelect);

        assertTrue(tool.resolve(runId,
                new AskUserQuestionTool.UserAnswer("scope", List.of("B"), null)),
                "同 runId + questionId 应能命中");

        String json = result.get(2, TimeUnit.SECONDS);
        assertTrue(json.contains("\"answers\""), json);
        assertTrue(json.contains("\"id\": \"scope\""), json);
        assertTrue(json.contains("\"B\""), json);
        assertFalse(json.contains("custom"), json);
    }

    @Test
    void customAnswerIsEchoed() throws Exception {
        long runId = 102L;
        CountDownLatch asked = new CountDownLatch(1);
        Future<String> result = pool.submit(() -> tool.ask(
                runId, "q1", "文件名？", null, List.of(), false,
                new CapturingCallback(asked), () -> false));

        assertTrue(asked.await(2, TimeUnit.SECONDS));
        tool.resolve(runId, new AskUserQuestionTool.UserAnswer("q1", List.of(), "index.ts"));

        String json = result.get(2, TimeUnit.SECONDS);
        assertTrue(json.contains("\"custom\": \"index.ts\""), json);
    }

    @Test
    void cancelSignalWakesWithinSlice() throws Exception {
        long runId = 103L;
        AtomicBoolean cancel = new AtomicBoolean(false);
        CountDownLatch asked = new CountDownLatch(1);

        Future<String> result = pool.submit(() -> tool.ask(
                runId, "q1", "继续吗？", null, List.of(), false,
                new CapturingCallback(asked), cancel::get));

        assertTrue(asked.await(2, TimeUnit.SECONDS));
        cancel.set(true);

        String json = result.get(2, TimeUnit.SECONDS);
        assertTrue(json.contains("\"error\": \"CANCELLED\""), json);
    }

    @Test
    void cleanupReleasesWaitingThread() throws Exception {
        long runId = 104L;
        CountDownLatch asked = new CountDownLatch(1);

        Future<String> result = pool.submit(() -> tool.ask(
                runId, "q1", "继续吗？", null, List.of(), false,
                new CapturingCallback(asked), () -> false));

        assertTrue(asked.await(2, TimeUnit.SECONDS));
        tool.cleanup(runId);

        String json = result.get(2, TimeUnit.SECONDS);
        assertTrue(json.contains("\"error\": \"CANCELLED\""), json);
    }

    @Test
    void resolveUnknownRunReturnsFalse() {
        assertFalse(tool.resolve(999L,
                new AskUserQuestionTool.UserAnswer("q", List.of("A"), null)));
    }

    @Test
    void resolveWithMismatchedQuestionIdReturnsFalse() throws Exception {
        long runId = 105L;
        CountDownLatch asked = new CountDownLatch(1);
        Future<String> pending = pool.submit(() -> tool.ask(
                runId, "real", "问题", null, List.of(), false,
                new CapturingCallback(asked), () -> false));

        assertTrue(asked.await(2, TimeUnit.SECONDS));
        assertFalse(tool.resolve(runId,
                new AskUserQuestionTool.UserAnswer("fake", List.of("A"), null)),
                "questionId 不匹配不能误放行");

        tool.cleanup(runId);
        assertTrue(pending.get(2, TimeUnit.SECONDS).contains("CANCELLED"));
    }

    /** 最小可用的 StreamCallback：实现 6 个抽象方法，其余走接口默认实现。 */
    private static final class CapturingCallback implements StreamCallback {
        private final CountDownLatch asked;
        private volatile String questionId;
        private volatile int optionCount;
        private volatile boolean multiSelect;

        CapturingCallback(CountDownLatch asked) {
            this.asked = asked;
        }

        @Override
        public void onUserQuestion(String questionId, String question, String header,
                                   List<Map<String, Object>> options, boolean multiSelect) {
            this.questionId = questionId;
            this.optionCount = options == null ? 0 : options.size();
            this.multiSelect = multiSelect;
            asked.countDown();
        }

        @Override
        public void onThinking(String thought) {}

        @Override
        public void onToolCall(String toolName, String arguments) {}

        @Override
        public void onToolResult(String toolName, ToolResult result) {}

        @Override
        public void onChunk(String chunk) {}

        @Override
        public void onComplete() {}

        @Override
        public void onError(Throwable t) {}
    }
}
