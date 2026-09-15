# mcp — 本地 MCP 服务与权限仲裁

回环 MCP（Streamable HTTP）服务 + 四级权限仲裁 + 工具策略。**默认只监听 127.0.0.1**；用户显式允许时绑定 0.0.0.0（局域网）。默认免 Bearer；开启鉴权时用持久化 `authToken`（不在每次启动轮换）。

## 职责

- `McpForegroundService`：loopback/可选 LAN 监听、generation 启停、并发连接上限、Bearer/Origin 门禁。
- `McpProtocol`：initialize / initialized / 通知 202 / 错误码分类；失效会话对 tools/call 可降级。
- `McpToolCatalog`：描述驱动工具与严格 JSON Schema；过滤 DISABLED。
- `McpActionRegistry`：四级权限 + 工具策略仲裁门面；构造时聚合并索引 [top.azek431.hzzs.mcp.executor.ToolExecutor]。
- `executor/`：按 feature 分组的工具执行器（见 [`executor/CLAUDE.md`](executor/CLAUDE.md)）。
- `McpAccessLog`：进程内访问日志 ring（method/工具/状态/耗时/远端；**无** Token/参数）。
- `McpUiBridge`：审批对话框与导航；停止时拒绝挂起审批。
- H6-C4D 复用资源 `app://rikka/observation/v1/latest` 与工具 `submit_rikka_decision_v1`；只读配对观测/验证决策，无第二服务、C3 或执行。
- `McpSessionManager` / `McpEventBus` / `McpLanAddresses` / `McpProfileStore` / `McpSettingsPatch` 等支撑组件。

## 入口

1. `McpService.kt` — 前台服务（最大）。改连接/鉴权/Origin 门禁必读。
2. `McpProtocol.kt` — 协议层。
3. `McpToolCatalog.kt` — 工具/资源目录。
4. `McpActionRegistry.kt` — 权限仲裁。
5. `McpAccessLog.kt` / `McpUiBridge.kt` / `McpSessionManager.kt`。
6. `MainActivity.kt`（syncMcpService + 审批 UI）、`feature/settings/screens/McpSettingsScreen.kt`（设置页）。

## 数据流

```text
客户端 POST /mcp
  → McpForegroundService.dispatchOne
      → Origin 门禁 → Bearer 门禁 → McpProtocol.dispatch
          ├─ initialize → McpSessionManager.createSession
          ├─ tools/list → McpToolCatalog.toolsJson(effectiveTools)
          └─ tools/call → McpActionRegistry.call（四级权限 + toolPolicies）
                └─ WRITE/HIGH_RISK → McpUiBridge.requestApproval → MainActivity 审批对话框

设置变更：
  SettingsRepository.savedConfig → MainActivity.syncMcpService → 启停服务（指纹变化时重启）
  SettingsRepository.config（preview）→ toolPolicyConfig → 工具列表即时生效（无需重启）
```

### 绑定身份 vs 策略

- **跟 `savedConfig`**：端口 / 鉴权 / LAN / 启停 / `bindLocalhostOnly` / `requireAuth` / `authToken` → 改这些会**重启服务并清空会话**。
- **跟 `current()`（可含设置草稿）**：`permissionLevel` / `toolPolicies` / 工具列表 / 授权 → 改这些**不重启**服务。

## 不变量 / 安全 / 线程

- 绑定：默认 IPv4 `127.0.0.1`（**不用** `InetAddress.getLoopbackAddress()` 的 `::1`，避免与客户端 127.0.0.1 不通）；LAN 模式 `0.0.0.0`。
- Origin：空 / `null` / loopback 允许；非 loopback 浏览器 Origin **一律拒绝**（即使局域网模式）。
- Bearer：`constantTimeBearerMatches`；默认关；开启时用配置中持久化 `authToken`，不在每次启动轮换，仅设置页「轮换 Token」时更新。
- GET `/mcp` 返回 405（**不提供 SSE** 推送流）；GET `/health` 只读健康探测。
- 权限：READ_ONLY / ASK_EVERY_TIME / TRUSTED_SESSION / FULL_ACCESS；`toolPolicies`（DEFAULT / ALWAYS_ASK / ALLOW_WHEN_TRUSTED / DISABLED）可按工具覆盖；禁用工具**不进** `tools/list`。
- HIGH_RISK 工具（开自动操作/开开发者/下载算法/改 MCP 权限策略/set_mcp_*）在 TRUSTED_SESSION 下拒绝；完整访问也不能绕过 Android 系统权限对话框。
- 访问日志默认开（`accessLogEnabled`）：进程内 ring，记 method/工具/状态/耗时/远端摘要；**永不**记 Bearer/`authToken`/请求参数体。
- 会话内存化 + generation；服务重启清空；失效 Session 对 tools/call 可降级为无会话（避免 -32003 要求重连）。
- 线程：accept/读写在 IO 协程；通知与 Service 生命周期由主线程协调；stopServer 推进 generation 使陈旧 accept 循环 fail-closed。
- 配置 schema **10**（含 `overlay.persistBoxes` 默认开、自动复活等；访问日志自 schema 9 起）。
- API 34+ 前台服务须带 `SPECIAL_USE` type。

## 改这个包前必读

- 改 `McpForegroundService`：同步 `MainActivity.syncMcpService`（savedConfig 指纹启停）与 `McpSettingsScreen`（设置页状态）。
- 改工具清单：同步 `McpToolCatalog.tools`、`McpToolLabels`、`McpToolPolicySupport`、归属执行器；禁用工具须不进 `tools/list`。
- 改执行器：同步 `executor/ToolExecutorBindings`、`McpActionRegistry.executorIndex` 与本包 `executor/CLAUDE.md`；工具名全局唯一。
- 改权限仲裁：同步 `McpActionRegistry`（四级 + toolPolicies）、`McpSettingsPatch`（preview/save 收敛）。
- 改协议：同步 `McpProtocol` 错误码、`McpSessionManager` 会话生命周期、`McpErrorCodes`。
- 改访问日志：**不得**记 Bearer/`authToken`/请求参数体；设置页提供 `get_mcp_access_log` / `clear_mcp_access_log`。
- 改 `preview_settings` / `save_settings`：相对已 saved baseline 做权限型字段收敛（不得静默关 `requireAuth`、不得改写 `authToken`）。
- 外部摄入：`save_settings` / 配置导入仍经 `hardenedForExternalIngest`，不得静默开自动操作或局域网。
- 改 `McpUiBridge`：审批对话框在 `MainActivity`；服务停止时拒绝挂起审批。
- 设置页「可选主机」始终含 `127.0.0.1`；LAN IP 仅在允许局域网后追加。

## 测试

- 门禁：`python tools/quality/check_project.py`（含 MCP 安全字面量、LAN 门控）。
- 真机 MCP 联调（推荐）：`adb forward tcp:18765 tcp:8765` → `http://127.0.0.1:18765/mcp`。顺序：initialize → tools/list → inspect/get_status/get_runtime_snapshot → patch_settings/set_scene → start_analysis/stop_analysis → cancel_actions；调试帧 `capture_debug_frame` / `get_debug_frame`（HIGH_RISK + `allowDebugFrames`）；算法 `upgrade_algorithms(dryRun)`（勿连点）。
- Wi‑Fi 直连失败先怀疑 AP 隔离，改 ADB。
