package com.capsule.agentkey.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ChatRequest {
    private String prompt;
    private Long sessionId;
    /**
     * 当前项目：知识库检索与会话列表都按项目隔离；
     * 不传时后端回落到默认项目，已存在会话则以其自身归属为准。
     */
    private Long projectId;
    /**
     * 是否启用 RAG 知识库检索。
     * 默认 true：检索在当前项目内进行，任何会话都应默认尝试检索，
     * 避免出现"同一个项目里有的会话能查到资料、有的查不到"的不一致表现。
     * 前端若明确传 false（用户手动关闭 RAG 开关），仍可关闭。
     */
    private boolean useRag = true;
    private List<Long> attachmentIds = new ArrayList<>();
}