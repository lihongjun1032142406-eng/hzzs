# H6-C2 PRECHECK — Execution Wiring / Arbiter Runtime Boundary

审计日期：2026-09-15。
阶段：仅 PRECHECK + Contract Freeze 输入证据；**未实现 H6-C2，未执行任何动作**。

## 1. BASE / HEAD

- Commander 指定 BASE：`4f774bd275bce963ac0b9f3e066fbdc37ea24759`。
- 审计开始时实际 HEAD：`4f774bd275bce963ac0b9f3e066fbdc37ea24759`。
- 审计开始时实际分支：`work`，不是任务描述中的 `jinchan-h6c-execution-wiring`。本审计不切分支、不改写历史。
- H6-C1 实现提交为 `701a58f`，其测试提交为当前 BASE/HEAD `4f774bd`。

## 2. Inspected files

完整检查了以下真实源码与相邻约束：

- `app/src/main/java/top/azek431/hzzs/domain/automation/AutomationModels.kt`
- `app/src/main/java/top/azek431/hzzs/service/automation/GestureDispatcherFactory.kt`
- `app/src/main/java/top/azek431/hzzs/service/automation/ForegroundWindowProbe.kt`
- `app/src/main/java/top/azek431/hzzs/service/automation/HzzsAccessibilityService.kt`
- `app/src/main/java/top/azek431/hzzs/service/automation/ShellGestureDispatchers.kt`
- `app/src/main/java/top/azek431/hzzs/data/vision/VisionRuntimeController.kt`
- `app/src/main/java/top/azek431/hzzs/core/model/AppModels.kt`
- `app/src/main/java/top/azek431/hzzs/core/preferences/SettingsRepository.kt`
- `app/src/main/java/top/azek431/hzzs/platform/compat/GestureCapabilities.kt`
- `app/src/main/java/top/azek431/hzzs/data/jinchan/action/{JinChanActionContract,JinChanActionSafetyGate,JinChanGestureResolver}.kt`
- `app/src/main/java/top/azek431/hzzs/data/jinchan/execution/JinChanExecutionAdapter.kt`
- `docs/migration/H6C_EXECUTION_WIRING_PLAN_20260915.md`
- 上述包级 `CLAUDE.md`、相关 JVM 测试及全仓符号调用点。

注意：`domain/automation`、`service/automation`、`data/vision` 的包级说明仍描述旧的
`maybeDispatch → actionJob → GestureArbiter` 链路；当前生产源码已经清退该链路。这些说明不能作为当前可达性的证据。

## 3. Exact current call graph

### 3.1 JinChan H6 链

当前生产代码只有相互独立的纯值能力：

```text
JinChanActionSafetyGate.evaluate
  → JinChanActionGateResult.Approved（H6-A capability value）

JinChanGestureResolver.resolve(ApprovedJinChanAction + caller profile/source)
  → JinChanGestureResolveResult.Resolved（H6-B value）

JinChanExecutionAdapter.adapt(Resolved + caller IDs/timestamps)
  → JinChanExecutionAdapterResult.Ready(AutomationAction)（H6-C1 value）
  → STOP
```

生产源码中没有调用 `JinChanActionSafetyGate.evaluate`、`JinChanGestureResolver.resolve` 或
`JinChanExecutionAdapter.adapt` 的编排器；它们目前仅由 JVM 测试直接调用。

### 3.2 现有 transport 链（当前无生产上游）

```text
GestureArbiter.dispatch(action)                       [仅 JVM 测试调用]
  → GestureDispatcher.dispatch(action)
    → AccessibilityGestureDispatcher
      → HzzsAccessibilityService.dispatchCurrent
        → HzzsAccessibilityService.dispatch
          → ensureFreshForeground
          → normalized → real-display pixels
          → AccessibilityService.dispatchGesture
    OR ShizukuGestureDispatcher
      → ShellInputGestureDispatcher.dispatch
        → ShellForegroundProbe.snapshot (dumpsys)
        → normalized → real-display pixels
        → ShellProcessSupport.runShizukuResult (`input tap/swipe`)
    OR RootGestureDispatcher
      → ShellInputGestureDispatcher.dispatch
        → ShellForegroundProbe.snapshot (dumpsys)
        → normalized → real-display pixels
        → ShellProcessSupport.runRootResult (`input tap/swipe`)
```

