package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Data
@Entity//告诉JPA这是一个数据库实体类，和数据表映射
@Table(name = "user")//指定对应数据库表名
@EntityListeners(AuditingEntityListener.class)//绑定JPA审计监听器配合@EnableJpaAuditing才能启用CreateDate等自动时间填充
public class User {
    @Id//标记该字段为数据库主键
    @GeneratedValue(strategy = GenerationType.IDENTITY)//主键生成策略，：数据库自增
    private Long id;

    @Column(nullable = false, unique = true, length = 50)//字段约束（数据库列不能为null，用户名唯一，）
    private String username;

    @Column(nullable = false, length = 255)
    private String passwordHash;

    @Column(length = 100)
    private String email;

    @CreatedDate
    private LocalDateTime createTime;

    @LastModifiedDate
    private LocalDateTime updateTime;
}
