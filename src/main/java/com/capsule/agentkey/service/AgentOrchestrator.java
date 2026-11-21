package com.capsule.agentkey.service;

import com.capsule.agentkey.agent.context.ContextAssembler;
import com.capsule.agentkey.agent.run.FileChangeService;
import com.capsule.agentkey.agent.run.LoopGuard;
import com.capsule.agentkey.agent.stream.StreamPublisher;
import com.capsule.agentkey.agent.tool.ToolExecutor;
import com.capsule.agentkey.client.AgentClient;
import com.capsule.agentkey.service.ReActAgentService.Completion;
import com.capsule.agentkey.service.ReActAgentService.CompletionStatus;
import com.capsule.agentkey.service.ReActAgentService.StreamCallback;
import com.capsule.agentkey.tool.AskUserQuestionTool;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ReAct 主循环编排器（R4b）。
 *
 * <p>本类只负责一次 Run 的循环执行：模型流式调用 → 工具批 → 诊断/验证 → 计划收口。
 * 入口参数、消息装配、RAG 与项目上下文仍由 {@link ReActAgentService} 准备，
 * 这样主循环依赖的协作者可以在测试里被替换，而 SSE 回调、事件格式和停止原因保持不变。
 *
 * <p>行为保持约束：轮次上限、预算判定、回调顺序、工具消息顺序与终态载荷都必须与
 * 重构前逐行一致；任何调整都需要先补脚本化测试。
 */
@Slf4j
public class AgentOrchestrator {

    /** 工具结果进入上下文前的格式化（超长落盘 + 截断）；配置留在 Service 侧。 */
    @FunctionalInterface
    public interface ToolContentFormatter {
        String format(String content, int limit);
    }

    /** 循环相关的配置快照；执行开始时取值，运行中不重新读取。 */
    public record Limits(int maxRepeatedToolCalls, int maxStagnantRounds,
                         long maxTokensPerRun, long maxSecondsPerRun) {
    }

    /** 一次 Run 的输入；随每次 execute 变化的部分都放在这里。 */
    public record Invocation(String apiKey,
                             AgentPolicy policy,
                             List<ObjectNode> messages,
                             List<ToolCallback> tools,
                             String advisorSummary,
                             String workspacePath,
                             Long userId,
                             Long projectId,
                             Long sessionId,
                             AtomicBoolean cancelRequested,
                             StreamCallback callback,
                             ToolExecutor.ApprovalUi approvalUi,
                             ContextAssembler contextAssembler) {
    }

    private final AgentClient springAiClient;
    private final ToolExecutor toolExecutor;
    private final FileChangeService fileChangeService;
    private final com.capsule.agentkey.verify.VerifyService verifyService;
    /** P7-06：ask_user_question 的挂起/恢复执行；测试手动装配时不可用 */
    private final AskUserQuestionHandler askUserQuestionHandler;
    private final ToolContentFormatter toolContentFormatter;
    private final Limits limits;
    /** R4b：终态判定与计划收口；循环只决定何时结束 */
    private final RunTermination termination = new RunTermination();
    private final StreamPublisher streamPublisher = new StreamPublisher();
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentOrchestrator(AgentClient springAiClient,
                             ToolExecutor toolExecutor,
                             FileChangeService fileChangeService,
                             com.capsule.agentkey.verify.VerifyService verifyService,
                             AskUserQuestionTool askUserQuestionTool,
                             AgentRunService agentRunService,
                             ToolContentFormatter toolContentFormatter,
                             Limits limits) {
        this.springAiClient = springAiClient;
        this.toolExecutor = toolExecutor;
        this.fileChangeService = fileChangeService;
        this.verifyService = verifyService;
        this.askUserQuestionHandler = new AskUserQuestionHandler(askUserQuestionTool, agentRunService);
        this.toolContentFormatter = toolContentFormatter;
        this.limits = limits;
    }

