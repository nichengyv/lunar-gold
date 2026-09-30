package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByUserIdOrderByCreateTimeDesc(Long userId);
    long countByUserIdAndStatus(Long userId, Document.Status status);
}