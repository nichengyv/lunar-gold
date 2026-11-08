package com.capsule.agentkey.service.desktop;

import com.capsule.agentkey.entity.User;
import com.capsule.agentkey.repository.UserRepository;
import com.capsule.agentkey.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalUserServiceTest {

    private UserRepository userRepo;
    private LocalUserService service;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        service = new LocalUserService(userRepo, new UserService(userRepo, new BCryptPasswordEncoder()), "local");
    }

    @Test
    void 首次启动创建本地用户并缓存id() {
        when(userRepo.findByUsername("local")).thenReturn(Optional.empty());
        when(userRepo.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(9L);
            return user;
        });

        assertEquals(9L, service.localUserId());
        // 第二次取用走内存缓存，不重复查库/建号
        assertEquals(9L, service.localUserId());
        verify(userRepo, times(1)).save(any(User.class));
    }

    @Test
    void 已有本地用户时不再建号() {
        User existing = new User();
        existing.setId(3L);
        existing.setUsername("local");
        when(userRepo.findByUsername("local")).thenReturn(Optional.of(existing));

        assertEquals(3L, service.localUserId());
        verify(userRepo, never()).save(any(User.class));
    }
}
