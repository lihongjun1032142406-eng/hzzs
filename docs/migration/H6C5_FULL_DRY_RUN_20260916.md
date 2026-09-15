# H6-C5 Full Dry Run — Minimal Wiring

## 范围

H6-C5 是 **Full Dry Run**，用于验证真实设备截图形成的 same-evidence `JoinedState`、Rikka 决策、UID/source provenance、H6-A 安全门和（存在合法坐标配置时）H6-B 解析是否足以支持未来执行。

Rikka 是唯一高层策略大脑。HZZS/JinChanAI 不增加英雄价值、阵容、刷新/升级、影子策略或本地 fallback 决策脑。

## 不可越过的边界

1. MCP 提交先由 C4D 对 exact paired immutable evidence 做 provenance 与 UID 唯一性验证。
2. C5 只接收该 typed validation result 及其原配 `JoinedState`；不读取 current/latest ledger 或后续 StateFlow 修复位置。
3. Move/Sell 的 `JinChanActionSourceEvidence` 的 session、sequence、revision 和 source location 均来自同一 paired snapshot。
4. C5 复用冻结的 `JinChanActionSafetyGate.evaluate()`；通过后仅在合法 profile 存在时复用 `JinChanGestureResolver.resolve()`。
5. C5 在 coordinator/dispatch/平台 transport 前结构性 **HARD STOP**，不构造 execution envelope，也不调用运行时 action integration。

production runtime 当前没有合法 `JinChanCoordinateProfile`，因此返回 `RESOLVER_PROFILE_UNAVAILABLE`，同时保留 `GATE_WOULD_PASS` 诊断；绝不构造 synthetic profile、硬编码坐标或把 ROI 当 hitbox。真实坐标校准推迟到 H6-C6。

## 诊断与安全结论

C5 复用 `AppLog` ring 和既有 diagnostics export，并维护小型进程内计数快照。日志包含 observation/decision/validation/gate/profile/resolver/hard-stop 事件及 provenance、UID reject、stale reject 计数所需事件，不包含 Token 或参数体。

- `AppConfig.ACTION_ENABLED=false` 保持不变。
- `REAL_ACTION_REACHABLE=false`。
- `ACTION_EXECUTED=0`。
- Overlay 默认状态不变。
- C5 does not execute actions；无障碍、Shizuku、Root 及任何 gesture transport 均不可达。
