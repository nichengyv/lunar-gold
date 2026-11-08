package com.capsule.agentkey.service.desktop;

import com.capsule.agentkey.entity.User;
import com.capsule.agentkey.repository.UserRepository;
import com.capsule.agentkey.service.UserService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 桌面端单用户初始化（P2-05）：首次启动自动建号，之后复用同一条记录。
 *
 * 桌面端没有注册流程，也没有可记忆的密码：账号的密码哈希用一次性随机串生成，
 * 仅用于满足表约束，永远不会被用于登录（本地身份由 LocalTokenFilter 承担）。
 * 云端多用户注册仍由 UserService / AuthController 负责，本类只在 desktop profile 下装配。
 */
@Slf4j
@Component
@Profile("desktop")
public class LocalUserService {

    private final UserRepository userRepo;
    private final UserService userService;
    private final String username;

    private volatile Long userId;

    public LocalUserService(UserRepository userRepo,
                            UserService userService,
                            @Value("${app.desktop.local-username:local}") String username) {
        this.userRepo = userRepo;
        this.userService = userService;
        this.username = username;
    }

    /** 启动即确保本地用户存在，避免首个请求时才建号造成并发写入 */
    @PostConstruct
    public void initialize() {
        log.info("桌面端单用户已就绪 [username={}, userId={}]", username, localUserId());
    }

    /** 当前本地用户 id：不存在则创建，创建后缓存在内存中 */
    public synchronized Long localUserId() {
        if (userId != null) {
            return userId;
        }
        User user = userRepo.findByUsername(username).orElseGet(this::createLocalUser);
        userId = user.getId();
        return userId;
    }

    public String username() {
        return username;
    }

    private User createLocalUser() {
        // 随机密码满足 UserService 的强度规则，但不会被任何入口使用
        String randomPassword = "Local@" + UUID.randomUUID() + "9a";
        User created = userService.register(username, randomPassword, username + "@desktop.local");
        log.info("首次启动：已创建桌面端本地用户 [username={}]", username);
        return created;
    }
}
