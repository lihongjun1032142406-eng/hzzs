# H6-C3 Runtime Integration / Shadow-to-Armed Switch PRECHECK — 2026-09-15

## 0. 结论与审计边界

- 基线：`a7001d7382932387222c5901ad1121ac6d594c03`，工作分支 `jinchan-h6c-execution-wiring`。
- 本轮仅审计并记录结论；H6-A、H6-B、H6-C1、H6-C2、H1-H5 源码与测试均未修改。
- **STATUS=BLOCKED**。冻结链路本身无需修改，但当前生产源码没有 action request/intent producer、正数
  `trackId` producer、calibration/profile owner，也没有调用 H6-A/H6-B/C2 的 runtime caller。禁止本轮接 decision
  engine、猜坐标或修改冻结阶段，因此无法把一个真实 production request 接入完整链路。
- 阻塞不削弱现有 fail-closed 状态：`AppConfig.ACTION_ENABLED=false`；没有 production caller；本轮没有动作、
  实机或坐标校准，故 `REAL_ACTION_REACHABLE=false`、`ACTION_EXECUTED=0`、
  `SELL_ZONE_CALIBRATED=false`。

## 1. 已确认的冻结合同

### A. H6-A：Safety Gate

实际入口为纯函数
`JinChanActionSafetyGate.evaluate(intent: JinChanActionIntent, context: JinChanActionContext): JinChanActionGateResult`。

- 输入 intent 只有 `BuyShopSlot`、`MoveUnit`、`SellUnit`、`RefreshShop`、`BuyXp`。
- context 显式携带 action enable、目标包、frame/evidence session、sequence freshness、stable UI、Shop、
  ownership/revision/reconcile/trust/ambiguity/duplicate 标志及调用方 UI evidence。
- 输出只有 `Approved(ApprovedJinChanAction)` 或 `Rejected(reason, intent)`；代码中的阻断术语是
  **Rejected**，不是 Blocked。
- Approved provenance 仅有 `sessionId`、`evidenceSequence`、`ownershipRevision`、固定 target package。
- 全局 fail-closed：action disabled、包名非 `com.tencent.jkchess`、session 错配/非法、证据过期、非游戏或
  UNKNOWN/OTHER UI、重复/冲突 intent。动作专属门还覆盖 slot/uid/location、Shop/ownership/revision、
  identity、board/bench trust、reconcile/ambiguity 及显式 UI evidence。

### B. H6-B：Gesture Resolver

实际入口为纯函数
`JinChanGestureResolver.resolve(JinChanGestureResolverInput): JinChanGestureResolveResult`。输入必须是 H6-A
`ApprovedJinChanAction`，另需 caller 提供 nullable `JinChanCoordinateProfile`；Move/Sell 还需 caller 提供
`sourceLocation`。

- 输出 `Resolved(action, GestureSpec, resolver provenance)` 或 `Blocked(action, reason)`。
- profile 是全屏 normalized `[0,1]` 显式校准表：5 个 Shop slot、4×7 Board map、9 个 Bench slot、
  refresh、buy-XP、nullable sell target；resolver 不读取 ROI、不换算 pixel。
- missing profile → `PROFILE_UNAVAILABLE`；目标、来源、目的地缺失或非法分别 typed BLOCKED。
- Sell 在来源有效后仍要求 `sellTarget`；null → `SELL_TARGET_UNCALIBRATED`。
- Resolved provenance 为 `resolverId` + `profileId`；H6-A provenance 随 `action` 原样保留。

### C. H6-C1：Execution Envelope Adapter

实际 API 为
`JinChanExecutionAdapter.adapt(JinChanExecutionAdapterInput): JinChanExecutionAdapterResult`。输入是 H6-B
`Resolved` 加 `actionId`、`trackId`、created/expires uptime；输出 `Ready(resolved, AutomationAction)` 或
`Rejected(reason)`。

它仅验证固定包、正 actionId/trackId 和合法时间窗，原样保留 gesture/Resolved，构造 package allow-list
严格等于 `{com.tencent.jkchess}` 的 `AutomationAction`。它不检查 Android runtime、不 dispatch。

### D. H6-C2：Armed Dispatch Boundary

`JinChanExecutionCoordinator` 的实际 API：

- `startSession(AutomationConfig, GestureBackend)`；
- `stopSession()`；
- `cancelPending()`；
- `suspend execute(H6-B Resolved, positive trackId): JinChanExecutionResult`。

