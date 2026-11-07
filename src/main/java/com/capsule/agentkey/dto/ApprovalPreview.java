package com.capsule.agentkey.dto;

/**
 * 工具调用审批的可读预览。
 *
 * 审批卡片不再直接把工具入参 JSON 甩给用户（会暴露路径、转义字符、整份文件内容），
 * 而是由后端整理成「要做什么 + 影响哪个文件 + 改了多少行」的结构化摘要。
 */
public record ApprovalPreview(String kind,      // write / edit / command / other
                              String path,      // 相对工作区路径（文件类工具）
                              String command,   // 命令原文（run_command）
                              String diff,      // 截断后的逐行 diff 或内容预览
                              int additions,
                              int deletions,
                              boolean newFile,
                              String note) {
}
