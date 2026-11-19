# Lunar Gold VS Code 扩展

在本机 VS Code 中连接 Lunar Gold 后端，发起单个智能体任务，查看流式正文与工具调用，处理审批，并审查或撤回文件变更。

## 安装与卸载

### 安装 vsix

1. 先启动本机 Lunar Gold 后端。开发环境可在仓库根目录运行：

   ```powershell
   ./scripts/dev-desktop.ps1
   ```

2. 在 VS Code 扩展视图的「…」菜单选择「从 VSIX 安装…」，选择生成的 `lunargold-vscode-0.1.0.vsix`；或运行：

   ```powershell
   code --install-extension ./lunargold-vscode-0.1.0.vsix
   ```

3. 重新加载 VS Code 窗口，点击活动栏的 **Lunar Gold** 图标。

从源码打包需要 Node.js 24 与 npm：

```powershell
npm install
npm run check
npm test
npm run package
```

### 卸载

```powershell
code --uninstall-extension lunargold.lunargold-vscode
```

卸载前建议先执行「Lunar Gold: 断开并撤销客户端」，撤销本机客户端令牌。卸载不会撤销后端已经签发的令牌，也不会删除项目、会话或文件变更。

## 连接

1. 执行命令 **Lunar Gold: 连接本机后端**。
2. 扩展先检查后端健康状态与 API 契约版本，再校验 SecretStorage 中已有的客户端令牌。
3. 没有可用令牌时，可使用桌面端本地用户令牌换发名为 `vscode-<主机名>` 的客户端，或选择手动粘贴 `lgc_` 客户端令牌。
4. 连接成功后即可发起任务。客户端令牌只保存在 VS Code SecretStorage，不写入 `settings.json` 或扩展日志。

默认后端地址为 `http://127.0.0.1:8080`，仅允许 HTTP(S) 回环地址。使用 `scripts/dev-desktop.ps1` 启动的开发后端时，将 `lunargold.localTokenPath` 设置为仓库下的 `build/dev-desktop-data/local-token`；安装版桌面端默认读取 `~/.lunargold/desktop/local-token`。

执行 **Lunar Gold: 断开并撤销客户端** 会撤销当前客户端、清除本地密钥和任务恢复状态。

## 使用流程

1. 执行 **Lunar Gold: 发起任务**。
2. 选择项目，再选择既有会话或「新会话」。
3. 如果项目工作区与当前 VS Code 文件夹不一致，可打开该文件夹、明确继续，或取消。
4. 输入任务描述。任务面板会显示正文、工具调用、文件变更、审批和终态；状态栏同步显示运行状态。
5. 在「变更」视图中按 Run 查看文件。点击文件可打开原生 diff；无法可靠重建修改前内容时，扩展改为展示 patch 预览与当前文件，不伪造对比。
6. 对危险工具可批准、拒绝、选择「本会话记住」，或批量批准当前待审批项。没有自动批准。
7. 可按当前 Run 或整个会话撤回变更；执行前会显示目标、文件数和影响路径，冲突原因由后端原样返回。
8. 执行 **Lunar Gold: 停止当前生成** 可取消当前任务。网络中断或窗口重载不会终止后端任务，Run 会在后台继续；扩展按 `runId + sinceSeq` 恢复任务身份、后续事件、终态与待审批卡片，恢复后仍可继续处理审批。

## 命令

| 命令 | 作用 |
| --- | --- |
| `Lunar Gold: 连接本机后端` | 检查后端并连接、换发或录入客户端令牌 |
| `Lunar Gold: 发起任务` | 选择项目和会话，发起一个任务 |
| `Lunar Gold: 打开 Lunar Gold 面板` | 打开任务与审批面板 |
| `Lunar Gold: 停止当前生成` | 确认后停止当前任务 |
| `Lunar Gold: 刷新变更列表` | 重新获取当前会话的变更 |
| `Lunar Gold: 撤回本次 Run 的变更` | 确认后撤回当前 Run 的待撤回变更 |
| `Lunar Gold: 撤回本会话全部变更` | 确认后撤回当前会话的待撤回变更 |
| `Lunar Gold: 断开并撤销客户端` | 撤销客户端并清除本地连接与恢复状态 |

## 设置

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `lunargold.baseUrl` | `http://127.0.0.1:8080` | 本机后端地址，仅支持回环地址 |
| `lunargold.localTokenPath` | 空 | 可选，覆盖桌面端本地用户令牌路径；默认读取 `~/.lunargold/desktop/local-token` |

## 已知限制

- 同一 VS Code 窗口同时只能有一个活跃任务，不支持多任务并发。
- 首版只支持本地窗口，不支持 SSH、WSL 或 Dev Container 远程窗口。
- 首版不接入 Verify；任务完成后面板显示「未验证」。
- 不提供自动批准、LSP、代码补全、内联聊天或附件能力。
- diff 基线来自后端精简差异；截断、二进制、过大、不可读或磁盘再次修改时会降级为 patch 预览。
- 仅提供本机 vsix 手动安装，不做签名、市场发布或自动更新。
