-- 工作区文件系统：每个项目可绑定一个磁盘文件夹作为工作区，
-- Agent 能在该文件夹内读写文件、搜索代码。
-- workspace_path 为可空 TEXT，老项目/未绑定的项目不影响现有功能。
ALTER TABLE project ADD COLUMN IF NOT EXISTS workspace_path TEXT;

COMMENT ON COLUMN project.workspace_path IS '工作区磁盘目录的绝对路径；为空表示未绑定工作区';
