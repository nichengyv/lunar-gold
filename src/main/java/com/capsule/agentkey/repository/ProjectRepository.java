package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByUserIdOrderByDefaultFlagDescCreateTimeAsc(Long userId);

    Optional<Project> findFirstByUserIdAndDefaultFlagTrue(Long userId);

    Optional<Project> findByIdAndUserId(Long id, Long userId);
}
