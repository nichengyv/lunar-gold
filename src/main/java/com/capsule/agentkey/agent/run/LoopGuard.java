package com.capsule.agentkey.agent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.HashSet;
import java.util.Set;

/**
 * 无进展检测与渐进式恢复（R4，落地 P7-08）。
 *
 * <p>重构前，重复调用、重复失败、重复诊断、重复验证失败、停滞轮次五组计数器
 * 直接散落在 ReAct 主循环里，且一到阈值就硬停止。本类把「计数 + 分级 nudge + 停止」
 * 收敛为纯状态机：
 *
 * <ul>
 *   <li>重复调用 / 重复失败：第 N-1 次软提示，第 N 次 CRITICAL，第 N+1 次才停止，
 *       给模型一轮自救窗口；</li>
 *   <li>重复诊断 / 重复验证 / 停滞轮次：在第 N 次停止前一轮插入 nudge，阈值语义不变；</li>
 *   <li>任何真实进展（新调用成功、计划推进、文件变化）都会清零对应计数。</li>
 * </ul>
 *
 * <p>本类不依赖 SSE 与工具实现，主循环只负责把 {@link Signal} 转成 user 消息或收尾。
 */
public final class LoopGuard {

    public enum Kind { NONE, NUDGE, STOP }

    /**
     * 一次检测结果。
     *
     * @param kind            NONE / NUDGE / STOP
     * @param reason          停止原因，与既有 NoProgress.reason 保持一致
     * @param toolName        相关工具名（停滞轮次时为 null）
     * @param count           当前连续计数
     * @param stagnantRounds  当前停滞轮次
     * @param message         给模型的 nudge 文本，或停止说明
     * @param firstOccurrence 本次调用签名是否为首次出现（供主循环判定是否有进展）
     */
    public record Signal(Kind kind, String reason, String toolName, int count,
                         int stagnantRounds, String message, boolean firstOccurrence) {

        static Signal none(boolean firstOccurrence) {
            return new Signal(Kind.NONE, null, null, 0, 0, null, firstOccurrence);
        }

        static Signal nudge(String reason, String toolName, int count, int stagnantRounds,
                            String message) {
            return new Signal(Kind.NUDGE, reason, toolName, count, stagnantRounds, message, false);
        }

        static Signal stop(String reason, String toolName, int count, int stagnantRounds,
                           String message) {
            return new Signal(Kind.STOP, reason, toolName, count, stagnantRounds, message, false);
        }

        public boolean isNudge() {
            return kind == Kind.NUDGE;
        }

        public boolean isStop() {
            return kind == Kind.STOP;
        }
    }

    private final int maxRepeats;
    private final int maxStagnantRounds;

    private String lastToolSignature;
    private int toolRepeatCount;
    private int toolNudgeLevel;
    /** 本轮 Run 内已出现过的调用签名；交替调用 A/B/A/B 不能算作新进展。 */
    private final Set<String> seenToolSignatures = new HashSet<>();

    private String lastFailureFingerprint;
    private int failureCount;
    private int failureNudgeLevel;

    private String lastDiagnosticsFingerprint;
    private int diagnosticsCount;
    private boolean diagnosticsNudged;

    private String lastVerifyFingerprint;
    private int verifyCount;
    private boolean verifyNudged;

    private int stagnantRounds;
    private boolean stagnantNudged;

    public LoopGuard(int maxRepeats, int maxStagnantRounds) {
        this.maxRepeats = maxRepeats;
        this.maxStagnantRounds = maxStagnantRounds;
    }

