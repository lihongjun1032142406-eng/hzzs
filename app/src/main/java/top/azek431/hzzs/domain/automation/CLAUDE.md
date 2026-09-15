# domain/automation — 自动操作领域模型

纯 Kotlin、可 JVM 单测。描述**归一化手势规格、动作任务、串行仲裁与跨帧去重**，**不直接调用无障碍 / Shell API**。真正的平台注入由 `service.automation` 完成。

## 职责

- `GestureSpec`：归一化手势规格（点击/滑动 + 双击延迟）。
- `AutomationAction`：一次待执行任务（trackId、avoidance、TTL、包名/窗口前缀白名单）。
- `DispatchOutcome` / `DispatchReceipt` / `GestureDispatcher`：分发终态与平台抽象（测试可替换为假实现）。
- `GestureArbiter`：系统手势的**唯一串行闸门**，持锁等待系统回执或超时。
- `ActionCommitLedger`：track + 空间短时去重账本。
- `TriggerDistanceAutoTuner`：触发距离（玩家宽度倍数）运行时自调（升/降 + 冷却 + 步长上限）。

## 入口

1. `AutomationModels.kt` — 手势/动作/回执/仲裁器/账本。
2. `TriggerDistanceAutoTuner.kt` — 触发距离自调。
3. `domain/vision`（被本包消费 avoidance、Detection）与 `service/automation`（实现 `GestureDispatcher`）是上下游。

## 数据流

```text
JinChanExecutionCoordinator（H6-C2 唯一 arbiter owner）
   └─ arbiter.dispatch(action)
               └─ GestureDispatcherFactory.dispatcher(backend).dispatch
                     ├─ ACCESSIBILITY → HzzsAccessibilityService.dispatchGesture
                     ├─ SHIZUKU      → ShellInputGestureDispatcher (input tap/swipe + dumpsys)
                     └─ ROOT         → ShellInputGestureDispatcher (input tap/swipe + dumpsys)
```

## 不变量 / 安全 / 线程 / 坐标

- 坐标：手势使用**全屏归一化 [0,1]**，由分发层换算像素；本包不做换算。
- 安全：本包**只做规则与门控数据结构**，不执行无障碍/Shell。包名门控在 `AutomationAction.matchesPackage`：**空集 = 不限制**，非空才门控。
- `GestureArbiter`：`dispatch` 在持锁期间等待系统回执或超时；超时后**仍持锁排空** `POST_TIMEOUT_DRAIN_MS`，避免「超时即空闲」导致叠点。预算覆盖双击间隔 + 单次按压 shell 开销 + 冷启动候选试探。
- `ActionCommitLedger.canPlan`：同步快照读，使用 `tryLock`，失败时 **fail-closed（视为不可规划）**，避免帧环阻塞。track 冷却 `TRACK_COOLDOWN_MS=900`（**非永久**封禁，否则同 track 持续靠近会永远 skip）。
- `TriggerDistanceAutoTuner`：范围 `0.5..8`（与 `validated` 中触发距离 clamp 一致）；不写磁盘，由调用方节流落盘。
- 线程：本包结构纯同步；线程安全与串行化由 `service.automation` 与 `data.vision` 保证。

## 改这个包前必读

- 改 `GestureSpec`：同步 `service.automation`（无障碍 `GestureDescription.StrokeDescription`、Shell `input tap/swipe` 的参数映射）与 `data.vision.VisionRuntimeController.planGestures`（按 avoidance 映射手势形态）。
- 改 `AutomationAction.allowedPackages` 语义：空集=不限制贯穿规划、分发、`VisionRuntimeController.dispatchPlan` 三处，**不要**在某处把它解释成「限制所有」。
- 改仲裁超时常量：同步 `ShellInputGestureDispatcher` 的 `FAIL_FAST_CANDIDATE_TIMEOUT_MS` 与 arbiter 预算计算（`PER_PRESS_SHELL_OVERHEAD_MS` / `FAIL_FAST_CANDIDATE_BUDGET_MS` / `ARBITER_SLACK_MS`）。
- 自动操作默认关闭；配置导入与迁移不得静默开启。

## 测试

- 本包是 JVM 单测黄金位置：`GestureArbiterTest`（串行/超时/排空）、`ActionCommitLedgerTest`（track/空间冷却、tryLock fail-closed）、`TriggerDistanceAutoTunerTest`（升降/冷却/步长/范围 clamp）、`GestureSpec` 校验（坐标范围、点击/滑动互斥、时长）。
- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。
