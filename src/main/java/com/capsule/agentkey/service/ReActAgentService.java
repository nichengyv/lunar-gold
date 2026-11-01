package com.capsule.agentkey.service;

import com.capsule.agentkey.client.DeepSeekAgentClient;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.capsule.agentkey.tool.WorkspaceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;



//Agent大脑决策
@Service
@Slf4j
public class ReActAgentService {

    private final DeepSeekAgentClient llmClient;
    private final ToolRegistry toolRegistry;
    private final DocumentService documentService;
    private final ProjectService projectService;
    private final WorkspaceFileService workspaceFileService;
    private final ObjectMapper mapper = new ObjectMapper();

    //B2：送入大模型的单条工具结果上限（字符）。网页抓取/文档片段可能极大，
    //不截断的话一次工具调用就能撑爆上下文窗口、推高 token 费用
    private static final int TOOL_CONTENT_LIMIT = 2000;

    //B2：超长工具结果截断，末尾附提示让模型知道内容不完整。
    //构建/测试日志的关键信息（报错行、失败原因）通常在末尾，只留头部会让模型看不到问题，
    //所以改成「头部 + 尾部」各留一半。
    private String truncateForContext(String s) {
        if (s == null || s.length() <= TOOL_CONTENT_LIMIT) {
            return s;
        }
        int half = TOOL_CONTENT_LIMIT / 2;
        return s.substring(0, half)
                + "\n…（中间内容省略）…\n"
                + s.substring(s.length() - half);
    }

