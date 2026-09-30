package com.capsule.agentkey.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ChatRequest {
    private String prompt;
    private Long sessionId;
    private boolean useRag = false;
    private List<Long> attachmentIds = new ArrayList<>();
}