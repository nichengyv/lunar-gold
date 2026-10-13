package com.capsule.agentkey.service;

import com.capsule.agentkey.client.DeepSeekAgentClient;
import com.capsule.agentkey.entity.ChatMessage;
import com.capsule.agentkey.tool.ToolRegistry;
import com.capsule.agentkey.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;



//Agent大脑决策
@Service
@Slf4j
public class ReActAgentService {

    private final DeepSeekAgentClient llmClient;
    private final ToolRegistry toolRegistry;
    private final DocumentService documentService;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final int MAX_TOOL_ROUNDS = 3;

    public ReActAgentService(DeepSeekAgentClient llmClient,
                             ToolRegistry toolRegistry,
                             DocumentService documentService) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.documentService = documentService;
    }

    //流式回调接口
    public interface StreamCallback {
        void onThinking(String thought);//模型思考内容
        void onToolCall(String toolName, String arguments);//触发工具调用，显示调用工具，入参
        void onToolResult(String toolName, ToolResult result);//工具执行完毕，推送工具返回结果
        void onChunk(String chunk);//大模型普通文字流式分片，逐字返回给前端
        void onComplete();//整套智能体流程全部结束
        void onError(Throwable t);//发生异常，推送错误报告
    }

    //alt+ctrl+f7查找使用处
    public void execute(String apiKey, //大模型密钥
                        String userPrompt,//用户当前提问文本
                        List<ChatMessage> history,//历史聊天记录
                        Long userId,//登录用户id，用于RAG检索该用户的附件向量
                        boolean useRag,//开关，是否开启知识库检索
                        String fileContext,//附带文本文件
                        StreamCallback callback) {//流式回调，向前端推送每一步状态
        try {
            //如果有需要，先检索知识库
            String ragContext = "";
            if (useRag && userId != null) {
                try {
                    callback.onThinking("🔍 正在检索知识库...");
                    ragContext = documentService.retrieveContext(userId, userPrompt, 3);//最后参数指最相关的三条文档片段
                    if (!ragContext.isEmpty()) {
                        log.info("RAG 检索到 {} 条相关知识", ragContext.split("--- 片段").length - 1);
                    }
                } catch (Exception e) {
                    log.warn("RAG 检索失败，降级为普通模式: {}", e.getMessage());
                }
            }

            //构建消息（系统提示词 + 历史记录 + 用户问题）
            List<ObjectNode> messages = buildMessages(history, userPrompt, ragContext, fileContext);
            if (!ragContext.isEmpty()) {
                log.info("✅ 知识库上下文已注入 system 消息（{} 字符），大模型可见", ragContext.length());
            }
            List<Map<String, Object>> tools = toolRegistry.buildToolDefinitions();//定义工具列表

            int round = 0;
            //开始 ReAct 循环（最多3轮）
            while (round < MAX_TOOL_ROUNDS) {
                log.info("🧠 ReAct 第 {} 轮推理", round + 1);

                // 调用 DeepSeek，问它：你需要调用工具吗？
                // DeepSeek 返回 tool_calls → 调用工具
                // DeepSeek 返回 content → 直接回答，结束循环
                JsonNode response = llmClient.callSync(apiKey, messages, tools);//发送请求给大模型
                //取出返回结果的choices数组，取第一条回复（有多条候选回答），取这条回复里的message对象（里面包含content文本和tool_calls调用指令）
                JsonNode message = response.path("choices").path(0).path("message");
                JsonNode toolCalls = message.path("tool_calls");//从message对象提取tool_calls数组（判断模型想要调用工具还是直接输出答案）

                if (toolCalls == null || toolCalls.isEmpty()) {//若大模型没有给出工具调用指令
                    String finalContent = message.path("content").asText("");
                    if (!finalContent.isEmpty()) {//若回答不为空，则给前端
                        callback.onChunk(finalContent);
                    }
                    callback.onComplete();//流式回调通知：本轮全部输出完毕，前端关闭加载，思考动画
                    return;
                }

                //deepCopy());深拷贝，新建一份完全独立的json节点，避免引用污染
                //把模型想要调用工具的消息或者决策结果存入messages作为历史上下文
                messages.add((ObjectNode) message.deepCopy());

                for (JsonNode toolCall : toolCalls) {//循环遍历，支持一次调用多个工具
                    String toolName = toolCall.path("function").path("name").asText();
                    String arguments = toolCall.path("function").path("arguments").asText();//工具所需的入参
                    String toolCallId = toolCall.path("id").asText();//工具调用的唯一编号

                    callback.onToolCall(toolName, arguments);
                    log.info("🔧 调用工具: {} 参数: {}", toolName, arguments);

                    ToolResult result = toolRegistry.dispatch(toolName, arguments);//根据工具名称找到对应实现类
                    callback.onToolResult(toolName, result);
                    log.info("🔧 工具结果: success={}, cost={}ms", result.isSuccess(), result.getCostMs());


                    ObjectNode toolMsg = mapper.createObjectNode();//新建一个空白的json对象
                    //role标签规定该json对象的数据类型，方便大模型处理
                    //user：人类输入
                    //assistant：大模型自己说的
                    //tool：外部程序返回
                    toolMsg.put("role", "tool");

                    toolMsg.put("tool_call_id", toolCallId);
                    //工具执行成功：content填入返回数据
                    //执行失败：填入错误提示文本
                    toolMsg.put("content", result.isSuccess() ? result.getContent() : "错误: " + result.getError());

                    messages.add(toolMsg);//把组装完毕的tool消息存入全局消息数组
                }
                round++;
            }

            callback.onChunk("\n\n（思考轮次过多，基于已有信息给出最佳回答）");
            callback.onComplete();

        } catch (Exception e) {
            log.error("ReAct 执行异常", e);
            callback.onError(e);
        }
    }

    private List<ObjectNode> buildMessages(List<ChatMessage> history, String userPrompt,
                                           String ragContext, String fileContext) {
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
                        + "3. 如果文件是图片且无法识别内容，请诚实告知用户当前不支持图像识别\n"
                        + "4. 如果文件内容过长被截断，告知用户已基于部分内容回答\n"
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
                        + "1. 知识库检索按用户维度进行，与具体会话无关：任何一个会话提问都可能命中知识库\n"
                        + "2. 只要本轮消息中附带了【知识库检索结果】，就证明系统已成功检索到知识库内容，直接基于它回答\n"
                        + "3. 绝不要声称\"本会话没有绑定知识库\"\"我看不到知识库\"\"检索没有被触发\"；\n"
                        + "   如果检索结果与当前问题无关，直接基于自身知识正常回答即可，不要向用户讨论检索机制\n"
                        + "4. 历史回复中若出现过\"看不到知识库\"等旧说法，一律以本轮实际的检索结果为准，不要延续旧结论"
        );

        if (ragContext != null && !ragContext.isEmpty()) {
            systemContent.append("\n\n").append(ragContext);//吧RAG向量检索出的参考文档拼接到提示词末尾
        }

        ObjectNode systemMsg = mapper.createObjectNode();//构建system角色消息，加入消息列表。它用于给AI设定身份，约束回答规则
        systemMsg.put("role", "system");
        systemMsg.put("content", systemContent.toString());
        messages.add(systemMsg);


        if (history != null) {
            // 历史消息条数上限：默认 10 条；命中知识库时收窄到 4 条。
            // 原因：老会话的历史里常残留"上传文档之前"的旧问答（当时模型如实回答
            // "知识库里没有该文件"），这些旧结论会诱导模型为了前后一致而继续否认
            // 本轮已检索到的内容，表现为"老会话读不到新上传的文档，新会话却正常"。
            // 检索命中时只保留最近几轮，弱化旧结论干扰；未命中知识库时维持原状。
            int historyLimit = (ragContext != null && !ragContext.isEmpty()) ? 4 : 10;
            int start = Math.max(0, history.size() - historyLimit);//消息截断，只取最近 N 条聊天记录
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
}