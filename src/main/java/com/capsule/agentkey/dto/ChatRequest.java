package com.capsule.agentkey.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ChatRequest {
    private String prompt;
    private Long sessionId;
    /**
     * 是否启用 RAG 知识库检索。
     * 默认 true：知识库检索按用户维度进行、与会话无关，任何会话都应默认尝试检索，
     * 避免出现"同一个用户在不同会话里有的能查到知识库、有的查不到"的不一致表现。
     * 前端若明确传 false（用户手动关闭 RAG 开关），仍可关闭。
     */
    private boolean useRag = true;
    private List<Long> attachmentIds = new ArrayList<>();
}