`DefaultGestureDispatcherFactory.dispatcher` 本身也没有生产调用点。`VisionRuntimeController`
虽然注入 `GestureDispatcherFactory`，当前只保留该未使用字段；没有创建 `GestureArbiter`，没有
`actionJob`，没有任何 `.dispatch()`。

## 4. Runtime ownership findings

比较结果：

| 方案 | 真实源码评价 |
| --- | --- |
| A. 全部直接塞入 `VisionRuntimeController` | 它是现有 start/stop/restart、saved config、有效 backend 与 session generation 的唯一 owner，接生命周期最短；但当前职责明确是 capture-only，把 ID/TTL、arbiter、receipt/ledger 全塞回去会重新扩大控制器并耦合 JinChan。 |
| B. 新建 `JinChanExecutionCoordinator` / runtime | 可集中唯一 arbiter、clock、in-flight job、二层 enable gate 与 receipt 映射；由 `VisionRuntimeController` 只做 session start/stop/cancel 委托，避免多个 arbiter。最小且边界最清晰。 |
| C. 复用既有 automation runtime owner | 当前不存在。旧 owner/旧 `maybeDispatch` 只残留在过期包文档，不能复用。 |

**建议冻结 B**：唯一 runtime owner 为 application-scoped `JinChanExecutionCoordinator`（名称可在
Contract Freeze 统一），`VisionRuntimeController` 仍是外层 session/lifecycle owner，只传入已保存安全配置、
有效 backend 与 generation，并在 stop/restart/cancel 时委托 coordinator。不能让 UI、decision、adapter 或
dispatcher 直接持有第二个执行入口。

## 5. Clock / TTL findings（FIX1 已冻结）

- H6-C1 把 `actionId`、`trackId`、`createdAtUptimeMs`、`expiresAtUptimeMs` 当显式输入，自己不造 clock；在 C2 runtime 中这些 execution metadata 由 coordinator 提供，只有 `trackId` 由 C2 caller 提供。
- `AutomationAction` 注释写 `SystemClock.uptimeMillis`；`GestureArbiter` 只接收 `() -> Long`，不强制具体 Android clock。
- H6-C2 的权威 TTL clock domain 冻结为 `SystemClock.uptimeMillis()`。唯一 coordinator 用同一个注入的 clock
  函数实例生成 created/expires、执行 expiry recheck，并构造其唯一 arbiter；不得混用 `elapsedRealtime`。
- Accessibility/Shell 使用的 foreground freshness clock 保持 transport 现状，它不参与 AutomationAction TTL 比较，
  也不属于 H6-C2 TTL domain。
- `actionId`：建议由 coordinator 的单调 `AtomicLong` 生成，且进程内唯一。
- `trackId`：必须来自产生 intent 的 JinChan 稳定实体/操作键；当前 Clean Base 没有 action producer 或可供
  H6 执行复用的 tracker，因此**不能在 C2 中虚构**。
- TTL 已冻结为 `ACTION_TTL_MS=2000L`，由 coordinator 统一应用，不能由 H6-C1、resolver 或 dispatcher 决定。

## 6. Arbiter findings

- 当前生产代码**没有创建任何 `GestureArbiter`**；所有构造与 `dispatch` 调用都在
  `GestureArbiterTest`。
- Arbiter 内部 `Mutex` 串行整个 dispatch，顺序为 expiry → dispatcher + timeout → receipt identity 校验。
- 它在主 timeout 后仍持锁 drain 1500ms；不能在外层再实现一套并行 timeout。
- 建议 coordinator 每个 application runtime 只持有一个 arbiter 实例。backend 动态解析应通过 arbiter 的
  单一 delegating `GestureDispatcher` 在每次提交时读取 coordinator 冻结的 session backend，再调用 factory；
  backend 变化须先取消/排空旧 session，而不是 new 第二个 arbiter。
- restart/stop 必须先关闭入口并推进 generation，再取消 coordinator job；新 session 可复用同一串行 arbiter，
  但旧 job 未真实终止前不得提交新动作。

## 7. Dispatcher factory findings

- `GestureDispatcherFactory` 是 Hilt `SingletonComponent` 中的绑定；默认实现及 Accessibility/Shizuku/Root
  分发器均为 singleton，实际 dispatcher 被缓存于这些单例，而不是每次新建。
- `dispatcher(backend)` 是同步选择：ACCESSIBILITY → accessibility、SHIZUKU → shizuku、ROOT → root。
  误传 AUTO 时回到 Accessibility（注释称 fail-closed），但 factory **不做能力探测**。