    /**
     * 执行一次 ReAct 循环。
     *
     * <p>异常不在这里吞噬：调用方（{@link ReActAgentService}）保留原有的
     * 「收口计划 → onError」顺序与 finally 清理。
     */
    public void run(Invocation invocation) {
        AgentPolicy policy = invocation.policy();
        List<ObjectNode> messages = invocation.messages();
        List<ToolCallback> tools = invocation.tools();
        String advisorSummary = invocation.advisorSummary();
        String workspacePath = invocation.workspacePath();
        Long userId = invocation.userId();
        Long projectId = invocation.projectId();
        Long sessionId = invocation.sessionId();
        AtomicBoolean cancelRequested = invocation.cancelRequested();
        StreamCallback callback = invocation.callback();
        ToolExecutor.ApprovalUi approvalUi = invocation.approvalUi();
        // R5：计划软注入与三层压缩只作用于本轮发给模型的副本，权威消息列表保持原样
        ContextAssembler contextAssembler = invocation.contextAssembler();

        int round = 0;
        // P5-10-3：本轮开始时间，用于时间预算检查和前端耗时展示的对账
        long runStartedAt = System.currentTimeMillis();
        // P4-07：本 run 的 token 累计；超预算判定由 StreamPublisher 统一处理
        AtomicLong promptTokensTotal = new AtomicLong(0);
        AtomicLong completionTokensTotal = new AtomicLong(0);
        //开始 ReAct 循环（轮次上限由推理档位决定：快速 10 / 标准 30 / 深度 60）
        boolean budgetHintSent = false;
        boolean workspaceChanged = false;
        boolean unresolvedWriteFailure = false;
        boolean unresolvedApprovalRejection = false;
        // R4/P7-08：重复调用、失败、诊断、验证与停滞轮次统一由 LoopGuard 计数并分级 nudge
        LoopGuard loopGuard = new LoopGuard(limits.maxRepeatedToolCalls(), limits.maxStagnantRounds());
        //P5-06：验证次数、文件变更轮次与最近一次验证覆盖的轮次，避免重复执行验证
        int verifyAttempts = 0;
        long changeEpoch = 0;
        long verifiedEpoch = -1;
        boolean verifyUnavailableNotified = false;
        while (round < policy.reasoning().maxToolRounds()) {
            //P6-06：只有用户显式停止才中止；客户端断流不会进入这里，Run 继续执行
            if (cancelRequested != null && cancelRequested.get()) {
                log.info("用户已停止生成，中止 ReAct 推理 [round={}]", round + 1);
                // 批次 B：停止前也标记剩余步骤 aborted
                termination.finalizePlan(callback);
                callback.onCompletion(new Completion(CompletionStatus.INCOMPLETE, "CANCELLED",
                        "本轮执行已取消，任务尚未完成。", workspaceChanged));
                callback.onComplete();
                return;
            }
            // P5-10-3：时间预算耗尽时在下一轮开始前停止，给出可操作的原因而不是静默截断
            if (isTimeBudgetExceeded(System.currentTimeMillis() - runStartedAt, limits.maxSecondsPerRun())) {
                termination.stopWithCompletion(callback, workspaceChanged, "TIME_BUDGET_EXCEEDED",
                        "本轮已超过 " + limits.maxSecondsPerRun() + " 秒时间预算，已停止。"
                                + "可以缩小任务范围或调高 agent.budget.max-seconds-per-run。");
                return;
            }
            log.info("ReAct 第 {} 轮推理", round + 1);

            // 决策 + 作答合并成一次「流式」调用：
            // 模型只输出文本 → 边生成边推给前端（真正的打字机效果）；
            // 模型要调工具 → 先把分片参数累积成完整 tool_calls，再执行工具进入下一轮。
            // R4：流式调用、chunk/usage 转发、tool_calls 聚合与 token 预算统一由 StreamPublisher 处理。

            // 预算告警：剩余 3 轮时提醒模型收口，避免把轮次浪费在无谓探索上
            int remainingRounds = policy.reasoning().maxToolRounds() - round;
            if (!budgetHintSent && remainingRounds <= 3) {
                budgetHintSent = true;
                ObjectNode hint = mapper.createObjectNode();
                hint.put("role", "user");
                hint.put("content", "注意：本轮只剩 " + remainingRounds + " 次工具调用机会。"
                        + "请优先完成核心步骤，跳过非必要的探索；信息已足够时直接写文件并给出结论。");
                messages.add(hint);
            }

            StreamPublisher.Turn turn = streamPublisher.stream(
                    springAiClient, invocation.apiKey(), policy.model(),
                    assembleContext(contextAssembler, messages), tools, advisorSummary,
                    callback::onChunk, callback::onUsage,
                    promptTokensTotal, completionTokensTotal, limits.maxTokensPerRun());
            String streamedText = turn.text();
            List<ObjectNode> streamedToolCalls = turn.toolCalls();

            if (streamedToolCalls.isEmpty()) {//模型没要求调工具 → 只表示候选完成，不能代替验证证据
                //P5-06：候选完成时若最近一次验证没有覆盖最新文件变更，先补一次验证；
                //失败且仍有预算时把输出回灌，继续修复，而不是直接收尾。
                if (workspaceChanged && verifiedEpoch < changeEpoch) {
                    com.capsule.agentkey.verify.VerifyResult verify =
                            runVerification(workspacePath, cancelRequested, ++verifyAttempts, callback);
                    verifiedEpoch = changeEpoch;
                    if (verify.failed()) {
                        LoopGuard.Signal verifySignal = loopGuard.onVerifyFailure(
                                verify.failureFingerprint(), verify.message());
                        if (verifySignal.isStop()) {
                            termination.stopForNoProgress(callback, workspaceChanged,
                                    termination.toNoProgress(verifySignal));
                            return;
                        }
                        if (verify.attempt() < verify.maxAttempts()) {
                            addUserMessage(messages, verify.formatForPrompt());
                            if (verifySignal.isNudge()) {
                                addUserMessage(messages, verifySignal.message());
                            }
                            round++;
                            continue;
                        }
                    } else {
                        loopGuard.onVerifyFailure(null, null);
                    }
                }
                Completion completion = termination.candidateCompletion(
                        workspaceChanged, unresolvedWriteFailure, unresolvedApprovalRejection);
                if (completion.status() == CompletionStatus.INCOMPLETE) {
                    termination.finalizePlan(callback);
                }
                callback.onCompletion(completion);
                callback.onComplete();//流式回调通知：本轮全部输出完毕，前端关闭加载，思考动画
                return;
            }

            //把模型这一轮「调用工具」的意图写回上下文，后续 tool 消息才能对应上
            ObjectNode assistantMsg = mapper.createObjectNode();
            assistantMsg.put("role", "assistant");
            if (!streamedText.isEmpty()) {
                assistantMsg.put("content", streamedText);
            } else {
                assistantMsg.putNull("content");
            }
            assistantMsg.set("tool_calls", mapper.valueToTree(streamedToolCalls));
            messages.add(assistantMsg);

            boolean roundProgress = false;
            boolean roundFileChanged = false;
            //P5-08：当前连续只读批的执行结果（下标 → 结果）。写/命令调用不入批，遇到即中断批。
            Map<Integer, ToolResult> readOnlyBatch = Map.of();
            //诊断等 user 消息绝不能插在 assistant(tool_calls) 与本组 tool 响应之间：
            //并行调用多个写工具时，第一个写操作产生的诊断若立即入列，会把剩余 tool 响应
            //隔到 user 之后，provider 直接以 400「insufficient tool messages」拒绝整段历史。
            //先暂存，本组 tool 响应全部写完后统一追加。
            List<ObjectNode> deferredUserMessages = new ArrayList<>();
            for (int callIndex = 0; callIndex < streamedToolCalls.size(); callIndex++) {//循环遍历，支持一次调用多个工具
                ObjectNode toolCall = streamedToolCalls.get(callIndex);
                String toolName = toolCall.path("function").path("name").asText();
                String arguments = toolCall.path("function").path("arguments").asText();//工具所需的入参
                String toolCallId = toolCall.path("id").asText();//工具调用的唯一编号

                //P5-08：到达新的批首时，先把连续只读调用并发执行；结果仍按原下标顺序回放
                if (!readOnlyBatch.containsKey(callIndex)) {
                    readOnlyBatch = toolExecutor.executeReadOnlyBatch(streamedToolCalls, callIndex,
                            sessionId, policy, cancelRequested);
                }

                callback.onToolCall(toolName, arguments);
                log.info("调用工具: {} 参数: {}", toolName, arguments);

                // R4/P7-08：重复调用先 nudge，超过自救窗口才停止
                LoopGuard.Signal callSignal = loopGuard.onToolCall(toolName, arguments);
                boolean newToolSignature = callSignal.firstOccurrence();
                if (callSignal.isStop()) {
                    callback.onToolResult(toolName, ToolResult.fail(callSignal.message()));
                    termination.stopForNoProgress(callback, workspaceChanged, termination.toNoProgress(callSignal));
                    return;
                }

                ToolResult result;//根据工具名称找到对应实现类
                String risk = null; // 初始化为 null——ask_user_question 不走审批，risk 保持 null

                // P7-06：ask_user_question 拦截——绕开 ToolRegistry.dispatch()，走挂起/恢复链路
                // 原因：ask_user_question 需要挂起 ReAct 线程等用户回答，BaseTool.execute() 是同步返回的
                if ("ask_user_question".equals(toolName)
                        && askUserQuestionHandler.available()
                        && WorkspaceContext.get() != null
                        && WorkspaceContext.get().runId() != null) {
                    result = askUserQuestionHandler.execute(arguments, callback, cancelRequested);
                } else {
                if (readOnlyBatch.containsKey(callIndex)) {
                    //P5-08：连续只读批已并发执行完毕，按原顺序取结果；
                    //R3：批内调用在子线程中同样经过 ApprovalGate.dispatch（scope 校验），不存在旁路
                    result = readOnlyBatch.get(callIndex);
                } else {
                    // R3：最新权限 → 风险判定 → 审批（如需）→ scope → dispatch 全在 ToolExecutor 内完成
                    ToolExecutor.Outcome outcome =
                            toolExecutor.execute(toolName, arguments, userId, sessionId,
                                    policy.permission(), cancelRequested,
                                    approvalUi);
                    result = outcome.result();
                    risk = outcome.risk();
                    if (outcome.approvalRejected()) {
                        unresolvedApprovalRejection = true;
                    }
                }
                } // end else（非 ask_user_question 的常规审批 + dispatch 路径）
                callback.onToolResult(toolName, result);
                log.info("工具结果: success={}, cost={}ms", result.isSuccess(), result.getCostMs());

                //协议要求：assistant(tool_calls) 之后必须紧跟每个 id 的 tool 响应。
                //诊断/验证等 user 消息只能排在 tool 响应之后，否则模型侧会以 400 拒绝整段历史。
                ObjectNode toolMsg = mapper.createObjectNode();//新建一个空白的json对象
                //role标签规定该json对象的数据类型，方便大模型处理
                //user：人类输入
                //assistant：大模型自己说的
                //tool：外部程序返回
                toolMsg.put("role", "tool");

                toolMsg.put("tool_call_id", toolCallId);
                //工具执行成功：content填入返回数据
                //执行失败：填入错误提示文本
                //B2：无论成败都过一遍截断，防止超长内容进入上下文
                String toolContent = result.isSuccess() ? result.getContent() : "错误: " + result.getError();
                toolMsg.put("content", toolContentFormatter.format(
                        toolContent, policy.reasoning().toolContentLimit()));

                messages.add(toolMsg);//把组装完毕的tool消息存入全局消息数组

                // nudge 同样只能排在本组 tool 响应之后
                if (callSignal.isNudge()) {
                    deferredUserMessages.add(userMessage(callSignal.message()));
                }


                if (newToolSignature && result.isSuccess()) roundProgress = true;
                if (result.isSuccess() && risk != null) {
                    unresolvedWriteFailure = false;
                    unresolvedApprovalRejection = false;
                    roundProgress = true;
                } else if (!result.isSuccess() && ("WRITE".equals(risk) || "DESTRUCTIVE".equals(risk))) {
                    unresolvedWriteFailure = true;
                }

                //工具改动了工作区文件 → 逐个推给前端（在 tool_result 之后，保证顺序稳定）
                List<com.capsule.agentkey.tool.FileChange> fileChanges =
                        WorkspaceContext.drainFileChanges();
                if (!fileChanges.isEmpty()) {
                    workspaceChanged = true;
                    roundProgress = true;
                    roundFileChanged = true;
                }
                for (com.capsule.agentkey.tool.FileChange change : fileChanges) {
                    callback.onFileChange(change);
                }

                //P5-05：变更文件诊断 → 注入下一轮上下文、推送前端，并参与无进展判定
                if (!fileChanges.isEmpty()) {
                    // R3：诊断收集、指纹与文件版本回写由 FileChangeService 负责
                    FileChangeService.Collected collected =
                            fileChangeService.collect(workspacePath, fileChanges);
                    com.capsule.agentkey.diagnostics.DiagnosticsResult diagnostics =
                            collected.diagnostics();
                    String fingerprint = collected.fingerprint();
                    if (diagnostics.available() || diagnostics.analyzedFiles() > 0) {
                        callback.onDiagnostics(diagnostics);
                    }
                    if (diagnostics.analyzedFiles() > 0) {
                        ObjectNode diagMsg = mapper.createObjectNode();
                        diagMsg.put("role", "user");
                        diagMsg.put("content", diagnostics.formatForPrompt());
                        deferredUserMessages.add(diagMsg);
                        if (diagnostics.hasDiagnostics()) {
                            com.capsule.agentkey.diagnostics.Diagnostic first =
                                    diagnostics.diagnostics().get(0);
                            LoopGuard.Signal diagSignal = loopGuard.onDiagnostics(
                                    fingerprint, first.file() + ":" + first.line());
                            if (diagSignal.isNudge()) {
                                deferredUserMessages.add(userMessage(diagSignal.message()));
                            }
                            if (diagSignal.isStop()) {
                                termination.stopForNoProgress(callback, workspaceChanged,
                                        termination.toNoProgress(diagSignal));
                                return;
                            }
                        } else {
                            loopGuard.onDiagnostics(null, null);
                        }
                    }
                }

                //第六十八批 A1：update_plan 更新了计划 → 推送最新快照
                WorkspaceContext ctx = WorkspaceContext.get();
                if (ctx != null && ctx.drainPlanChanged()) {
                    roundProgress = true;
                    callback.onPlan(ctx.plan());
                }

                if (!result.isSuccess()) {
                    LoopGuard.Signal failSignal = loopGuard.onFailure(toolName,
                            result.getError() != null ? result.getError() : result.getContent());
                    if (failSignal.isNudge()) {
                        deferredUserMessages.add(userMessage(failSignal.message()));
                    }
                    if (failSignal.isStop()) {
                        termination.stopForNoProgress(callback, workspaceChanged,
                                termination.toNoProgress(failSignal));
                        return;
                    }
                } else {
                    loopGuard.onToolSuccess();
                }

                //P5-08：停止生成后不再执行本轮剩余调用（含已入池但未开始的只读任务），
                //按取消语义立即收尾，而不是等下一轮开头才发现。
                if (cancelRequested != null && cancelRequested.get()) {
                    log.info("用户已停止生成，取消本轮剩余工具调用");
                    termination.finalizePlan(callback);
                    callback.onCompletion(new Completion(CompletionStatus.INCOMPLETE, "CANCELLED",
                            "本轮执行已取消，任务尚未完成。", workspaceChanged));
                    callback.onComplete();
                    return;
                }
            }

            //本组 tool 响应已全部就位，诊断等 user 消息现在才能入列（排在 tool 响应之后）
            messages.addAll(deferredUserMessages);

            //P5-06：一轮写操作结束 → 执行一次项目验证。通过则告诉模型不要重复验证；
            //失败则在预算内回灌输出，让模型修复；超过重试或重复指纹阈值立即停止。
            if (roundFileChanged) {
                changeEpoch++;
                com.capsule.agentkey.verify.VerifyResult verify =
                        runVerification(workspacePath, cancelRequested, ++verifyAttempts, callback);
                verifiedEpoch = changeEpoch;
                if (verify.failed()) {
                    LoopGuard.Signal verifySignal = loopGuard.onVerifyFailure(
                            verify.failureFingerprint(), verify.message());
                    if (verifySignal.isStop()) {
                        termination.stopForNoProgress(callback, workspaceChanged,
                                termination.toNoProgress(verifySignal));
                        return;
                    }
                    if (verify.attempt() >= verify.maxAttempts()) {
                        termination.stopWithCompletion(callback, workspaceChanged, "VERIFY_RETRY_EXHAUSTED",
                                "验证连续失败 " + verify.attempt() + " 次，已达到重试上限，任务尚未完成。");
                        return;
                    }
                    addUserMessage(messages, verify.formatForPrompt());
                    if (verifySignal.isNudge()) {
                        addUserMessage(messages, verifySignal.message());
                    }
                } else {
                    loopGuard.onVerifyFailure(null, null);
                    if (verify.passed()) {
                        addUserMessage(messages, verify.formatForPrompt());
                    } else if (!verifyUnavailableNotified) {
                        //未配置/环境不可用只需提示一次，避免每轮重复噪声
                        verifyUnavailableNotified = true;
                        addUserMessage(messages, verify.formatForPrompt());
                    }
                }
            }

            LoopGuard.Signal roundSignal = loopGuard.onRoundEnd(roundProgress);
            if (roundSignal.isNudge()) {
                addUserMessage(messages, roundSignal.message());
            }
            if (roundSignal.isStop()) {
                termination.stopForNoProgress(callback, workspaceChanged, termination.toNoProgress(roundSignal));
                return;
            }
            round++;
        }

        //P6-06：停止后不再做收尾生成，但计划仍然要标记 aborted
        if (cancelRequested != null && cancelRequested.get()) {
            log.info("用户已停止生成，跳过收尾生成");
            termination.finalizePlan(callback);
            callback.onCompletion(new Completion(CompletionStatus.INCOMPLETE, "CANCELLED",
                    "本轮执行已取消，任务尚未完成。", workspaceChanged));
            callback.onComplete();
            return;
        }

        //轮次耗尽：不再允许工具调用，让模型基于已收集的工具结果做最终生成。
        //原实现直接 onComplete，用户收不到任何实质回答——这是核心体验 bug。
        //第五十三批修复：追加一条明确的 user 指令，防止模型输出 tool_calls 意图或内部推理标记（如 DSML）。
        callback.onChunk("\n\n（已达到最大工具调用轮次，以下基于已获取的信息作答）\n\n");
        ObjectNode finalInstruction = mapper.createObjectNode();
        finalInstruction.put("role", "user");
        finalInstruction.put("content",
                "请基于以上已经收集到的全部工具结果，直接输出你的最终文本回答。"
                + "不要再调用任何工具（本轮工具调用上限已耗尽），也不要输出任何工具调用意图、"
                + "内部推理标记（如 <DSML>、<tool_call> 等）、或思考过程，"
                + "只输出面向用户的最终回答文本。");
        messages.add(finalInstruction);
        //第三个参数传空工具列表：收尾这一次调用模型不能再发起 tool_calls，只能输出文本答案
        //R4：统一走 StreamPublisher，chunk/usage/预算判定与主循环一致
        streamPublisher.stream(springAiClient, invocation.apiKey(), policy.model(),
                assembleContext(contextAssembler, messages), List.of(),
                advisorSummary, callback::onChunk, callback::onUsage,
                promptTokensTotal, completionTokensTotal, limits.maxTokensPerRun());
        // 批次 B 修复：finalizePlan 必须在 onComplete 之前推 plan 事件，
        // 因为 onComplete 发 SSE done 后前端就停止处理后续事件了
        termination.finalizePlan(callback);
        callback.onCompletion(new Completion(CompletionStatus.INCOMPLETE, "MAX_TOOL_ROUNDS",
                "已达到最大工具调用轮次，任务尚未确认完成。", workspaceChanged));
        callback.onComplete();
    }