    public ReActAgentService(DeepSeekAgentClient llmClient,
                             ToolRegistry toolRegistry,
                             DocumentService documentService,
                             ProjectService projectService,
                             WorkspaceFileService workspaceFileService) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.documentService = documentService;
        this.projectService = projectService;
        this.workspaceFileService = workspaceFileService;
    }

    //流式回调接口
    public interface StreamCallback {
        void onThinking(String thought);//模型思考内容
        void onToolCall(String toolName, String arguments);//触发工具调用，显示调用工具，入参
        void onToolResult(String toolName, ToolResult result);//工具执行完毕，推送工具返回结果
        //工作区文件被工具改动时推送（write_file/edit_file/run_command），前端据此展示变更文件与 diff
        default void onFileChange(com.capsule.agentkey.tool.FileChange change) {}
        void onChunk(String chunk);//大模型普通文字流式分片，逐字返回给前端
        //命中的资料出处，用于回答下方展示「参考了哪些资料」；无命中时不触发
        default void onSources(java.util.List<DocumentService.Source> sources) {}
        void onComplete();//整套智能体流程全部结束
        void onError(Throwable t);//发生异常，推送错误报告
    }

    //alt+ctrl+f7查找使用处
    public void execute(String apiKey, //大模型密钥
                        AgentPolicy policy,//本次对话的模型/推理档位/工作权限
                        String userPrompt,//用户当前提问文本
                        List<ChatMessage> history,//历史聊天记录
                        Long userId,//登录用户id，用于RAG检索该用户的附件向量
                        Long projectId,//当前项目id，RAG 只检索本项目内的资料
                        boolean useRag,//开关，是否开启知识库检索
                        String fileContext,//附带文本文件
                        String imageContext,//截图经视觉模型转写后的文字（无图时为空）
                        String contextSummary,//较早对话的滚动摘要（长会话压缩后注入，无摘要时为空）
                        String sessionReferenceContext,//用户用 @会话 点名的其他会话的最近问答（无点名时为空）
                        AtomicBoolean cancelled,//B3：客户端断连标记，SSE 写出失败即置位
                        StreamCallback callback) {//流式回调，向前端推送每一步状态
        //工作区工具需要 userId/projectId 上下文，用 ThreadLocal 透传（dispatch 签名不变）；
        //cancelled 让 run_command 这类长耗时工具能在用户停止生成时杀掉子进程
        WorkspaceContext.set(userId, projectId, cancelled);
        try {
            //如果有需要，先检索知识库
            String ragContext = "";
            if (useRag && userId != null) {
                try {
                    //消息里用 @资料:《文件名》点名的资料，检索时只在这些资料内查
                    List<Long> referencedDocs = documentService.resolveReferencedDocuments(userId, projectId, userPrompt);
                    //检索进度不写进思考链：它是内部动作，不是模型的推理过程，
                    //留在思考记录里会让用户以为回答结束后还在检索。
                    //最后参数指最相关的三条文档片段
                    DocumentService.RagResult rag = documentService.retrieveContext(
                            userId, projectId, userPrompt, 3, referencedDocs);
                    ragContext = rag.context();
                    if (!rag.sources().isEmpty()) {
                        callback.onSources(rag.sources());//先把出处推给前端，回答里就能标注来源
                        log.info("RAG 检索到 {} 条相关知识", rag.sources().size());
                    }
                } catch (Exception e) {
                    log.warn("RAG 检索失败，降级为普通模式: {}", e.getMessage());
                }
            }

            //构建消息（系统提示词 + 历史记录 + 用户问题）
            //先拿项目级自定义指令，拼进 system prompt；拿不到或为空则按无指令处理，不阻塞对话
            String projectInstruction = "";
            String workspaceTree = "";
            try {
                Long resolvedId = projectService.resolveProjectId(userId, projectId);
                com.capsule.agentkey.entity.Project p = projectService.getOwned(userId, resolvedId);
                projectInstruction = p.getInstruction();
                //如果绑定了工作区，把根目录文件树注入 system prompt，让 Agent 知道项目结构
                if (p.getWorkspacePath() != null) {
                    workspaceTree = buildWorkspaceTree(p.getWorkspacePath());
                }
            } catch (Exception e) {
                log.warn("读取项目指令/工作区失败，降级: {}", e.getMessage());
            }

            List<ObjectNode> messages = buildMessages(history, userPrompt, ragContext, fileContext,
                    imageContext, contextSummary, sessionReferenceContext,
                    projectInstruction, workspaceTree, policy);
            if (!ragContext.isEmpty()) {
                log.info("✅ 知识库上下文已注入 system 消息（{} 字符），大模型可见", ragContext.length());
            }
            //工具清单按工作权限过滤：只读看不到写文件/执行命令
            List<Map<String, Object>> tools = toolRegistry.buildToolDefinitions(policy.permission());
            log.info("🧩 本轮策略: model={}, reasoning={}, permission={}, tools={}",
                    policy.model(), policy.reasoning().name(), policy.permission().name(), tools.size());

            int round = 0;
            //开始 ReAct 循环（轮次上限由推理档位决定：快速 4 / 标准 10 / 深度 16）
            while (round < policy.reasoning().maxToolRounds()) {
                //B3：客户端已断开（SSE 写出失败）→ 立即中止，不再发起下一次 LLM 调用，
                //避免用户关掉页面后智能体继续空转消耗 token
                if (cancelled != null && cancelled.get()) {
                    log.info("客户端已断开，中止 ReAct 推理 [round={}]", round + 1);
                    return;
                }
                log.info("🧠 ReAct 第 {} 轮推理", round + 1);

                // 决策 + 作答合并成一次「流式」调用：
                // 模型只输出文本 → 边生成边推给前端（真正的打字机效果）；
                // 模型要调工具 → 先把分片参数累积成完整 tool_calls，再执行工具进入下一轮。
                StringBuilder streamedText = new StringBuilder();
                List<ObjectNode> streamedToolCalls = new ArrayList<>();
                AtomicReference<Throwable> streamError = new AtomicReference<>();

                llmClient.streamChat(apiKey, policy.model(), messages, tools, new DeepSeekAgentClient.Callback() {
                    @Override
                    public void onChunk(String chunk) {
                        streamedText.append(chunk);
                        callback.onChunk(chunk);
                    }

                    @Override
                    public void onToolCalls(List<ObjectNode> toolCalls) {
                        streamedToolCalls.addAll(toolCalls);
                    }

                    @Override
                    public void onComplete() {
                        // 一轮结束由外层决定：继续工具循环还是收尾
                    }

                    @Override
                    public void onError(Throwable t) {
                        streamError.set(t);
                    }
                });

                if (streamError.get() != null) {
                    throw new RuntimeException(streamError.get());
                }

                if (streamedToolCalls.isEmpty()) {//模型没要求调工具 → 文本已经边生成边推送完毕
                    callback.onComplete();//流式回调通知：本轮全部输出完毕，前端关闭加载，思考动画
                    return;
                }

                //把模型这一轮「调用工具」的意图写回上下文，后续 tool 消息才能对应上
                ObjectNode assistantMsg = mapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                if (streamedText.length() > 0) {
                    assistantMsg.put("content", streamedText.toString());
                } else {
                    assistantMsg.putNull("content");
                }
                assistantMsg.set("tool_calls", mapper.valueToTree(streamedToolCalls));
                messages.add(assistantMsg);

                for (ObjectNode toolCall : streamedToolCalls) {//循环遍历，支持一次调用多个工具
                    String toolName = toolCall.path("function").path("name").asText();
                    String arguments = toolCall.path("function").path("arguments").asText();//工具所需的入参
                    String toolCallId = toolCall.path("id").asText();//工具调用的唯一编号

                    callback.onToolCall(toolName, arguments);
                    log.info("🔧 调用工具: {} 参数: {}", toolName, arguments);

                    ToolResult result = toolRegistry.dispatch(toolName, arguments, policy.permission());//根据工具名称找到对应实现类
                    callback.onToolResult(toolName, result);
                    log.info("🔧 工具结果: success={}, cost={}ms", result.isSuccess(), result.getCostMs());

                    //工具改动了工作区文件 → 逐个推给前端（在 tool_result 之后，保证顺序稳定）
                    for (com.capsule.agentkey.tool.FileChange change : WorkspaceContext.drainFileChanges()) {
                        callback.onFileChange(change);
                    }


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
                    toolMsg.put("content", truncateForContext(toolContent));

                    messages.add(toolMsg);//把组装完毕的tool消息存入全局消息数组
                }
                round++;
            }

            //B3：断连后不再做收尾生成
            if (cancelled != null && cancelled.get()) {
                log.info("客户端已断开，跳过收尾生成");
                return;
            }

            //轮次耗尽：不再允许工具调用，让模型基于已收集的工具结果做最终生成。
            //原实现直接 onComplete，用户收不到任何实质回答——这是核心体验 bug
            callback.onChunk("\n\n（已达到最大工具调用轮次，以下基于已获取的信息作答）\n\n");
            //第三个参数传空工具列表：收尾这一次调用模型不能再发起 tool_calls，只能输出文本答案
            AtomicReference<Throwable> finalError = new AtomicReference<>();
            llmClient.streamChat(apiKey, policy.model(), messages, List.of(), new DeepSeekAgentClient.Callback() {
                @Override
                public void onChunk(String chunk) {
                    callback.onChunk(chunk);
                }

                @Override
                public void onComplete() {
                    //收尾由下面统一触发
                }

                @Override
                public void onError(Throwable t) {
                    finalError.set(t);
                }
            });
            if (finalError.get() != null) {
                throw new RuntimeException(finalError.get());
            }
            callback.onComplete();

        } catch (Exception e) {
            log.error("ReAct 执行异常", e);
            callback.onError(e);
        } finally {
            WorkspaceContext.clear();
        }
    }

    private List<ObjectNode> buildMessages(List<ChatMessage> history, String userPrompt,
                                           String ragContext, String fileContext, String imageContext,
                                           String contextSummary,
                                           String sessionReferenceContext,
                                           String projectInstruction, String workspaceTree,
                                           AgentPolicy policy) {
        List<ObjectNode> messages = new ArrayList<>();

        StringBuilder systemContent = new StringBuilder(
                "你是一个通用智能助手，具备天气查询、联网搜索、知识库问答、文件分析等能力。\n"
                        + "\n"
                        + "【核心原则】\n"
                        + "1. 用户问天气、气温、穿衣建议时 → 必须调用 get_current_weather 工具\n"
                        + "2. 用户问最新新闻、实时信息、你不确定的知识时 → 必须调用 web_search 工具\n"
                        + "3. 用户上传了文件并询问文件内容时 → 基于文件内容回答，不要调用工具\n"
                        + "4. 用户问通用问题（编程、数学、常识、闲聊）→ 直接回答，不调用工具\n"
                        + "5. 工具返回错误时，如实告知用户，不要编造数据\n"
                        + "6. 如果下方提供了【知识库检索结果】或【用户上传的文件内容】，请优先基于这些信息回答\n"
                        + "\n"
                        + "【文件处理要求】\n"
                        + "1. 用户上传文件后，文件内容会附在问题之前\n"
                        + "2. 回答时明确指出你引用了哪个文件\n"
                        + "3. 用户上传图片时，系统会先用视觉模型把图片转写成文字（见【用户上传的截图】），"
                        + "直接基于转写内容回答；不要声称自己看不到图片\n"
                        + "4. 如果文件内容过长被截断，告知用户已基于部分内容回答\n"
                        + "\n"
                        + "【文件修改规则】\n"
                        + "1. 修改文件前先用 read_file 读取实际内容，oldText 必须与文件完全一致（空格、缩进、换行都要一致）\n"
                        + "2. oldText 只取「要改动的最小片段」，不要整行/整段复制。"
                        + "例如在某一行的句尾追加文字：oldText 用该行末尾 2-5 个已有字符，newText = 这些字符 + 要追加的内容\n"
                        + "3. 除非用户明确要求重写整行，否则不要删掉整行再加回整行；"
                        + "未被要求改动的原有文字必须原样保留，否则差异视图会显示成「整行删除再新增」\n"
                        + "4. 在指定行插入内容时：用该行或相邻行的唯一片段作为锚点，不要覆盖整行\n"
                        + "5. 修改完成后用 read_file 复核结果\n"
                        + "\n"
                        + "【天气播报要求】\n"
                        + "当调用天气工具后，生成一段完整的天气播报，包含：城市、天气状况、温度、体感温度、湿度、风速。\n"
                        + "播报要口语化、有温度，不要罗列原始数据。\n"
                        + "必须给出生活建议（穿衣、是否带伞、是否适合户外活动）\n"
                        + "\n"
                        + "【知识库引用要求】\n"
                        + "1. 回答时如果使用了知识库内容，请在相关段落末尾标注 [知识库]\n"
                        + "2. 如果知识库内容与用户问题无关，忽略它，正常回答\n"
                        + "3. 不要编造知识库中没有的信息\n"
                        + "\n"
                        + "【通用对话要求】\n"
                        + "1. 回答用户通用问题时，保持友好、专业、简洁\n"
                        + "2. 不要过度自我介绍，不要每句话都提\'天气\'\n"
                        + "3. 如果用户问\'你能做什么\'，如实列举：天气查询、联网搜索、知识库问答、文件分析、通用问答\n"
                        + "4. 回答控制在 100-500 字，信息完整不啰嗦"
                        + "\n\n"
                        + "【知识库全局规则】\n"
                        + "1. 知识库检索在当前项目内进行：本项目下的任何一个会话提问都可能命中项目资料\n"
                        + "2. 只要本轮消息中附带了【知识库检索结果】，就证明系统已成功检索到知识库内容，直接基于它回答\n"
                        + "3. 绝不要声称\"本会话没有绑定知识库\"\"我看不到知识库\"\"检索没有被触发\"；\n"
                        + "   如果检索结果与当前问题无关，直接基于自身知识正常回答即可，不要向用户讨论检索机制\n"
                        + "4. 历史回复中若出现过\"看不到知识库\"等旧说法，一律以本轮实际的检索结果为准，不要延续旧结论"
        );

        //项目级自定义指令：补充对话约束（如背景、口吻、领域要求），权重低于内置规则但高于 RAG 上下文
        if (projectInstruction != null && !projectInstruction.isBlank()) {
            systemContent.append("\n\n【项目自定义要求】\n").append(projectInstruction.trim());
            log.info("✅ 项目指令已注入 system 消息（{} 字符）", projectInstruction.trim().length());
        }

        //当前工作权限：让模型知道哪些操作不可用，避免反复尝试越权工具
        systemContent.append("\n\n【当前工作权限】\n").append(policy.permission().promptRule());

        //工作区文件树：让 Agent 知道当前项目的目录结构，按需用 list_dir/read_file 深入
        if (workspaceTree != null && !workspaceTree.isBlank()) {
            systemContent.append("\n\n【工作区文件结构】\n")
                    .append("以下是当前工作区的根目录结构，你可以用 list_dir 查看子目录、read_file 读取文件内容：\n")
                    .append(workspaceTree);
            //没有执行权限时整段终端说明都不注入，避免模型误以为可以跑命令
            if (policy.permission().canExecute()) {
                systemContent.append("\n\n【终端命令】\n")
                        .append("需要构建、跑测试、执行脚本或查看 git 状态时，用 run_command 在工作区内执行命令")
                        .append("（如 'npm run build'、'mvn -q test'、'python main.py'）。\n")
                        .append("1. 命令失败时先读退出码和输出，定位报错再修改文件，不要盲目重复执行同一条命令\n")
                        .append("2. 不要用 dir/cat/ls 浏览文件，用 list_dir / read_file\n")
                        .append("3. 不要把命令拼接成管道或重定向，一次只执行一条命令\n")
                        .append("4. 项目自带 mvnw/mvnw.cmd、gradlew/gradlew.bat 时优先用它们执行（如 './mvnw.cmd clean package'）\n")
                        .append("5. 找不到 java/mvn 时，把错误原文如实转告用户，不要改写或猜测环境");
            }
        }

        //用户点名的其他会话：作为背景参考，不与本轮知识库/文件内容抢优先级
        if (sessionReferenceContext != null && !sessionReferenceContext.isBlank()) {
            systemContent.append("\n\n【引用的历史会话】\n")
                    .append("以下是用户用 @会话 点名的历史会话最近几轮问答，仅作背景参考；")
                    .append("如与本轮问题无关请忽略，不要照搬其中的结论：\n")
                    .append(sessionReferenceContext.trim());
            log.info("✅ 点名会话上下文已注入 system 消息（{} 字符）", sessionReferenceContext.trim().length());
        }

        //较早对话的滚动摘要：原文被预算截掉后，靠这段摘要保住用户目标、已定结论和未完成事项
        if (contextSummary != null && !contextSummary.isBlank()) {
            systemContent.append("\n\n【会话历史摘要】\n")
                    .append("以下是本会话中更早对话的压缩摘要，用于保持连贯；")
                    .append("若与最近几条原文消息冲突，一律以最近原文为准：\n")
                    .append(contextSummary.trim());
            log.info("✅ 会话历史摘要已注入 system 消息（{} 字符）", contextSummary.trim().length());
        }

        if (ragContext != null && !ragContext.isEmpty()) {
            systemContent.append("\n\n").append(ragContext);//吧RAG向量检索出的参考文档拼接到提示词末尾
        }

        //截图经视觉模型转写后的文字：主对话模型不直接看图片，靠这段文字理解截图
        if (imageContext != null && !imageContext.isBlank()) {
            systemContent.append("\n\n【用户上传的截图（视觉模型转写）】\n")
                    .append("以下内容由视觉模型从用户截图中转写而来，可能有 OCR 误差；")
                    .append("涉及报错、路径、代码时以这里的原文为依据：\n")
                    .append(imageContext.trim());
            log.info("✅ 截图转写已注入 system 消息（{} 字符）", imageContext.trim().length());
        }

        ObjectNode systemMsg = mapper.createObjectNode();//构建system角色消息，加入消息列表。它用于给AI设定身份，约束回答规则
        systemMsg.put("role", "system");
        systemMsg.put("content", systemContent.toString());
        messages.add(systemMsg);


        if (history != null && !history.isEmpty()) {
            // B1 历史消息双重上限：条数上限 + 字符预算，防止长会话把上下文撑爆。
            // 条数：默认 10 条；命中知识库时收窄到 4 条。
            // 字符预算：从最新一条往前累积，超过 6000 字即停止；但无论预算如何，至少保留最近 2 条。
            // 原因：老会话的历史里常残留"上传文档之前"的旧问答（当时模型如实回答
            // "知识库里没有该文件"），这些旧结论会诱导模型为了前后一致而继续否认
            // 本轮已检索到的内容，表现为"老会话读不到新上传的文档，新会话却正常"。
            // 检索命中时只保留最近几轮，弱化旧结论干扰；未命中知识库时维持原状。
            //条数与字符预算都随推理档位变化：快速档更抠、深度档更宽
            int countLimit = policy.reasoning().historyCount(ragContext != null && !ragContext.isEmpty());
            final int CHAR_BUDGET = policy.reasoning().historyCharBudget();
            int start = history.size();
            int used = 0;
            while (start > 0
                    && (history.size() - start) < countLimit
                    && (used < CHAR_BUDGET || history.size() - start < 2)) {//不足 2 条时无视预算
                ChatMessage prev = history.get(start - 1);
                used += prev.getContent() == null ? 0 : prev.getContent().length();
                start--;
            }
            for (int i = start; i < history.size(); i++) {//循环读取历史记录，转换为api要求的json节点
                ChatMessage msg = history.get(i);
                ObjectNode node = mapper.createObjectNode();//用JSON库创建json节点
                node.put("role", msg.getRole().name().toLowerCase());//设置角色
                node.put("content", msg.getContent());//设置消息内容
                messages.add(node);
            }
        }

        //创建消息头，创建一个json节点，指定角色为user
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");

        StringBuilder finalPrompt = new StringBuilder();
        if (fileContext != null && !fileContext.isEmpty()) {
            finalPrompt.append(fileContext).append("\n");//拼接知识库或者附件的相关片段
        }

        finalPrompt.append(userPrompt != null ? userPrompt : "");
        userMsg.put("content", finalPrompt.toString());//把用户的问题拼接在参考资料后
        messages.add(userMsg);

        return messages;
    }

    /**
     * 构建工作区根目录的单层文件树文本（只列根目录，不递归，避免上下文过长）。
     * Agent 需要深入时自己调 list_dir。
     */
    private String buildWorkspaceTree(String workspaceRoot) {
        try {
            List<WorkspaceFileService.FileNode> nodes = workspaceFileService.listDir(workspaceRoot, "");
            if (nodes.isEmpty()) return "(空工作区)";
            StringBuilder sb = new StringBuilder();
            for (WorkspaceFileService.FileNode n : nodes) {
                String prefix = n.isDirectory() ? "📁 " : "📄 ";
                sb.append(prefix).append(n.name()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("构建工作区树失败: {}", e.getMessage());
            return "";
        }
    }
}