    /**
     * 记录一次工具调用意图。
     *
     * <p>maxRepeats=3 时的节奏：第 2 次软提示，第 3 次 CRITICAL，第 4 次停止。
     * 第 3 次仍执行工具，让模型能看到结果并自行调整；再重复才判定为无进展。
     */
    public Signal onToolCall(String toolName, String arguments) {
        String signature = normalizeToolCall(toolName, arguments);
        // 进展判定看“整个 Run 是否首次出现”，重复计数只看“是否连续相同”：
        // 交替调用 A/B/A/B 时前者为 false（停滞），后者每轮都是新签名（不误报重复）。
        boolean firstOccurrence = seenToolSignatures.add(signature);
        boolean consecutiveNew = !signature.equals(lastToolSignature);
        lastToolSignature = signature;
        if (consecutiveNew) {
            toolRepeatCount = 1;
            toolNudgeLevel = 0;
            return Signal.none(firstOccurrence);
        }
        toolRepeatCount++;
        if (maxRepeats <= 0) {
            return Signal.none(false);
        }
        int softAt = Math.max(1, maxRepeats - 1);
        int criticalAt = maxRepeats;
        int stopAt = maxRepeats + 1; // 一轮自救窗口

        if (toolRepeatCount >= stopAt) {
            return Signal.stop("REPEATED_TOOL_CALL", toolName, toolRepeatCount, stagnantRounds,
                    "检测到连续 " + toolRepeatCount + " 次相同工具调用（" + toolName
                            + "），已停止以避免无进展循环。");
        }
        if (toolRepeatCount >= criticalAt && toolNudgeLevel < 2) {
            toolNudgeLevel = 2;
            return Signal.nudge("REPEATED_TOOL_CALL", toolName, toolRepeatCount, stagnantRounds,
                    "【重要】连续 " + toolRepeatCount + " 次相同调用 " + toolName
                            + " 没有带来新信息。请立即停止重试该调用，改用其他策略"
                            + "（使用已有结果、缩小范围，或调用 ask_user_question 询问用户）；"
                            + "下一轮仍无进展将安全停止。");
        }
        if (toolRepeatCount >= softAt && toolNudgeLevel < 1) {
            toolNudgeLevel = 1;
            return Signal.nudge("REPEATED_TOOL_CALL", toolName, toolRepeatCount, stagnantRounds,
                    "提示：已连续 " + toolRepeatCount + " 次以相同参数调用 " + toolName
                            + "。请先检查已有结果是否足够；如需更多信息，请调整参数或换一种工具，"
                            + "不要原样重复调用。");
        }
        return Signal.none(false);
    }

    /** 工具成功：清空失败连击，避免一次成功之后仍被旧失败计数误伤 */
    public void onToolSuccess() {
        lastFailureFingerprint = null;
        failureCount = 0;
        failureNudgeLevel = 0;
    }

    /** 记录一次工具失败；节奏与重复调用一致（软提示 → CRITICAL → 停止） */
    public Signal onFailure(String toolName, String error) {
        String fingerprint = normalizeFailure(toolName, error);
        failureCount = fingerprint.equals(lastFailureFingerprint) ? failureCount + 1 : 1;
        lastFailureFingerprint = fingerprint;
        if (failureCount == 1) {
            return Signal.none(false);
        }
        if (maxRepeats <= 0) {
            return Signal.none(false);
        }
        int softAt = Math.max(1, maxRepeats - 1);
        int criticalAt = maxRepeats;
        int stopAt = maxRepeats + 1;

        if (failureCount >= stopAt) {
            return Signal.stop("REPEATED_FAILURE", toolName, failureCount, stagnantRounds,
                    "检测到连续 " + failureCount + " 次相同失败（" + toolName
                            + "），已停止以避免无效重试。");
        }
        if (failureCount >= criticalAt && failureNudgeLevel < 2) {
            failureNudgeLevel = 2;
            return Signal.nudge("REPEATED_FAILURE", toolName, failureCount, stagnantRounds,
                    "【重要】" + toolName + " 已连续 " + failureCount + " 次以相同方式失败。"
                            + "不要再原样重试：请先分析错误信息，检查路径、参数与权限，"
                            + "或改用其他工具；下一轮仍无进展将安全停止。");
        }
        if (failureCount >= softAt && failureNudgeLevel < 1) {
            failureNudgeLevel = 1;
            return Signal.nudge("REPEATED_FAILURE", toolName, failureCount, stagnantRounds,
                    "提示：" + toolName + " 连续 " + failureCount + " 次失败。"
                            + "请先分析错误原因再决定是否重试，不要原样重试。");
        }
        return Signal.none(false);
    }