Coordinator 独占一个 `GestureArbiter`、action ID sequence、2000ms TTL 与在飞 job。它在 C1 前及紧邻
arbiter/factory 前检查 runtime active、saved automation enabled、免责声明、production kill switch；校验
package invariant/expiry；把 dispatcher receipt 映射成 Completed/Rejected/Cancelled/Expired。它不负责
decision、resolver、profile、track 创建或 ledger commit。

## 2. 唯一 C3 runtime entry point

最终允许的最小入口应是新增 singleton
`data/jinchan/execution/JinChanRuntimeActionIntegration` 的一个 `suspend submit(request)`。其**唯一职责**：

1. 接收一个不可变、已关联的 request；
2. 严格顺序调用冻结 H6-A → H6-B → C2（C2 内部固定调用 C1）；
3. 在任一 non-success 立即返回统一 typed result/provenance；
4. 不持有 arbiter，不接触 dispatcher/platform transport，不造 intent/track/profile，不 commit ledger。

不应把 orchestration 塞入 `VisionRuntimeController`：该 controller 继续只做 frame/session 生命周期并开关 C2。
在 production producer 尚不存在时，也不应仅为“看似接通”而让 controller 从 shadow snapshot 猜 action。

## 3. 完整 typed pipeline 与责任人

```text
INPUT JinChanRuntimeActionRequest
  { intent, context, trackId, coordinateProfile?, sourceLocation? }
  → H6-A JinChanActionSafetyGate.evaluate(intent, context)
      Rejected → RESULT GateRejected (dispatch=0)
      Approved → H6-B JinChanGestureResolver.resolve(approved + profile + sourceLocation)
          Blocked → RESULT ResolverBlocked (dispatch=0)
          Resolved → H6-C2 JinChanExecutionCoordinator.execute(resolved, trackId)
              → H6-C1 JinChanExecutionAdapter.adapt(resolved + coordinator metadata)
                  Rejected → RESULT EnvelopeRejected (dispatch=0)
                  Ready → C2 gates → one GestureArbiter → existing factory/dispatcher
              → RESULT Execution(Disabled/InvalidTrackId/PackageInvariantRejected/
                                 Completed/Rejected/Cancelled/Expired)
```

责任归属：

- **intent/request**：必须由未来、单独授权的 action producer 提供；当前不存在。Integration 只消费。
- **trackId**：必须由同一 producer 从一个稳定实体/操作键取得并随 request 固定；integration/C2 不生成。
  `AutomationAction` 注释称它为 Tracker stable ID，但当前 Clean Base runtime 没有 Tracker；Shop control 等非实体
  intent 也没有已定义 track key，因此 owner/生成规则仍是合同缺口。
- **calibration/profile**：必须由未来显式 calibration/profile repository/provider 提供 immutable session
  snapshot；当前只有 data class 与测试 synthetic profile，无 production instance/owner/default profile。
- **sourceLocation**：Move/Sell 应由与 H6-A ownership snapshot 同 revision 的 caller 提供；integration 不推测。
- **structured provenance**：H6-A 生成 approval provenance，H6-B 追加 resolver/profile provenance，C2/C1
  生成 execution action/receipt metadata；integration 只聚合并返回。

## 4. 各阶段 fail-closed 条件

| 阶段 | 不得向下游的条件 |
| --- | --- |
| INPUT/C3 | request 缺失；`trackId <= 0`；context 与 source/ownership 关联无法证明；profile 缺失；未知 producer |
| H6-A | 任一 `JinChanActionRejectionReason`；只有 Approved 能进 H6-B |
| H6-B | 任一 `JinChanGestureBlockedReason`；特别是 missing profile、missing calibration、Sell target null |
| C1 | target package、action ID、track ID、时间窗非法；只有 Ready 能进 arbiter |
| C2 | runtime inactive、automation disabled、免责声明不足、`ACTION_ENABLED=false`、包 invariant、expiry、取消 |
| Arbiter/dispatcher | action 已过期；timeout/异常；receipt id/track mismatch；平台前台/package/window/capability 拒绝 |

所有 Rejected/Blocked/Disabled/Invalid/Expired/Cancelled 都是 terminal，均不得重试式绕过或进入下一 dispatch。

## 5. trackId 生命周期与唯一 owner

