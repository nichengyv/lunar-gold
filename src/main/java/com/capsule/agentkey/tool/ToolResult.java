//统一工具返回格式
package com.capsule.agentkey.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder//开启建造者模式
@NoArgsConstructor//无参构造
@AllArgsConstructor//全参构造
public class ToolResult {
    private boolean success;//是否执行成功
    private String content;//成功时，工具返回的业务结果文本
    private String error;//失败时，存放错误描述
    private long costMs;//工具执行耗时

    public static ToolResult ok(String content) {
        return ToolResult.builder().success(true).content(content).build();
    }

    public static ToolResult ok(String content, long costMs) {
        return ToolResult.builder().success(true).content(content).costMs(costMs).build();
    }

    public static ToolResult fail(String error) {
        return ToolResult.builder().success(false).error(error).build();
    }
}