- AUTO 必须先经 `resolveEffectiveGestureBackend`。该 resolver 优先 Accessibility，条件 Shizuku，AUTO 永不 Root；
  显式 Root/显式 Shizuku 由能力结果 fail-closed 报不可用。
- factory 没有内置 transport fallback、重试或 unavailable 判定。服务未连接/探测失败最终返回 REJECTED。
  `VisionRuntimeController` 当前另有 Shizuku 健康检查，在 Accessibility 已连接时显式降级；这不是 factory fallback。
- `clearShellCaches()` 只清 Shizuku/Root 的 dumpsys cache，不销毁 dispatcher。
- 最合适 factory owner 是唯一 coordinator；`VisionRuntimeController` 只解析/冻结 session backend。实现时应把当前
  controller 中未使用的 factory 注入移动给 coordinator，不能重新实现三种 transport。

## 8. Foreground safety findings

- Accessibility 最终 injection 前调用 `ensureFreshForeground()`；缓存超过 1500ms 会主动刷新，失败返回 REJECTED，
  然后校验 package 与 window class。双击两次之间会再次校验。
- Shizuku/Root 每次 dispatch 先用同源 `ShellForegroundProbe` 做 dumpsys。probe 成功缓存 TTL 300ms、失败缓存
  150ms；`ShellInputGestureDispatcher` 另拒绝年龄超过 1500ms 的快照，并校验 package/window；双击间隔再检。
- `snapshotForeground()` 可供规划期观察，但 final dispatcher 已完成真正 injection 前的权威校验。H6-C2 不应复制
  dumpsys/Accessibility freshness 逻辑；最多在 coordinator 做便宜的 envelope package invariant/expiry recheck，
  最终窗口安全仍由 dispatcher owner。
- C1 已强制 `allowedPackages = setOf(JinChanActionSafetyGate.JINCHAN_PACKAGE)`。C2 必须重检它仍恰好等于该单元素
  set；绝不能改成 `emptySet`，因为现有 `AutomationAction` 中 empty 意味着“不限制”。

## 9. Receipt findings

- `DispatchReceipt` 携带原 `AutomationAction`、`DispatchOutcome` 和可选 detail；arbiter 会校验 receipt 的 action id
  与 trackId，错配统一 REJECTED。
- `COMPLETED`：Accessibility 意味着系统 callback 完成；Shell 只意味 input 命令 exit 0，语义较弱。
- `CANCELLED`：系统 callback 取消或 arbiter timeout/drain 未完成。
- `REJECTED`：前台/窗口/服务/命令/回执身份/异常失败。
- `EXPIRED`：arbiter 在调用 dispatcher 前发现 TTL 已到。
- H6-C2 应返回 typed JinChan result，保留原 receipt/outcome/detail；只有 `COMPLETED` 可调用
  `ActionCommitLedger.commit`。REJECTED/CANCELLED/EXPIRED 均不得 commit、retry 或假定动作未发生。
- 但既有 H6-C plan 明确 C2 “no ledger ownership mutation”；因此 C2 只映射结果，commit 应留给更外层、且只能在
  COMPLETED 分支。当前 Clean Base 没有 ledger owner，不能在 PRECHECK 虚构一个。

## 10. Timeout findings

- `GestureArbiter` 默认 `dispatchTimeoutMs=2000ms`，有效预算会按按压次数、gesture duration、双击间隔、shell
  cold-start probe 与 slack 增大，上限 12000ms；随后最多 drain 1500ms。
- Shell 每候选有自己的 transport timeout（非首选 380ms，首选/最后按 duration+900ms、700..2500ms）。
- H6-C2 没有证据需要 JinChan override；应保持 arbiter 默认，禁止 coordinator 再套业务 timeout。
- `dispatchTimeoutMs` 由唯一 arbiter owner 提供；Contract Freeze 应明确“不覆盖即使用默认”。

## 11. Cancellation / lifecycle findings

- `VisionRuntimeController` 是当前 runtime lifecycle owner：singleton scope、`lifecycleMutex` 串行 start/stop/restart，
  `generation` 在 start/stop 推进，stop 会 `cancelAndJoin(runtimeJob)`、停 source、HUD、前台服务和健康监控。
- 当前 `cancelPendingActions()` **只是日志 no-op**；当前没有 pending/in-flight action 或 action job。
- `GestureArbiter` 没有 `cancelPending` API。取消调用它的 coroutine 会取消 await/job；Accessibility finally 仅推进
  callback generation，使迟到回执失效，**没有调用系统 API 撤销已接受手势**。已接受的系统 gesture 可能仍执行。
