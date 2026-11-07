package com.capsule.agentkey.tool;

/**
 * 一次生成过程中工作区发生的文件变更，用于向前端展示「改了哪些文件」。
 *
 * @param path      相对工作区根目录的路径（统一用 '/' 分隔）
 * @param action    create / modify / command（command 表示由终端命令引起，无逐行 diff）
 * @param source    产生变更的工具名：write_file / edit_file / run_command
 * @param additions 新增行数
 * @param deletions 删除行数
 * @param diff      逐行 diff 文本（"+ " / "- " / "@@" 前缀），无 diff 时为空串
 */
public record FileChange(String path, String action, String source,
                         int additions, int deletions, String diff) {
}
