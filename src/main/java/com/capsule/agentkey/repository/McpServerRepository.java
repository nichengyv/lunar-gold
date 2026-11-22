package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.McpServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    List<McpServer> findByUserIdOrderByIdAsc(Long userId);

    Optional<McpServer> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndName(Long userId, String name);

    boolean existsByUserIdAndNameAndIdNot(Long userId, String name, Long id);

    List<McpServer> findByEnabledTrueOrderByIdAsc();
}
