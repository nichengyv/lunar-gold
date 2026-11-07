package com.capsule.agentkey.dto;

import com.capsule.agentkey.entity.Project;

import java.time.LocalDateTime;

/**
 * 项目对外视图：不暴露 userId 等内部字段。
 */
public record ProjectSummary(
        Long id,
        String name,
        String description,
        boolean defaultFlag,
        String instruction,
        String workspacePath,
        /** 项目默认工作权限：READ_ONLY / WRITE / EXECUTE，null 表示未设置（按可执行） */
        String defaultPermission,
        LocalDateTime createTime) {

    public static ProjectSummary from(Project p) {
        return new ProjectSummary(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.isDefaultFlag(),
                p.getInstruction(),
                p.getWorkspacePath(),
                p.getDefaultPermission(),
                p.getCreateTime());
    }
}
