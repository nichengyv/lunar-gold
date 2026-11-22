-- M1：用户自助 MCP Server（仅桌面端）。
-- 命令与参数明文保存；环境变量可能含令牌，由应用层 AES-GCM 加密后写入 env_encrypted。
CREATE TABLE mcp_server (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    command       VARCHAR(512) NOT NULL,
    args_json     TEXT         NOT NULL DEFAULT '[]',
    env_encrypted TEXT,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mcp_server_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_mcp_server_user ON mcp_server (user_id);
