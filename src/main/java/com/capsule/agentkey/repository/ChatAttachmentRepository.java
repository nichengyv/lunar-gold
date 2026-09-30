package com.capsule.agentkey.repository;

import com.capsule.agentkey.entity.ChatAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {
    List<ChatAttachment> findByMessageId(Long messageId);
}