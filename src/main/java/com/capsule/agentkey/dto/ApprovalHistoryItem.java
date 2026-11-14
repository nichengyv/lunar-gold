package com.capsule.agentkey.dto;

import java.time.LocalDateTime;

/**
 * P5-10-2：会话内审批历史项。
 * 不返回原始 arguments，保持审批卡片不暴露裸参数 JSON 的边界。
 */
public record ApprovalHistoryItem(String approvalId,
                                  String tool,
                                  String risk,
                                  String state,
                                  String decisionSource,
                                  LocalDateTime decisionTime,
                                  LocalDateTime createTime,
                                  boolean rememberSession) {
}