一次 request 创建前由 producer 确定正数 `trackId`，从 C3 input → C2 input → C1 envelope →
`AutomationAction` → `DispatchReceipt` 全程不变；arbiter 校验 receipt 的 action ID 与 trackId 防串单。C2 只生成
action ID，不拥有 trackId；C3 也不得生成。当前没有 producer/Tracker，因此无法指定 production 唯一 owner，
是 blocking `CONTRACT_GAP`。在 owner 获授权前不能借用 uid、frame sequence、slot 或随机数冒充 trackId。

## 6. calibration/profile 与 Sell 事实

源码不存在 `JinChanCoordinateProfile` 的 production 构造、repository 或默认实例；唯一实例都在 H6-B 单测。
因此当前 production profile **不存在**，自然不含 sell target；`SELL_ZONE_CALIBRATED=false`。将来 provider 必须：

- 提供显式版本/profileId 的不可变 session snapshot；缺失整体 BLOCKED；
- 不使用 ROI 作为 hitbox，不重复 normalized→pixel converter；
- 在另行校准并授权前始终 `sellTarget=null`，使 Sell typed BLOCKED；
- 不因 synthetic test profile 含 sell target 就声称 production 已校准。

## 7. provenance/result 最小合同

建议 C3 自己新增（不改冻结 value types）：

```text
JinChanRuntimeActionRequest(
  requestId, intent, context, trackId, coordinateProfile?, sourceLocation?
)

JinChanRuntimeActionProvenance(
  requestId,
  trackId,
  sessionId,
  evidenceSequence,
  ownershipRevision?,
  targetPackage?,
  resolverId?,
  profileId?,
  actionId?
)

JinChanRuntimeActionResult =
  GateRejected(reason, provenance)
  | ResolverBlocked(reason, provenance)
  | Execution(result, provenance)
```

`EnvelopeRejected` 可由 `Execution(AdapterRejected)` 表达，避免重复语义；日志只记录 enum/IDs，不序列化整份
context。Completed/Rejected/Cancelled/Expired 的 receipt 提供 actionId，非 dispatch 结果的 actionId 为 null。
当前 H6-A Rejected 不含 provenance，C1 Rejected 不回显 input，故 C3 必须从 immutable request/已批准值聚合，
不能伪称冻结类型自身已提供 end-to-end trace。

## 8. GestureArbiter / transport 边界审计

生产源码检索只发现 `JinChanExecutionCoordinator` 构造 `GestureArbiter`；测试会按需直接构造用于领域测试。
因此不存在第二个 production arbiter owner。C3 只调用 coordinator，不直接触碰
`GestureDispatcherFactory`、Accessibility、Shizuku、Root 或 shell；normalized→pixel 仍只在既有 dispatcher 层。

## 9. C3 最小拟议文件清单（解除阻塞后）

| 文件 | 必要性 |
| --- | --- |
| `app/src/main/java/top/azek431/hzzs/data/jinchan/execution/JinChanRuntimeActionIntegration.kt` | 新增唯一 typed request/result/provenance 与固定顺序 orchestration；不改冻结阶段 |
| `app/src/test/java/top/azek431/hzzs/data/jinchan/execution/JinChanRuntimeActionIntegrationTest.kt` | fake factory/dispatcher 的完整 click/drag 链路及所有 terminal no-dispatch 证明 |
| `app/src/main/java/top/azek431/hzzs/data/vision/CLAUDE.md` | 实现后同步 runtime ownership/链路文档；不属于冻结生产源码 |
| `docs/migration/H6C_EXECUTION_WIRING_PLAN_20260915.md` | 实现后只追加 C3 交付状态/实测 SHA，不改冻结 C1/C2 合同 |

`VisionRuntimeController.kt` **不在最小修改清单**：它已管理 C2 session 生命周期；在 producer 缺失时添加
submit 调用必然是在制造 decision/action。若后续授权的 producer 明确属于该 controller，再单独审查是否需要一行
委托；不得预先修改。

## 10. test-only synthetic click/drag 设计与矩阵

测试通过 internal/test constructor 注入 `productionActionEnabled={true}` 的既有 C2 coordinator 及返回
`DispatchReceipt` 的 fake `GestureDispatcherFactory`。这只走 JVM fake，不构造 Android service，不调用真实 factory。
Synthetic profile 使用显式 `[0,1]` 点；生产 kill switch 测试必须另用 false。

