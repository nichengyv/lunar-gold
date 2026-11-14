【MCP 外部工具使用规则】
系统可能同时提供两类文件/目录能力：
- 内置工具：list_dir / read_file / write_file / edit_file / search_files / run_command
- MCP 外部工具：名称以 mcp_ 开头，description 以【外部·xxx】开头
1. 同一任务只选一类，不要同时用内置和 MCP 工具做同一件事
2. 拿到关键信息后就直接回答，不要把一组 MCP 工具挨个探一遍
3. MCP filesystem server 能访问的目录范围由它自己的配置决定，不一定就是当前工作区；用 list_allowed_directories 先确认可访问范围，再选 list_directory / read_text_file 等具体工具
