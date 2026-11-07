package com.capsule.agentkey.service;

/**
 * 单次对话的运行参数：模型 + 推理档位 + 工作权限。
 *
 * 推理档位不只是"想得久一点"：它同时决定工具循环上限和历史消息预算——
 * 快速档省钱但只能处理一两步简单任务，深度档给多步任务更多轮次和更长上下文。
 *
 * 工作权限决定审批方式：手动审批每次写/执行都确认，自动审批写文件自动通过，
 * 完全权限全部自动通过。只读档仅兼容旧会话。
 */
public record AgentPolicy(String model, Reasoning reasoning, Permission permission) {

    /**
     * 推理档位：统一映射工具轮次与历史预算，前端只传档位名。
     *
     * 轮次上限对齐主流 Agent（一次任务通常 20~40 步），旧值 6/12/18 明显偏小：
     * 一个「读目录 → 读配置 → 写多个文件 → 跑测试」的任务在标准档下轻易撞顶。
     */
    public enum Reasoning {
        FAST("快速", 10, 6, 2, 3000),
        STANDARD("标准", 30, 12, 4, 6000),
        DEEP("深度", 60, 18, 8, 12000);

        private final String label;
        private final int maxToolRounds;
        private final int historyCount;
        private final int ragHistoryCount;
        private final int historyCharBudget;

        Reasoning(String label, int maxToolRounds, int historyCount, int ragHistoryCount, int historyCharBudget) {
            this.label = label;
            this.maxToolRounds = maxToolRounds;
            this.historyCount = historyCount;
            this.ragHistoryCount = ragHistoryCount;
            this.historyCharBudget = historyCharBudget;
        }

        public String label() {
            return label;
        }

        public int maxToolRounds() {
            return maxToolRounds;
        }

        /** 历史消息条数上限；命中知识库时收窄，避免旧结论干扰本轮检索结果 */
        public int historyCount(boolean ragHit) {
            return ragHit ? ragHistoryCount : historyCount;
        }

        public int historyCharBudget() {
            return historyCharBudget;
        }

        public static Reasoning from(String raw) {
            if (raw != null) {
                for (Reasoning r : values()) {
                    if (r.name().equalsIgnoreCase(raw.trim())) {
                        return r;
                    }
                }
            }
            return STANDARD;
        }
    }

    /**
     * 工作权限：按「审批方式」分档，而不是按能力分档。
     *
     * 三档与主流 Agent（Cursor / Claude Code）对齐：
     * 手动审批 = 写文件与执行命令都要确认；自动审批 = 写文件自动通过、命令仍需确认；
     * 完全权限 = 全部自动通过。READ_ONLY 是旧版本遗留的只读档，仍兼容但不再出现在新 UI。
     */
    public enum Permission {
        MANUAL("手动审批", true, true),
        AUTO("自动审批", true, true),
        FULL("完全权限", true, true),
        /** 旧版「只读」档：仅用于兼容已持久化的会话，新界面不再提供 */
        READ_ONLY("只读", false, false);

        private final String label;
        private final boolean write;
        private final boolean execute;

        Permission(String label, boolean write, boolean execute) {
            this.label = label;
            this.write = write;
            this.execute = execute;
        }

        public String label() {
            return label;
        }

        public boolean canWrite() {
            return write;
        }

        public boolean canExecute() {
            return execute;
        }

        /**
         * 执行前是否需要用户确认。
         *
         * @param risk    工具风险等级（WRITE / EXECUTE），null 表示该工具本身不需要审批
         * @param command 命令原文（仅 EXECUTE 风险时用于判断是否属于破坏性命令）
         */
        public boolean requiresApproval(String risk, String command) {
            if (risk == null) return false;
            return switch (this) {
                case MANUAL -> true;
                case AUTO -> "DESTRUCTIVE".equals(risk)
                        || ("EXECUTE".equals(risk) && com.capsule.agentkey.util.CommandRisk.isDestructive(command));
                case FULL, READ_ONLY -> false;
            };
        }

        /** 拼进 system prompt 的权限说明，让模型知道哪些动作会先被拦下 */
        public String promptRule() {
            return switch (this) {
                case MANUAL -> "手动审批：可以新建、修改文件和执行终端命令，但每次写文件或执行命令前都会请用户确认。"
                        + "需要连续多步时，一次只提交一步，等待确认后再继续。";
                case AUTO -> "自动审批：新建、修改、删除文件和普通命令（构建/测试/脚本/查看）都会自动通过；"
                        + "只有递归删除目录、格式化、强推、硬重置这类破坏性操作才会请用户确认。";
                case FULL -> "完全权限：写文件和执行终端命令都会自动通过。"
                        + "请避免破坏性操作（删除目录、强推分支、批量重写文件），必要时先说明风险。";
                case READ_ONLY -> "只读：只能查看和检索，不能新建或修改文件，也不能执行终端命令。"
                        + "用户要求改动时，说明需要切换工作权限再继续。";
            };
        }

        public static Permission from(String raw) {
            if (raw != null) {
                String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
                // 旧版本持久化的档位：映射到行为最接近的新档（旧版写/执行都需要确认 → 手动审批）
                if ("EXECUTE".equals(v) || "WRITE".equals(v)) {
                    return MANUAL;
                }
                for (Permission p : values()) {
                    if (p.name().equals(v)) {
                        return p;
                    }
                }
            }
            return MANUAL;
        }
    }

    public static AgentPolicy defaults(String model) {
        return new AgentPolicy(model, Reasoning.STANDARD, Permission.MANUAL);
    }
}
