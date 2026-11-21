package com.capsule.agentkey.agent.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R4 / P7-08：无进展检测的渐进式恢复契约。
 *
 * <p>锁定三件事：重复调用与失败在停止前给出两级 nudge 和一轮自救窗口；
 * 诊断 / 验证 / 停滞在既有阈值前插入 nudge，阈值本身不变；任何进展都会清零计数。
 */
class LoopGuardTest {

    @Test
    void 重复调用先软提示再严重提示最后才停止() {
        LoopGuard guard = new LoopGuard(3, 4);

        assertTrue(guard.onToolCall("read_file", "{\"path\":\"a.txt\"}").firstOccurrence());
        assertTrue(guard.onToolCall("read_file", "{\"path\":\"a.txt\"}").isNudge());

        LoopGuard.Signal critical = guard.onToolCall("read_file", "{\"path\":\"a.txt\"}");
        assertTrue(critical.isNudge());
        assertTrue(critical.message().contains("【重要】"));

        LoopGuard.Signal stop = guard.onToolCall("read_file", "{\"path\":\"a.txt\"}");
        assertTrue(stop.isStop());
        assertEquals("REPEATED_TOOL_CALL", stop.reason());
        assertEquals(4, stop.count());
    }

    @Test
    void 参数顺序不同仍视为同一次调用() {
        LoopGuard guard = new LoopGuard(3, 4);

        guard.onToolCall("search_files", "{\"pattern\":\"x\",\"path\":\"src\"}");
        LoopGuard.Signal signal = guard.onToolCall("search_files", "{\"path\":\"src\",\"pattern\":\"x\"}");

        assertTrue(signal.isNudge(), "按 key 排序后语义相同，应计入重复");
    }

    @Test
    void 切换到不同调用后重新计数() {
        LoopGuard guard = new LoopGuard(3, 4);
        guard.onToolCall("read_file", "{\"path\":\"a.txt\"}");
        assertTrue(guard.onToolCall("read_file", "{\"path\":\"a.txt\"}").isNudge());

        LoopGuard.Signal different = guard.onToolCall("read_file", "{\"path\":\"b.txt\"}");
        assertTrue(different.firstOccurrence());
        assertEquals(LoopGuard.Kind.NONE, different.kind());
    }

    @Test
    void 连续失败同样保留一轮自救窗口() {
        LoopGuard guard = new LoopGuard(3, 4);

        assertEquals(LoopGuard.Kind.NONE, guard.onFailure("write_file", "permission denied").kind());
        assertTrue(guard.onFailure("write_file", "permission denied").isNudge());
        assertTrue(guard.onFailure("write_file", "permission denied").isNudge());
        LoopGuard.Signal stop = guard.onFailure("write_file", "permission denied");
        assertTrue(stop.isStop());
        assertEquals("REPEATED_FAILURE", stop.reason());
    }

    @Test
    void 工具成功后失败连击清零() {
        LoopGuard guard = new LoopGuard(3, 4);
        guard.onFailure("write_file", "boom");
        guard.onFailure("write_file", "boom");
        guard.onToolSuccess();

        assertEquals(LoopGuard.Kind.NONE, guard.onFailure("write_file", "boom").kind(),
                "成功之后旧失败不应继续累计");
    }

    @Test
    void 相同诊断在停止前一轮提示() {
        LoopGuard guard = new LoopGuard(3, 4);

        assertEquals(LoopGuard.Kind.NONE, guard.onDiagnostics("fp-1", "A.java:10").kind());
        assertTrue(guard.onDiagnostics("fp-1", "A.java:10").isNudge());
        LoopGuard.Signal stop = guard.onDiagnostics("fp-1", "A.java:10");
        assertTrue(stop.isStop());
        assertEquals("REPEATED_DIAGNOSTICS", stop.reason());

        assertEquals(LoopGuard.Kind.NONE, guard.onDiagnostics(null, null).kind());
        assertEquals(LoopGuard.Kind.NONE, guard.onDiagnostics("fp-1", "A.java:10").kind(),
                "诊断恢复后重新计数");
    }

    @Test
    void 相同验证失败在停止前一轮提示() {
        LoopGuard guard = new LoopGuard(3, 4);

        assertEquals(LoopGuard.Kind.NONE, guard.onVerifyFailure("vf-1", "mvn test").kind());
        assertTrue(guard.onVerifyFailure("vf-1", "mvn test").isNudge());
        LoopGuard.Signal stop = guard.onVerifyFailure("vf-1", "mvn test");
        assertTrue(stop.isStop());
        assertEquals("REPEATED_VERIFY_FAILURE", stop.reason());
    }

    @Test
    void 停滞轮次在阈值前提示_进展会清零() {
        LoopGuard guard = new LoopGuard(3, 4);

        assertEquals(LoopGuard.Kind.NONE, guard.onRoundEnd(false).kind());
        assertEquals(LoopGuard.Kind.NONE, guard.onRoundEnd(false).kind());
        assertTrue(guard.onRoundEnd(false).isNudge());
        LoopGuard.Signal stop = guard.onRoundEnd(false);
        assertTrue(stop.isStop());
        assertEquals("STAGNANT_ROUNDS", stop.reason());
        assertEquals(4, stop.stagnantRounds());

        assertTrue(guard.onRoundEnd(true).kind() == LoopGuard.Kind.NONE);
        assertEquals(0, guard.stagnantRounds());
    }

    @Test
    void 阈值为零时关闭检测() {
        LoopGuard guard = new LoopGuard(0, 0);

        for (int i = 0; i < 6; i++) {
            assertFalse(guard.onToolCall("read_file", "{}").isStop());
            assertFalse(guard.onFailure("read_file", "boom").isStop());
        }
        assertFalse(guard.onRoundEnd(false).isStop());
    }
}
