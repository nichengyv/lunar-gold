-- 邮箱验证码找回密码依赖「一个邮箱只对应一个账号」，否则按 email 改密会命中哪条记录不确定。
-- 旧数据 email 允许为 NULL，普通唯一索引在 PG 中允许多个 NULL，因此直接建唯一索引即可。
-- 统一按 LOWER(email) 建索引，与应用层「入库前转小写」的约定一致，避免大小写产生重复账号。
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_email_lower
    ON "user" (LOWER(email))
    WHERE email IS NOT NULL;
