package com.capsule.agentkey.service;

import com.capsule.agentkey.client.SpringAiAgentClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * 视觉转写服务：把用户上传的截图交给 DeepSeek 视觉模型（deepseek-flash），
 * 得到一份文字转写结果，再注入到不具备视觉能力的主对话模型里。
 *
 * 这样做的原因：
 * 1. 主对话链路要跑 ReAct 工具循环（deepseek-chat），图片只在 user 消息里合法；
 * 2. 每轮工具调用都会重新发送整段上下文，内联图片会让 token 成倍上涨；
 * 3. 转写一次后以文本形式复用，既省钱又能让 Agent 在后续轮次里反复引用。
 */
@Service
@Slf4j
public class VisionService {

    private final SpringAiAgentClient springAiClient;

    @Value("${deepseek.vision-model:deepseek-flash}")
    private String visionModel;

    @Value("${deepseek.vision-enabled:true}")
    private boolean visionEnabled;

    public VisionService(SpringAiAgentClient springAiClient) {
        this.springAiClient = springAiClient;
    }

    public boolean isEnabled() {
        return visionEnabled;
    }

    /**
     * 转写图片内容。
     * @param apiKey     用户配置的 DeepSeek Key（与主对话共用）
     * @param images     base64 data URL 形式的图片
     * @param userPrompt 用户随图片提出的问题，用于让转写有针对性
     * @return 转写文本；未开启或没有图片时返回空串
     */
    public String describeImages(String apiKey,
                                 List<ChatAttachmentService.ImageInput> images,
                                 String userPrompt) throws IOException {
        if (!visionEnabled || images == null || images.isEmpty()) {
            return "";
        }

        //UserMessage + Media 走多模态调用（第四十八批接入，第四十九批删旧后唯一路径）
        String instruction = buildInstruction(userPrompt);
        List<String> dataUrls = images.stream().map(ChatAttachmentService.ImageInput::dataUrl).toList();
        String text = springAiClient.callVision(apiKey, visionModel, instruction, dataUrls).trim();
        if (text.isBlank()) {
            log.warn("视觉模型返回空内容 [model={}]", visionModel);
            return "";
        }
        log.info("视觉模型转写完成 [model={}, images={}, chars={}]", visionModel, images.size(), text.length());
        return text;
    }

    private String buildInstruction(String userPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个视觉转写助手：把图片内容转成准确、结构化的文字，供没有视觉能力的代码 Agent 使用。\n")
                .append("规则：\n")
                .append("1. 若图片是报错、日志、终端输出：逐字转写报错信息、堆栈、文件路径与行号，保持原样，不要翻译、不要省略；\n")
                .append("2. 若图片是 IDE 或网页界面：说明界面类型、所在页面、关键按钮与可见文案；\n")
                .append("3. 若图片是代码：完整转写代码，保留缩进与语言特征；\n")
                .append("4. 若图片是图表、设计稿：描述结构、文案、数值与布局要点；\n")
                .append("5. 看不清的地方写「[看不清]」，不要编造。\n")
                .append("只输出转写结果，不要给修复建议、不要额外寒暄。\n");
        String p = userPrompt == null ? "" : userPrompt.trim();
        sb.append("用户同时提出的问题：").append(p.isEmpty() ? "（无额外说明，只需完整转写图片）" : p);
        return sb.toString();
    }
}
