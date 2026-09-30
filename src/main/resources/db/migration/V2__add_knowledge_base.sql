-- ============================================================
-- V2__add_knowledge_base.sql
-- 知识库文档和分块表
-- ============================================================

CREATE TABLE IF NOT EXISTS `document` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `file_name` VARCHAR(255) NOT NULL,
    `file_type` VARCHAR(50) NOT NULL,
    `file_size` BIGINT NOT NULL,
    `storage_path` VARCHAR(500) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `description` TEXT,
    `chunk_count` INT DEFAULT 0,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`),
    CONSTRAINT `fk_doc_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `document_chunk` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `document_id` BIGINT NOT NULL,
    `chunk_index` INT NOT NULL,
    `content` TEXT NOT NULL,
    `token_count` INT DEFAULT 0,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_doc_id` (`document_id`),
    INDEX `idx_user_doc` (`user_id`, `document_id`),
    CONSTRAINT `fk_chunk_doc` FOREIGN KEY (`document_id`) REFERENCES `document`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- V2__add_chat_attachment.sql
CREATE TABLE chat_attachment (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 user_id BIGINT NOT NULL,
                                 message_id BIGINT,
                                 file_name VARCHAR(255) NOT NULL,
                                 file_type VARCHAR(50) NOT NULL,
                                 file_size BIGINT NOT NULL,
                                 storage_path VARCHAR(500) NOT NULL,
                                 content_text LONGTEXT,
                                 description VARCHAR(500),
                                 create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                                 INDEX idx_chat_att_msg (message_id),
                                 INDEX idx_chat_att_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;