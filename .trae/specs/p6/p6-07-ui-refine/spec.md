# P6-07 交互收敛（审批卡片 / 气泡用量 / 诊断入口） - Product Requirements Document

> 状态：**已实施，前端单测与类型检查通过**（2026-11-17）。真机浏览器复核未做，e2e 已同步路径但未运行。
> 上游：P6-03 / P6-05 / P6-05b 交付后暴露的交互问题；行业对照结论见 `## Design Decisions`。

## Overview

- **Summary**：收敛三处面向用户的交互。审批卡片去掉重复信息与秒级倒计时；回答气泡显示本轮 token 并常显复制 / 重新生成；「体验诊断」从设置一级 tab 降级为应用信息内的折叠入口，验收指标默认隐藏。
- **Purpose**：审批卡片头部堆了三处同义信息（标题、风险 chip、文件行）与 1 秒一跳的倒计时；诊断页把用户数据、排障信息、内部验收指标混在一张卡片里，普通用户无法解读。
- **Target Users**：Web 工作台的日常用户；需要排障时打开高级诊断的维护者。

## Goals

1. **审批卡片只保留决策必要信息**：动作 + 目标 + 两个主操作；低频的「本会话总是允许」收进更多菜单。
2. **移除秒级倒计时**：卡片不再每秒重渲染，也不再在到期时自行隐藏；超时由后端 `TIMEOUT` 终态决定。
3. **气泡显示本轮用量**：`重新生成` 右侧显示 `token`，悬停给出输入 / 输出明细；耗时只保留在执行摘要，避免重复。
4. **操作可发现**：复制 / 重新生成常显，不再依赖悬停。
5. **诊断分层**：设置一级 tab 移除；排障信息进「应用信息 → 高级诊断」并默认收起；验收指标需显式勾选。

## Non-Goals

- 不新增接口、迁移、事件、配置项；不改审批策略、scope、批量白名单。
- 不回填历史消息的 token（消息历史接口未返回 usage）；刷新后旧回答不显示 token。
- 不搬动会话级预算提示（`liveBudget`）与执行摘要里的耗时。
- 不做主题 / 布局重做，不改 CLI 与 VS Code 插件代码。

## Design Decisions

1. **卡片中等精简，而非单行**：保留标题、目标与主操作，只删重复信息。行业对照（Claude Code、Codex CLI、Cursor、Copilot、Devin）均无风险等级标签，只有「允许 / 本会话总是允许 / 拒绝」三类选项。
2. **完全移除倒计时**：五家中仅 Claude Code 在 Code Mode 未连 IDE 时显示超时倒计时。前端自行按到期隐藏卡片会让用户误以为审批未发生；改为等待后端 `approval_resolved` 终态。
3. **诊断分三类归位**：用户数据（token）进消息层；排障信息（连接、Web Vitals、错误摘要、导出 / 清除）进折叠区；内部验收（首字时延、工具失败率、审批分布）默认隐藏。行业中没有常驻的「体验指标」设置页，只有 `/doctor`、`/debug`、Agent Debug Logs 这类按需入口。

## Background & Context

| 项 | 事实 | 位置 |
|---|---|---|
| 审批卡片 | 头部含风险 chip 与秒级倒计时，到期调用 `dismissApproval` 自行隐藏 | `frontend/src/components/ApprovalCard.vue` |
| 气泡操作栏 | 复制 / 重新生成仅在悬停时可见 | `frontend/src/styles/style.css` |
| 消息数据 | `UiMessage` 只有 `durationMs`，无本轮 token | `frontend/src/types/api.ts` |
| 用量事件 | `onUsage` 只更新 `liveBudget`，未写入消息 | `frontend/src/stores/chat.ts` |
| 诊断入口 | 设置一级 tab `diagnostics`，四张卡片同屏展示 | `frontend/src/views/SettingsView.vue`、`ExperienceDiagnosticsPanel.vue` |
| 验收指标 | P6-05b `approval.byEntry` 由诊断面板渲染 | `AgentExperienceMetricsService`、`ExperienceDiagnosticsPanel.vue` |

## Acceptance

- 审批卡片不再出现倒计时与风险 chip；`本会话总是允许` 在更多菜单内；过期后卡片不自行消失。
- 新产生的回答在 `重新生成` 右侧显示 token；无 usage 时不占位；复制 / 重新生成常显。
- 设置中不再有「体验诊断」tab；应用信息内「高级诊断」默认收起；验收指标默认隐藏，勾选后出现并记住选择。
- 前端 `npm test` 与 `vue-tsc` 通过，既有用例零回归。
