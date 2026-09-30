package com.capsule.agentkey.service;

import com.capsule.agentkey.entity.User;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@Slf4j
public class UserService {
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$");

    public UserService(UserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    public User register(String username, String rawPassword, String email) {
        if (!PASSWORD_PATTERN.matcher(rawPassword).matches()) {
            throw new BusinessException("密码至少8位，需包含大小写字母、数字和特殊字符");
        }
        if (userRepo.findByUsername(username).isPresent()) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setEmail(email);
        user.setCreateTime(LocalDateTime.now());
        User saved = userRepo.save(user);
        log.info("用户注册成功: {}", username);
        return saved;
    }

    public Optional<User> findByUsername(String username) {
        return userRepo.findByUsername(username);
    }

    public Optional<User> findById(Long id) {
        return userRepo.findById(id);
    }

    public boolean checkPassword(String raw, String hash) {
        return passwordEncoder.matches(raw, hash);
    }

    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException("原密码错误");
        }
        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new BusinessException("新密码至少8位，需包含大小写字母、数字和特殊字符");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepo.save(user);
        log.info("用户修改密码成功: {}", user.getUsername());
    }
}