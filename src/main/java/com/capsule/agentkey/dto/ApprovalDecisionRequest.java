package com.capsule.agentkey.dto;

import lombok.Data;

/** 第六十八批 A2：前端提交的工具审批结果 */
@Data
public class ApprovalDecisionRequest {
    /** APPROVE / REJECT */
    private String decision;
    /** NONE / SESSION：SESSION 表示本会话内不再询问该工具 */
    private String remember = "NONE";
}
