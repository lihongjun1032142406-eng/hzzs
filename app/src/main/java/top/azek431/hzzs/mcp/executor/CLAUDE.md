# mcp/executor — MCP 工具执行器（插件化分发）

MCP 工具执行的**按 feature 分组**层。每个 [top.azek431.hzzs.mcp.executor.ToolExecutor] 负责一组内聚工具，
经 Hilt `@IntoSet` 注入到 [top.azek431.hzzs.mcp.McpActionRegistry]，由其构造时按 `toolNames` 建立工具名→执行器索引。

## 职责

- **只做执行**：参数校验 ([top.azek431.hzzs.mcp.McpActionRegistry.validateArguments]) 与权限仲裁
  ([top.azek431.hzzs.mcp.McpActionRegistry.authorize]) 归 [top.azek431.hzzs.mcp.McpActionRegistry]；执行器**不得**自行读取
  [top.azek431.hzzs.core.preferences.SettingsRepository] 做权限判断。
- **工具名唯一**：同一工具名只能属于一个执行器（构造注册表时校验）。
- **语义摘要**：审批弹窗的一句话摘要统一由 [top.azek431.hzzs.mcp.McpToolLabels.summaryZh] 生成（不记 Token/完整参数体）。

## 文件清单

| 文件 | 职责 | 工具数 |
|---|---|---|
| `ToolExecutor.kt` | 接口（`toolNames: Set<String>` + `suspend fun execute(tool, arguments)`） | — |
| `ToolExecutorBindings.kt` | Hilt `@IntoSet` 注册执行器 | — |
| `RuntimeControlExecutor.kt` | 运行时控制：start/stop/restart/cancel/preview/save/patch 等 | 8 |
| `SettingsWriteExecutor.kt` | 设置写入：scene/obstacle/threshold/theme/overlay/developer/automation/capture/gesture 等 | 15 |
| `AlgorithmExecutor.kt` | 算法包：list/active/pipeline/set_active/refresh/download/upgrade | 7 |
| `McpSelfManagementExecutor.kt` | MCP 自管（HIGH_RISK）：启停/权限级/鉴权/工具策略/访问日志/list+status | 8 |
| `ProfileExecutor.kt` | 配置 profile：save/load/list/delete | 4 |
| `DebugFrameExecutor.kt` | 调试帧：list/clear/get/capture | 4 |
| `SystemExecutor.kt` | 系统：version/metrics/check_update/inspect/permissions/open_system_settings/export 等 | 12 |
| `RikkaBridgeExecutor.kt` | RikkaDecisionV1 配对验证并终止（无 C3/执行） | 1 |

工具总数以当前 catalog 为准，并与 [top.azek431.hzzs.mcp.McpToolCatalog] 保持三向一致（catalog / executorIndex / 工具目录 JSON）。

## 数据流

```text
tools/call → McpActionRegistry.call
    → McpToolCatalog.tool(tool)         // 描述符
    → validateArguments(descriptor, args)
    → authorize(descriptor, args, mcp, session)   // 四级 + toolPolicies
    → executorIndex[name].execute(tool, args)     // 分发到具体执行器
         └─ WRITE/HIGH_RISK → McpUiBridge.requestApproval
              └─ 审批标签 = McpToolLabels.approvalLabel(tool)
                   审批摘要 = McpToolLabels.summaryZh(tool, args)
```

## 不变量

- 新增工具：同步 `McpToolCatalog`、`McpToolLabels`、新增/归入某执行器、更新本表与 `mcp/CLAUDE.md`。
- 工具名只属于一个执行器；`toolNames` 非空。
- HIGH_RISK 工具（开自动操作/开开发者/下载算法/改 MCP 权限策略/`set_mcp_*`）保持标记；执行器不绕开权限。
- 访问日志不记 Bearer/`authToken`/请求参数体。
