# 设置页“应用信息”改造计划

## Context

当前“关于”卡片藏在“对话默认”页底部，版本还在前端硬编码为 `1.0.0`，导致用户无法判断自己安装的是桌面 v0.1.1 还是 v0.1.2。目标是提供独立、易发现的“应用信息”入口，并准确区分桌面应用版本与后端版本。

## 实施方案

### 1. 桌面壳传递真实应用版本

修改 `desktop/main.js`：在现有 `loadMainWindow()` 构造 URL 的位置，保留 `desktopToken`，通过 `URLSearchParams` 增加 `appVersion=app.getVersion()`。首启、侧车重启和渲染恢复均继续复用该入口，不新增 IPC 或 preload。

### 2. 在前端保存桌面版本

修改 `frontend/src/stores/meta.ts`：从当前 URL 查询参数读取非敏感的 `appVersion`，保存到 store，并用 `sessionStorage` 处理 SPA 路由跳转和刷新后的回退。不要持久化 `desktopToken`。

### 3. 增加独立“应用信息”页

修改 `frontend/src/views/SettingsView.vue`：

- 在 `BASE_TABS` 末尾增加“应用信息”tab，桌面端和 Web 端均显示。
- 将“对话默认”底部的“关于”卡片移动到该 tab。
- 显示应用名称、真实应用版本、后端版本、后端状态、运行时长和部署模式。
- 桌面应用版本取 Electron 传入的 `appVersion`；Web 应用版本与后端版本取 `meta.info.version`，不混用下载页的“最新版本”。
- 左侧底部 `Lunar Gold {{ version }}` 同步使用真实应用版本，删除硬编码 `1.0.0`。
- health 尚未返回或请求失败时显示“未获取”或“—”，不误报为确定离线。
- 复用现有卡片、`about-row`、状态点和 `formatUptime()` 样式，不引入新组件或后端接口。

本次不加入检查更新、诊断导出按钮或许可证外链，避免扩大范围。

## 验证

1. 执行桌面主进程 `node --check` 和前端 `npm run build`，确认语法、Vue/TypeScript 与 Vite 构建通过。
2. 桌面端检查首启、刷新、侧车重启后，“应用信息”和左下角均显示当前 Electron 版本，后端版本与 `/api/meta` 一致，部署模式为“桌面端”。
3. Web 端不带 `appVersion` 打开，应用版本与 `/api/meta` 一致，不显示桌面版本。
4. 模拟 health 请求失败，页面仍可用且状态显示“未获取”，不显示伪造运行时长。
