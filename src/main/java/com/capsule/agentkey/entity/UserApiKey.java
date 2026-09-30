package com.capsule.agentkey.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_api_key", indexes = {
        @Index(name = "idx_user_provider", columnList = "userId,provider")//联合索引（要同时查这几个元素）
})
@EntityListeners(AuditingEntityListener.class)//生命周期监听器，开启自动时间填充
public class UserApiKey {
    @Id//主键
    @GeneratedValue(strategy = GenerationType.IDENTITY)//配置主键自动生成，strategy = GenerationType.IDENTITY指定生成策略为自增
    private Long id;

    @Column(nullable = false)//配置列名，nullable = false指定列名不为null
    private Long userId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedKey;

    @Column(length = 50)
    private String keyAlias;

    @CreatedDate
    private LocalDateTime createTime;

    @LastModifiedDate
    private LocalDateTime updateTime;
}
