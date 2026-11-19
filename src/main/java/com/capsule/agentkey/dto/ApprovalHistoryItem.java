package com.capsule.agentkey.dto;

import java.time.LocalDateTime;

/**
 * P5-10-2：会话内审批历史项。
 * 不返回原始 arguments，保持审批卡片不暴露裸参数 JSON 的边界。
 * P6-05：clientId 为审批入口的客户端 id，Web 审批与旧记录为 null。
 */
public record ApprovalHistoryItem(String approvalId,
                                  String tool,
                                  String risk,
                                  String state,
                                  String decisionSource,
                                  String clientId,
                                  LocalDateTime decisionTime,
                                  LocalDateTime createTime,
                                  boolean rememberSession) {
}
