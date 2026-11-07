-- 消息全文搜索：侧栏可按聊天正文搜索，接口用 content LIKE '%关键字%' 匹配。
-- 普通 btree 索引对前后通配的 LIKE 无效，这里用 pg_trgm 的 GIN 索引让子串匹配也能走索引。
-- 若当前数据库账号没有安装扩展的权限，可只跳过第一句；查询仍可用，只是退化为全表扫描。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_chat_message_content_trgm
    ON chat_message USING gin (content gin_trgm_ops);