    /** P5-06：执行一次项目验证；任何异常都降级为 UNAVAILABLE，并始终推送 onVerify */
    private com.capsule.agentkey.verify.VerifyResult runVerification(
            String workspacePath, AtomicBoolean cancelRequested, int attempt, StreamCallback callback) {
        com.capsule.agentkey.verify.VerifyResult result;
        try {
            java.nio.file.Path root = workspacePath == null || workspacePath.isBlank()
                    ? null : java.nio.file.Paths.get(workspacePath);
            result = verifyService.verify(root, attempt, cancelRequested);
        } catch (Exception e) {
            log.warn("验证执行异常，降级为不可用: {}", e.getMessage(), e);
            result = new com.capsule.agentkey.verify.VerifyResult(
                    com.capsule.agentkey.verify.VerifyResult.Status.UNAVAILABLE,
                    ".lunargold/verify.json", false, attempt, Math.max(1, attempt),
                    java.util.List.of(), "", "验证执行异常：" + e.getMessage());
        }
        WorkspaceContext ctx = WorkspaceContext.get();
        if (ctx != null) {
            ctx.setVerify(result);
        }
        callback.onVerify(result);
        return result;
    }

    /** R5：未启用上下文接缝时原样返回权威消息列表，保证默认行为零改动 */
    private List<ObjectNode> assembleContext(ContextAssembler assembler, List<ObjectNode> messages) {
        return assembler == null ? messages : assembler.assemble(messages);
    }

    /** P5-06：把验证结果作为 user 消息注入下一轮模型上下文 */
    private void addUserMessage(List<ObjectNode> messages, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        messages.add(userMessage(content));
    }

    /** R4：统一的 user 消息节点，供 nudge / 诊断 / 计划快照复用 */
    private ObjectNode userMessage(String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", content);
        return message;
    }

    /** P5-10-3：时间预算是否已耗尽；maxSeconds <= 0 表示不限制。 */
    static boolean isTimeBudgetExceeded(long elapsedMs, long maxSeconds) {
        return maxSeconds > 0 && elapsedMs >= maxSeconds * 1000L;
    }

    /** 收口未完成计划；异常路径由 {@link ReActAgentService} 调用，保证与旧实现一致 */
    public void finalizePlan(StreamCallback callback) {
        termination.finalizePlan(callback);
    }
}