- Shell coroutine/process 是否响应取消取决于 transport；源码没有“撤销已经交给 input 的命令”的契约。命令 exit 0
  后更不可能撤销。
- 因此未来 cancel 的诚实语义只能是：关闭新提交、取消尚未进入 injection 的 job、尽力等待/排空；不能声称撤销已经
  接受/执行中的系统手势。scene invalidation 也必须先关 gate/generation，再 best-effort cancel。

## 12. Enable-gate findings

存在两个含义不同的 enable：

1. `AppConfig.ACTION_ENABLED`：编译期 `const val false`，不受 Settings/MCP/import 改写；这是生产真实动作总闸。
2. `AutomationConfig.enabled`：持久化用户开关，默认 false；`validated()` 还要求当前免责声明版本。运行时通过
   `withSavedSafetyGates` 强制取 saved config，避免 preview 开启动作。
3. H6-A `JinChanActionContext.actionEnabled`：caller 提供的语义 gate，默认 false；当前没有生产 caller，也没有证据
   它已绑定以上任一配置。

存在明显语义混淆风险：H6-A 的同名 Boolean 不能替代编译期总闸或用户开关。H6-C2 至少冻结三层：

```text
H6-A semantic gate: context.actionEnabled
AND runtime user gate: savedConfig.automation.enabled + disclaimer current
AND production kill switch: AppConfig.ACTION_ENABLED
```

最重要的 runtime gate 应由唯一 coordinator 持有，并且位于任何 arbiter/factory 调用之前：

```kotlin
if (!AppConfig.ACTION_ENABLED) return Disabled // 不创建提交 job，不调用 arbiter/factory
```

建议构造 coordinator 时可以持有不产生 transport 的依赖，但**只有三层 gate 全通过后**才能解析 dispatcher 并调用
唯一 arbiter。由于当前常量为 false，生产 `GestureArbiter.dispatch()` 绝对不可达。

## 13. Current production reachability

```text
JinChan Decision（当前不存在）
  → H6-A（仅纯 object，无生产调用者）
  → H6-B（仅纯 object，无生产调用者）
  → H6-C1（仅纯 object，无生产调用者）
  → Ready
  → STOP
```

全仓生产源码不存在 `GestureArbiter(` 构造或 `GestureArbiter.dispatch` 调用；factory 的 dispatcher 选择也没有调用点。
因此 JinChan 无路径到 `GestureDispatcher.dispatch`、Accessibility、Shizuku、Root、shell input 或
`dispatchGesture`。平台 transport 类虽然存在并可由未来合法上游调用，但当前 JinChan production reachability 为
`REAL_ACTION_REACHABLE=false`，本审计 `ACTION_EXECUTED=0`。

## 14. Proposed H6-C2 minimal boundary

建议 Contract Freeze 的最小链：

```text
VisionRuntimeController session lifecycle / saved config / resolved backend
  → one JinChanExecutionCoordinator (single runtime owner)
    → reject unless ACTION_ENABLED && saved automation.enabled && disclaimer current
    → execute(H6-B Resolved, caller positive trackId)
    → generate actionId + created/expires with the coordinator clock
    → frozen JinChanExecutionAdapter.adapt
    → accept only H6-C1 Ready; C1 Rejected stops without dispatch
    → recheck exact singleton package + same-domain expiry + runtime gate
    → one GestureArbiter (same SystemClock.uptimeMillis clock instance; default timeout)
      → GestureDispatcherFactory.dispatcher(frozen effective backend)
        → existing dispatcher final foreground validation + transport
    → typed receipt mapping
    → COMPLETED only: outer owner may commit; all other outcomes fail closed
```

这不是实现授权。尤其不能在本阶段创建 coordinator/arbiter、调用 factory 或添加 dispatch。

## 15. Files expected to change in H6-C2 implementation

若 Commander 接受上述冻结，预计最小实现范围：

- 新增 `app/src/main/java/top/azek431/hzzs/data/jinchan/execution/JinChanExecutionCoordinator.kt`（唯一 owner、
  三层 gate、单 arbiter、receipt mapping；准确命名待冻结）。
- 修改 `app/src/main/java/top/azek431/hzzs/data/vision/VisionRuntimeController.kt`（只做 lifecycle/backend/config
  委托与真实 `cancelPendingActions` 的诚实接线；不放 JinChan decision/坐标逻辑）。
