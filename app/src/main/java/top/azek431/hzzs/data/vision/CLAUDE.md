# data/vision — 视觉运行时（帧循环所有者）

应用运行时的**编排层**。Clean Base 的 `VisionRuntimeController` 是截图会话与外层生命周期 owner；H6-C2 的单一 `JinChanExecutionCoordinator` 独占 `GestureArbiter`，controller 只转发生命周期与 best-effort cancellation。

> 目录名是 `data`，但真实职责是「运行时编排」—— 看调用链比看包名更准（`docs/navigation/README.md`）。

## 职责

- `VisionRuntimeController`：帧循环唯一所有者、`generation` 令牌防陈旧写回、配置快照订阅、安全边界变化时取消动作。
- `MultiObjectTracker`：场景内多目标追踪，按中心距离+IoU 关联。
- `NativeVisionEngine`：JNI 适配层，实现领域 `VisionEngine`；`analyze` + `configureAlgorithm` 安全点。
- `DefaultActiveAlgorithmProvider` / `NativeBenchmarkRunner`：算法激活与基准（概念属 domain，因 Hilt 绑定在此）。
- `DebugFrameRecorder`：可选调试帧采样（仅存私有目录，默认关）。

## 入口（按阅读顺序）

1. `VisionRuntimeController.kt` — 主循环。改自动操作/帧循环/安全点必读。
2. `MultiObjectTracker.kt` — 追踪器。
3. `NativeVisionEngine.kt` — JNI 适配。
4. `NativeBenchmarkRunner.kt` / `DefaultActiveAlgorithmProvider.kt` / `DebugFrameRecorder.kt`。

## 数据流

```text
FrameSource（service.capture）
  → VisionRuntimeController.runLoop（完成驱动，无固定 FPS sleep）
      → NativeVisionEngine.analyze（Dispatchers.Default, JNI）
      → VisionResultValidator.sanitize（domain.vision）
      → MultiObjectTracker.update（按 analysisSequence）
      → withApproximateDisplayContour（仅 HUD）
      → OverlayController.show（service.overlay, 呈现层）
      → JinChan shadow frame bridge

H6-C2（当前无 decision producer，且 ACTION_ENABLED=false）：
  VisionRuntimeController lifecycle → JinChanExecutionCoordinator
    → enable gates → one GestureArbiter → GestureDispatcherFactory

H6-C4C 上游骨架：`publishFrame` 返回的显式 same-frame state 只尝试进入
`JinChanSameEvidenceAssembler`；production ownership join 尚不存在时 typed-block。只有完整同源 snapshot
才调用 fail-closed Decision skeleton，结果在 controller 内终止，不生成 intent/request，也不调用 H6-C3。
```

### 配置流

```text
SettingsRepository.config / savedConfig
  → combine → withSavedSafetyGates（自动操作/截图后端/强制后端取 saved）
  → latestConfig / safetyBoundaryChanged → cancelActions / restart
```

## 不变量 / 安全 / 线程 / 坐标

- 线程：生命周期（start/stop/restart）在 `lifecycleMutex` 下串行；帧循环在 `scope`（Default）；动作在独立 `actionJob`，与分析解耦。
- `generation` 令牌：stop/start 递增，用于 fail-closed 丢弃陈旧帧与动作。
- 完成驱动取帧：**无固定 FPS sleep**，上一轮完成后直接 `nextFrame`；HUD 显示时临时 `INVISIBLE` 并等一次显示提交，MediaProjection/AUTO 再排空一张可能含旧合成层的帧。
- H6-C2 coordinator 只拥有执行 envelope/arbiter/typed receipt，不拥有 decision、坐标解析或 ledger commit。
- 坐标：视觉结果与手势规划使用视口归一化 [0,1]；像素换算只在绘制层与手势分发层。
- 来源：`Detection.source` 从 JNI 正常结果与 `FilteredDetection` 诊断映射后，经 Validator / Tracker / `AlgorithmDetectionTrace` 保留；未知 native code 回退 `DEFAULT_HEURISTIC`。
- 自动操作默认关闭；H6-C2 还受免责声明与 `AppConfig.ACTION_ENABLED` 编译期总闸门控。
- 安全边界变化（场景/截图后端/自动操作/包名限制/手势后端）→ `cancelActions()`；手势后端切换额外 `clearShellCaches()`。
- 设置收集器只替换不可变配置快照，不直接操作引擎。
- `withSavedSafetyGates`：自动操作与截图后端**强制取 saved**，避免设置草稿未保存就派发手势或换源。

## 改这个包前必读

- 改帧循环：先确认 `generation` 语义（陈旧写回防护）与 `lifecycleMutex` 边界。
- 改 `maybeDispatch` / `dispatchPlan`：同步 `domain.automation`（arbiter/ledger/tuner）、`service.automation`（dispatchers/foreground probe）。
- 改 `planGestures`：同步 `domain.vision.Avoidance` 枚举与 `DisplayNames.kt`。
- 改 `NativeVisionEngine.analyze`：JNI 仅借用 `frame.argb`，返回后不得再访问该数组地址；视口裁剪坐标 → 全屏坐标映射与 `jni_bridge` 一致。
- 改 `DebugFrameRecorder`：文件仅存私有目录、默认关、MCP `capture_debug_frame` 可绕过间隔门控；`getBytes` 做了路径穿越防护（basename + canonical 校验）。

## 为什么这些文件挂在 data/vision 下

`data/vision` 是**运行时编排层**，不是纯数据层。下列文件挂在包下是「所有权 + Hilt 绑定」使然，**不是职责归属**：

- `DefaultActiveAlgorithmProvider.kt`：算法激活的**领域契约实现**（概念属 `domain.vision.ActiveAlgorithmProvider`）。挂这里只因 Hilt `@Binds` 在 `VisionEngineBindings` 中一并注册，便于运行时独占引擎。**改激活逻辑优先看 `core/algorithm/AlgorithmActivationCoordinator`**。
- `DebugFrameRecorder.kt`：调试帧采样器（仅存私有目录）。挂这里因帧循环是唯一调用者，需直接借用 `CapturedFrame`；**不涉及算法规划**，仅是呈现/诊断。
- `NativeBenchmarkRunner.kt`：Native 基准测试。挂这里因需直接调用 `NativeVisionEngine`；**不改识别行为**，仅测耗时/稳定性。

> 真实职责看调用链：帧循环、Tracker、JNI 适配三者强耦合组成运行时；上三者是运行时「借用」的协作者。详见 `docs/navigation/README.md`。

## 算法激活

`configureAlgorithm` 失败必须回退 `AlgorithmRuntimeProfile.builtin()`，保持引擎可用。

## 测试

- 相关测试：`FrameSequenceTest`、`VisionResultValidatorTest`、`GestureArbiterTest`、`SettingsSessionTest`。
- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。
- 真机 MCP 联调（可选）：`adb forward tcp:18765 tcp:8765` → `get_runtime_snapshot` / `get_automation_gates` / `start_analysis` / `cancel_actions`。
