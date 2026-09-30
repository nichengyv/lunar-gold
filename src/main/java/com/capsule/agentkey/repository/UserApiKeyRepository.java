package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.UserApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
//父接口已经定义了完整的增删改查通用方法
public interface UserApiKeyRepository extends JpaRepository<UserApiKey, Long> {
    Optional<UserApiKey> findByUserIdAndProvider(Long userId, String provider);
    List<UserApiKey> findByUserId(Long userId);
}
