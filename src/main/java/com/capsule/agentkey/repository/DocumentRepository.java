package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByUserIdAndProjectIdOrderByCreateTimeDesc(Long userId, Long projectId);
    long countByUserIdAndProjectIdAndStatus(Long userId, Long projectId, Document.Status status);
}