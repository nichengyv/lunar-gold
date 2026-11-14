package com.capsule.agentkey.dto;

import lombok.Data;

import java.util.List;

/** P5-10-2：批量允许低风险审批的请求 */
@Data
public class ApprovalBatchRequest {
    /** 当前会话 id，服务端据此校验审批归属 */
    private Long sessionId;
    /** 待批量允许的审批 id；只处理仍在本会话等待中的低风险写入 */
    private List<String> approvalIds;
}