    /** 相同诊断连击：停止阈值不变，只在停止前一轮插入 nudge；fingerprint 为 null 表示已无诊断 */
    public Signal onDiagnostics(String fingerprint, String description) {
        if (fingerprint == null) {
            lastDiagnosticsFingerprint = null;
            diagnosticsCount = 0;
            diagnosticsNudged = false;
            return Signal.none(false);
        }
        diagnosticsCount = fingerprint.equals(lastDiagnosticsFingerprint) ? diagnosticsCount + 1 : 1;
        lastDiagnosticsFingerprint = fingerprint;
        if (diagnosticsCount == 1) {
            diagnosticsNudged = false;
            return Signal.none(false);
        }
        if (maxRepeats <= 0) {
            return Signal.none(false);
        }
        if (diagnosticsCount >= maxRepeats) {
            return Signal.stop("REPEATED_DIAGNOSTICS", "diagnostics", diagnosticsCount,
                    stagnantRounds,
                    "连续 " + diagnosticsCount + " 次得到相同诊断（" + description
                            + "），已停止以避免在同一个编译错误上循环。");
        }
        if (diagnosticsCount >= maxRepeats - 1 && !diagnosticsNudged) {
            diagnosticsNudged = true;
            return Signal.nudge("REPEATED_DIAGNOSTICS", "diagnostics", diagnosticsCount,
                    stagnantRounds,
                    "提示：连续 " + diagnosticsCount + " 次得到相同诊断（" + description
                            + "）。请先修复这个错误再继续，避免在同一处反复编译。");
        }
        return Signal.none(false);
    }

    /** 相同验证失败：停止阈值不变，只在停止前一轮插入 nudge；fingerprint 为 null 表示验证恢复 */
    public Signal onVerifyFailure(String fingerprint, String description) {
        if (fingerprint == null) {
            lastVerifyFingerprint = null;
            verifyCount = 0;
            verifyNudged = false;
            return Signal.none(false);
        }
        verifyCount = fingerprint.equals(lastVerifyFingerprint) ? verifyCount + 1 : 1;
        lastVerifyFingerprint = fingerprint;
        if (verifyCount == 1) {
            verifyNudged = false;
            return Signal.none(false);
        }
        if (maxRepeats <= 0) {
            return Signal.none(false);
        }
        if (verifyCount >= maxRepeats) {
            return Signal.stop("REPEATED_VERIFY_FAILURE", "verify", verifyCount, stagnantRounds,
                    "连续 " + verifyCount + " 次得到相同验证失败（" + description
                            + "），已停止以避免无进展循环。");
        }
        if (verifyCount >= maxRepeats - 1 && !verifyNudged) {
            verifyNudged = true;
            return Signal.nudge("REPEATED_VERIFY_FAILURE", "verify", verifyCount, stagnantRounds,
                    "提示：连续 " + verifyCount + " 次得到相同验证失败（" + description
                            + "）。请先分析失败原因并修复，不要原样重跑验证。");
        }
        return Signal.none(false);
    }

    /**
     * 一轮结束：统计无进展轮次。
     * progress=true 表示本轮有新调用成功、计划推进或文件变化，计数清零。
     */
    public Signal onRoundEnd(boolean progress) {
        if (progress) {
            stagnantRounds = 0;
            stagnantNudged = false;
            return Signal.none(false);
        }
        stagnantRounds++;
        if (maxStagnantRounds <= 0) {
            return Signal.none(false);
        }
        if (stagnantRounds >= maxStagnantRounds) {
            return Signal.stop("STAGNANT_ROUNDS", null, 0, stagnantRounds,
                    "连续 " + stagnantRounds + " 轮没有新的有效结果、计划或文件变化，已安全停止。");
        }
        if (stagnantRounds >= maxStagnantRounds - 1 && !stagnantNudged) {
            stagnantNudged = true;
            return Signal.nudge("STAGNANT_ROUNDS", null, 0, stagnantRounds,
                    "提示：最近 " + stagnantRounds + " 轮没有新的工具结果、计划推进或文件变化。"
                            + "请停止重复探索，基于已有信息给出结论，或调用 ask_user_question 询问用户；"
                            + "否则将安全停止。");
        }
        return Signal.none(false);
    }

    public int stagnantRounds() {
        return stagnantRounds;
    }

    /** 参数 JSON 按 key 排序，避免语义相同的调用因字段顺序不同绕过重复检测。 */
    static String normalizeToolCall(String toolName, String arguments) {
        try {
            Object value = mapper().readValue(
                    arguments == null || arguments.isBlank() ? "{}" : arguments, Object.class);
            String canonical = mapper().writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
            return toolName + ":" + canonical;
        } catch (Exception e) {
            return toolName + ":" + (arguments == null ? "" : arguments.trim());
        }
    }

    static String normalizeFailure(String toolName, String error) {
        return toolName + ":" + (error == null ? "" : error.trim().replaceAll("\\s+", " "));
    }

    /** 规范化用的共享 ObjectMapper；方法引用避免静态可变字段 */
    private static ObjectMapper mapper() {
        return MapperHolder.INSTANCE;
    }

    private static final class MapperHolder {
        private static final ObjectMapper INSTANCE = new ObjectMapper();
    }
}
