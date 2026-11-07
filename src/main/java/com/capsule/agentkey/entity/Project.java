package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 项目（工作区）：资料与对话都归属于某个项目，检索只在本项目内进行。
 * 每个用户注册后自动拥有一个「默认项目」，老数据在迁移时统一归入其中。
 */
@Data
@Entity
@Table(name = "project")
@EntityListeners(AuditingEntityListener.class)
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** 默认项目标记：数据库列名为 is_default（default 是 SQL 关键字） */
    @Column(name = "is_default", nullable = false)
    private boolean defaultFlag = false;

    /** 项目级自定义指令：回答前拼进 system prompt，可空 */
    @Column(columnDefinition = "TEXT")
    private String instruction;

    /** 工作区磁盘目录的绝对路径；为空表示未绑定工作区，Agent 无法操作文件 */
    @Column(columnDefinition = "TEXT")
    private String workspacePath;

    /** 项目默认工作权限：新会话未显式指定时继承；为空按 EXECUTE 处理 */
    @Column(name = "default_permission", length = 20)
    private String defaultPermission;

    @CreatedDate
    private LocalDateTime createTime;

    @LastModifiedDate
    private LocalDateTime updateTime;
}