| 用例 | 期望 | fake dispatch 次数 |
| --- | --- | ---: |
| production kill switch false | Execution.Disabled(PRODUCTION_ACTION_DISABLED) | 0 |
| H6-A action disabled / stale / package / scene rejected | GateRejected（各 typed reason） | 0 |
| profile null | ResolverBlocked(PROFILE_UNAVAILABLE) | 0 |
| Buy slot target null | ResolverBlocked(TARGET_UNCALIBRATED) | 0 |
| Sell 的 production-like profile `sellTarget=null` | ResolverBlocked(SELL_TARGET_UNCALIBRATED) | 0 |
| Move/Sell source missing/uncalibrated | ResolverBlocked | 0 |
| invalid trackId | terminal InvalidTrackId（建议 C3 输入先挡） | 0 |
| runtime/automation/disclaimer disabled | typed Disabled | 0 |
| synthetic calibrated Buy/Refresh click | Completed，gesture/provenance/track 不变 | 1 |
| synthetic calibrated Move drag | Completed，start/end/duration 不变 | 1 |
| synthetic Sell drag（仅测试 profile） | Completed，但不得改变 production calibration 声明 | 1 |
| fake REJECTED/CANCELLED/EXPIRED/mismatched receipt | typed terminal；无第二次 dispatch | 1 |
| 两个并发 synthetic request | coordinator 单 arbiter 串行，max-in-flight=1 | 2 |

还应做 source-level prohibition：C3 文件不得包含 platform transport、pixel converter、ROI、decision engine、
第二个 `GestureArbiter` 或直接 `dispatcher()` 调用。

## 11. CONTRACT_GAP / UNKNOWN

1. **BLOCKING — action producer absent**：没有生产 caller 生成 `JinChanActionIntent` / `JinChanActionContext`。
2. **BLOCKING — track owner absent**：没有 Tracker，且非实体 intent 的稳定 track key 规则未定义。
3. **BLOCKING — calibration owner absent**：没有 profile provider/repository/default/session binding；全部坐标仅为测试值。
4. **BLOCKING — source provenance join absent**：没有合同说明如何保证 `sourceLocation` 与 H6-A ownership
   revision/session 来自同一 snapshot。
5. **NON-BLOCKING for precheck — unified provenance absent**：可在新 C3 类型中聚合，无需修改冻结阶段。
6. **UNKNOWN — authoritative remote identity**：本地 checkout 没有 configured git remote，故可验证 exact base SHA，
   不能从本地 remote metadata 复核其来源为 `lihongjun1032142406-eng/hzzs`。

这些缺口都不要求修改冻结 H6-A/B/C1/C2；但前四项未由后续明确合同/授权解决前，C3 production integration
不得实现。因此本次按要求 `STATUS=BLOCKED`，不自行接 decision engine、不猜 owner/坐标。

## 12. 审计文件

完整读取/检索覆盖：H6-A/B/C1/C2 四个 production 文件及四个对应 JUnit 文件；
`VisionRuntimeController.kt`；`AppModels.kt`；`AutomationModels.kt`；`GestureDispatcherFactory.kt`；
`data/vision`、`domain/automation`、`service/automation` 目录 CLAUDE；全部 `data/jinchan` production/test 文件名与
track/profile/call-site 检索；H6-A、H6-B、H6-C/C2 migration 合同文档。

```text
BASE_SHA=a7001d7382932387222c5901ad1121ac6d594c03
FILES_INSPECTED=H6-A/B/C1/C2 source+tests; VisionRuntimeController; AppModels; AutomationModels; GestureDispatcherFactory; JinChan production/test inventory; H6 migration contracts; scoped CLAUDE files
PROPOSED_FILES=JinChanRuntimeActionIntegration.kt; JinChanRuntimeActionIntegrationTest.kt; data/vision/CLAUDE.md; H6C_EXECUTION_WIRING_PLAN_20260915.md
CONTRACT_GAPS=action producer absent; trackId owner/rule absent; calibration/profile owner absent; sourceLocation-to-ownership provenance join absent; unified C3 provenance absent; authoritative remote identity locally unverifiable
FROZEN_FILES_CHANGED=0
PRODUCTION_CHANGED=false
REAL_ACTION_REACHABLE=false
ACTION_EXECUTED=0
SELL_ZONE_CALIBRATED=false
STATUS=BLOCKED
```
