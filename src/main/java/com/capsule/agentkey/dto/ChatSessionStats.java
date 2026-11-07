package com.capsule.agentkey.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 会话追踪面板的聚合统计。
 * 全部数据来自 ChatMessage.trace（TEXT 列存的 JSON），不新建表——解析时按需 parse。
 */
@Data
public class ChatSessionStats {

    /** 会话总览 */
    private Overview overview;

    /** 第五十六批：耗时口径拆分（setup → tools → answer） */
    private TimeBreakdown timeBreakdown;

    /** 工具调用统计：按工具名分组 */
    private List<ToolStat> tools;

    /** 文件变更汇总：按路径分组 */
    private List<FileChangeStat> files;

    /** 工具调用完整时间线（带时间戳） */
    private List<ToolTimelineEntry> timeline;

    @Data
    public static class Overview {
        /** 总消息数（assistant 消息数 = ReAct 轮次数） */
        private int totalRounds;
        /** 总工具调用次数 */
        private int totalToolCalls;
        /** 工具成功率（成功 / 总调用） */
        private double toolSuccessRate;
        /** 唯一工具种类数 */
        private int uniqueTools;
        /** 唯一变更文件数 */
        private int uniqueFilesChanged;
        /** 会话总耗时（ms，最后一条 assistant 消息的 trace.durationMs 累加） */
        private long totalDurationMs;
        /** 推理档位 */
        private String reasoningPolicy;
        /** 工作权限 */
        private String permission;
        /** 会话开始时间（ISO） */
        private String startedAt;
        /** 会话最近活跃时间（ISO） */
        private String lastActiveAt;
    }

    /**
     * 第五十六批：耗时口径拆分
     * setup: 从开始到第一次工具调用或第一个回答字符（含 LLM 内部 thinking、RAG 检索、截图转写等）
     * tools: 所有工具调用 costMs 之和
     * answer: 第一个回答字符到完成（含最后一轮 LLM 流式输出）
     */
    @Data
    public static class TimeBreakdown {
        /** setup 阶段（毫秒） */
        private long setupMs;
        /** tools 阶段（毫秒） */
        private long toolsMs;
        /** answer 阶段（毫秒） */
        private long answerMs;
        /** 有 segments 的轮次数（老会话无 segments 的轮次不计入） */
        private int roundsWithSegments;
    }

    @Data
    public static class ToolStat {
        /** 工具名（mcp_ 前缀的自动标记 source=mcp） */
        private String name;
        /** builtin / mcp */
        private String source;
        /** 调用次数 */
        private int calls;
        /** 成功次数 */
        private int success;
        /** 失败次数 */
        private int failure;
        /** 成功率 0.0~1.0 */
        private double successRate;
        /** 平均耗时 ms */
        private double avgCostMs;
        /** P95 耗时 ms */
        private long p95CostMs;
        /** 最大耗时 ms */
        private long maxCostMs;
    }

    @Data
    public static class FileChangeStat {
        /** 相对项目根的文件路径 */
        private String path;
        /** create / modify / delete / command */
        private String action;
        /** 变更来源工具名 */
        private String source;
        /** 累计新增行数 */
        private int additions;
        /** 累计删除行数 */
        private int deletions;
    }

    @Data
    public static class ToolTimelineEntry {
        /** 时间戳（ms，会话内相对开始的偏移） */
        private long offsetMs;
        /** 工具名 */
        private String name;
        /** builtin / mcp */
        private String source;
        /** success / error */
        private String status;
        /** 耗时 ms */
        private long costMs;
        /** 调用参数（截断） */
        private String argsSummary;
    }
}
