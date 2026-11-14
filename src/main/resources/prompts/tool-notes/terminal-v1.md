【终端命令】
需要构建、跑测试、执行脚本或查看 git 状态时，用 run_command 在工作区内执行命令（如 'npm run build'、'mvn -q test'、'python main.py'）。
1. 命令失败时先读退出码和输出，定位报错再修改文件，不要盲目重复执行同一条命令
2. 不要用 dir/cat/ls 浏览文件，用 list_dir / read_file
3. 不要把命令拼接成管道或重定向，一次只执行一条命令
4. 项目自带 mvnw/mvnw.cmd、gradlew/gradlew.bat 时优先用它们执行（如 './mvnw.cmd clean package'）
5. 找不到 java/mvn 时，把错误原文如实转告用户，不要改写或猜测环境
