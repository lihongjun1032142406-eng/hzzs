# service/automation — 手势分发与前台探测

自动操作的**平台注入层**。按 `GestureBackend` 解析具体 `GestureDispatcher`，把归一化手势换算为屏幕像素后投递系统（无障碍 `dispatchGesture` / Shell `input tap/swipe` + dumpsys 前台）。**工厂本身不做能力探测**，AUTO 须先经 `platform.compat.resolveEffectiveGestureBackend`。

## 职责

- `GestureDispatcherFactory` / `DefaultGestureDispatcherFactory`：按 backend 分发。
- `HzzsAccessibilityService`：生产唯一 `dispatchGesture` 持有者；主线程协调手势分发、前台快照、无障碍截图、按文案找可点目标（自动复活）。
- `ShellInputGestureDispatcher`：Shell `input tap/swipe`（Shizuku/Root），候选命令 fail-fast + 记住首选前缀。
- `ShizukuGestureDispatcher` / `RootGestureDispatcher`：持有前台 probe 缓存（可重置）。
- `ForegroundWindowProbe` / `AccessibilityForegroundProbe` / `ShellForegroundParser` / `ShellForegroundProbe`：前台窗口快照（归一化字段）。
- `ShellProcessSupport`：Shizuku/Root 进程通道（与截图后端同源，须 public）。

## 入口

1. `GestureDispatcherFactory.kt` — 工厂。
2. `HzzsAccessibilityService.kt` — 无障碍服务（最大、最复杂）。
3. `ShellGestureDispatchers.kt` — Shell 分发器。
4. `ForegroundWindowProbe.kt` — 前台探测。
5. `ShellProcessSupport.kt` — 进程通道。
6. `domain/automation`（上游规则）、`data/vision/VisionRuntimeController`（调用者）、`platform/compat`（能力探测）。

## 数据流

```text
JinChanExecutionCoordinator → arbiter.dispatch(action)
  → GestureDispatcherFactory.dispatcher(backend).dispatch
      ├─ ACCESSIBILITY → HzzsAccessibilityService.dispatchGesture（主线程）
      ├─ SHIZUKU      → ShellInputGestureDispatcher（input tap/swipe + dumpsys）
      └─ ROOT         → ShellInputGestureDispatcher（input tap/swipe + dumpsys）

规划期前台快照：
  ├─ ACCESSIBILITY → AccessibilityForegroundProbe.blockingSnapshot
  └─ SHIZUKU/ROOT  → ShellForegroundProbe（dumpsys + ShellForegroundParser）
```

## 不变量 / 安全 / 线程 / 坐标

- 坐标：手势为全屏归一化 [0,1]，分发层 clamp 后按**真实显示尺寸**换算像素（`realDisplaySize` / `screenSize` 用 `maximumWindowMetrics` / `getRealMetrics`，避免刘海/导航条分叉导致点偏）。
- 无障碍：前台快照超 `FOREGROUND_STALE_MS=1500ms` 视为过期；仅当 `allowedPackages` 非空时做包名门控（空集=不限制）；服务未连接时 companion 入口 fail-closed。
- 双击：点击类手势可携带 `doublePressDelayMs`；完成第一下后延迟再发第二下，间隔内前台变化则拒绝。
- 候选命令：绝对路径 `/system/bin/input` 优先（PATH 空），再 `cmd input`，最后裸 `input`；非首选候选 `FAIL_FAST_CANDIDATE_TIMEOUT_MS=380ms` 快速失败，避免三条命令各等 1s+ 把 DOUBLE_JUMP 拖进「手势回调超时」。
- 手势 generation：超时/取消后递增，使迟到 `GestureResultCallback` 无法 complete 旧 deferred，配合 arbiter 超时后持锁排空。
- dumpsys 解析：`ShellForegroundParser` 纯函数（便于 JVM 单测），优先 topResumed/mResumed/mFocused/mCurrentFocus，回退 ActivityRecord/Window。
- 自动复活：与障碍动作独立；按精确文案「原地复活」「重新冒险」找可点祖先屏幕中心；冷却 `300ms`；需无障碍连接。
- 线程：Accessibility 回调与 dispatch 在主线程；截图回调在专用守护线程，结果回主线程续体；`foregroundSnapshot` 在非主线程时用 `Handler.post` + 短等（80ms）避免卡帧循环。

## 改这个包前必读

- 改 `HzzsAccessibilityService.dispatch`：同步 `domain.automation.GestureArbiter`（串行闸门）与 `VisionRuntimeController.dispatchPlan`（规划期已预检 ledger/前台/速率）。
- 改 `ShellInputGestureDispatcher`：同步 arbiter 预算计算（`PER_PRESS_SHELL_OVERHEAD_MS` / `FAIL_FAST_CANDIDATE_BUDGET_MS`）与 `ShellProcessSupport` 的进程通道。
- 改 `ForegroundWindowProbe` / `ShellForegroundParser`：同步 `VisionRuntimeController.maybeDispatch` 的门控逻辑（`restrictPackages` / `allowedPackages`）。
- 改 `ShellProcessSupport`：与 `service.capture.ShizukuFrameSource` / `RootFrameSource` 同源，改动须同时验证截图与手势两条路径。
- 改 `GestureDispatcherFactory.dispatcher`：AUTO 应在调用前解析为 concrete；若误传 AUTO，fail-closed 走无障碍。
- 手势后端与截图后端**正交**。

## 测试

- 相关测试：`GestureArbiterTest`、`ShellForegroundParserTest`（dumpsys 解析纯函数）。
- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。
- 真机验证：无障碍连接/断开、前台包名门控、双击间隔、Shell 候选 fail-fast、dumpsys 解析。
