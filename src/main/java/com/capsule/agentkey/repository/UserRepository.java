package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    /** 邮箱找回密码 / 注册查重：入库前已统一转小写，这里按精确匹配即可 */
    Optional<User> findByEmail(String email);
}