- 新增对应 JVM 测试（fake dispatcher/factory；生产常量仍 false；不得触及真实 transport）。
- 同步当前已经过期的 `data/vision`、`domain/automation`、`service/automation` 包级 `CLAUDE.md` 和 H6 migration
  contract 文档，使其不再声称已存在的旧 action 链。

FIX1 已冻结：C2 包含上述最小 `VisionRuntimeController` lifecycle wiring；C3 仍独占 H6-A → H6-B → H6-C
完整 runtime integration / Shadow-to-Armed Switch。

## 16. Files explicitly frozen

- H6-A：`data/jinchan/action/JinChanActionContract.kt`、`JinChanActionSafetyGate.kt` 及测试。
- H6-B：`data/jinchan/action/JinChanGestureResolver.kt` 及测试。
- H6-C1：`data/jinchan/execution/JinChanExecutionAdapter.kt` 及测试。
- H1-H5 perception、frame、state、ledger/reconciler 全部冻结。
- `AutomationModels.kt`、`GestureDispatcherFactory.kt`、`ForegroundWindowProbe.kt`、
  `HzzsAccessibilityService.kt`、`ShellGestureDispatchers.kt` transport 行为冻结；H6-C2 只复用。
- `AppConfig.ACTION_ENABLED=false`、`OVERLAY_DEFAULT_ENABLED=false` 冻结。
- Native、overlay、CI 冻结。

## 17. Contract Freeze resolution (FIX1)

- C2 = Armed Dispatch Boundary + `VisionRuntimeController` 最小 lifecycle wiring；C3 = 完整 runtime integration / Shadow-to-Armed Switch。
- C2 public input = H6-B `Resolved` + caller-supplied positive `trackId`。Coordinator 生成 actionId 与时间窗并调用冻结的 C1 adapter；只有 C1 `Ready` 可进入 arbiter。
- Clock = `SystemClock.uptimeMillis()`；envelope、expiry recheck 与唯一 arbiter 共用同一注入实例；TTL = 2000ms。
- typed receipt mapping 留在 C2；不做 ledger commit。cancel 继续是 best-effort，不虚构 Android/Shell 硬取消。

## 18. PRECHECK verdict

**PRECHECK PASS；FIX1 合同已冻结。** 当前边界、安全不可达性、可复用 transport、唯一 owner、uptime clock、
2000ms TTL、C2/C3 阶段切分及底层取消限制均已对齐真实实现。

```text
STATUS=PASS
PHASE=H6-C2_PRECHECK
BASE_SHA=4f774bd275bce963ac0b9f3e066fbdc37ea24759
BRANCH=work
PRODUCTION_CODE_CHANGED=false
TEST_CODE_CHANGED=false
H6A_CHANGED=false
H6B_CHANGED=false
H6C1_CHANGED=false
ACTION_ENABLED_CHANGED=false
REAL_ACTION_REACHABLE=false
ACTION_EXECUTED=0

RUNTIME_OWNER=proposed singleton JinChanExecutionCoordinator; VisionRuntimeController remains outer lifecycle owner
ARBITER_OWNER=the single JinChanExecutionCoordinator; currently none exists in production
DISPATCHER_FACTORY_OWNER=the single JinChanExecutionCoordinator; backend is frozen/resolved by VisionRuntimeController
CLOCK_OWNER=JinChanExecutionCoordinator using SystemClock.uptimeMillis; envelope and arbiter share one instance
TTL_OWNER=JinChanExecutionCoordinator; ACTION_TTL_MS=2000
FOREGROUND_VALIDATION_OWNER=existing final AccessibilityGestureDispatcher/HzzsAccessibilityService or ShellInputGestureDispatcher
ENABLE_GATE_OWNER=JinChanExecutionCoordinator, before arbiter/factory, in addition to H6-A semantic gate
CANCEL_PENDING_CURRENT_BEHAVIOR=no-op log; no pending/in-flight action exists and no hard system-gesture cancellation API exists

CURRENT_DEFAULT_CALL_GRAPH=no production execute caller -> STOP (ACTION_ENABLED=false)
PROPOSED_H6C2_BOUNDARY=H6-B Resolved + trackId -> coordinator gates/metadata -> C1 Ready -> one arbiter -> existing factory/dispatcher -> typed receipt

UNRESOLVED=NONE_FOR_H6_C2_FIX1
PRECHECK_VERDICT=READY_FOR_CONTRACT_FREEZE
```
