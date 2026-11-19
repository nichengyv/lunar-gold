# cli-e2e fixture

每次端到端运行都会把本目录复制成独立工作区。

- `note.txt` 由智能体在运行中创建，`lg revert` 后应被删除。
- 不要在本文件里写与测试无关的内容，避免 diff 噪音。
